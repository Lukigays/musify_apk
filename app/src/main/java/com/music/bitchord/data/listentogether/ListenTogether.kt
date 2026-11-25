package com.music.bitchord.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.music.bitchord.BitChordApplication
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

sealed interface ServerUrlError {
    data object Whitespace : ServerUrlError
    data object InvalidScheme : ServerUrlError
    data object InvalidHost : ServerUrlError
    data object InvalidPort : ServerUrlError
    data object InvalidPath : ServerUrlError
    data object HasPath : ServerUrlError
    data object HasQuery : ServerUrlError
    data object HasFragment : ServerUrlError
    data object Malformed : ServerUrlError
}

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data class Invalid(val error: ServerUrlError) : ServerUrlValidationResult
}

sealed interface ServerConnectionState {
    data object Checking : ServerConnectionState
    data class DefaultOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomFallback(val latencyMs: Long) : ServerConnectionState
    data object Offline : ServerConnectionState
}

data class ProbeResult(val isOnline: Boolean, val latencyMs: Long = 0L)

val ServerConnectionState.latencyMs: Long?
    get() = when (this) {
        is ServerConnectionState.DefaultOnline -> latencyMs
        is ServerConnectionState.CustomOnline -> latencyMs
        is ServerConnectionState.CustomFallback -> latencyMs
        ServerConnectionState.Checking, ServerConnectionState.Offline -> null
    }

val ServerConnectionState.isFallback: Boolean
    get() = this is ServerConnectionState.CustomFallback

val ServerConnectionState.health: ListenTogether.Health
    get() = when (this) {
        ServerConnectionState.Checking -> ListenTogether.Health.CHECKING
        is ServerConnectionState.DefaultOnline,
        is ServerConnectionState.CustomOnline,
        is ServerConnectionState.CustomFallback -> ListenTogether.Health.ONLINE
        ServerConnectionState.Offline -> ListenTogether.Health.OFFLINE
    }

/**
 * Musify Dengar Bersama (Listen Together) via zero-login TLS MQTT relays.
 *
 * Replaces BitChord's authenticated HTTP/WebSocket backend with Musify's
 * account-free MQTT room architecture (`broker.emqx.io:8883` / `broker.hivemq.com:8883` /
 * `test.mosquitto.org:8883` or optional custom TLS broker), while keeping
 * [PartySync][com.music.bitchord.playback.PartySync]'s exact playback sync contract,
 * plus live filtered room chat and `Antrean Voting` song proposals.
 */
object ListenTogether {

    enum class Connection { OFFLINE, CONNECTING, LIVE }

    data class State(
        val code: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        val error: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val isFull: Boolean get() = members.size >= maxMembers
        val controlsLocked: Boolean
            get() = inParty && hostOnlyControl && you?.isHost != true
    }

    class PartyException(val code: String, message: String, val statusCode: Int? = null) : Exception(message)

    sealed interface SwitchPartyResult {
        data class Success(val partyCode: String) : SwitchPartyResult
        data class TargetFailedRecovered(val partyCode: String, val targetError: String) : SwitchPartyResult
        data class TargetFailedNoParty(val targetError: String) : SwitchPartyResult
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock(SystemClock::elapsedRealtime)
    private val switchMutex = Mutex()
    private val stateMutex = Any()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activity = MutableStateFlow<List<PartyActivity>>(emptyList())
    val activity: StateFlow<List<PartyActivity>> = _activity.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<PartyChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<PartyChatMessage>> = _chatMessages.asStateFlow()

    private val _voteQueue = MutableStateFlow<List<PartyVoteProposal>>(emptyList())
    val voteQueue: StateFlow<List<PartyVoteProposal>> = _voteQueue.asStateFlow()

    fun parseAndNormalizeServerUrl(raw: String): ServerUrlValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ServerUrlValidationResult.Valid("")
        if (trimmed.any { it.isWhitespace() }) return ServerUrlValidationResult.Invalid(ServerUrlError.Whitespace)

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            val authority = candidate.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
            if (authority.isEmpty()) {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
            }
            val portStr = authority.substringAfterLast(':', "")
            if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) {
                val portNum = portStr.toLongOrNull()
                if (portNum != null && portNum !in 1..65535) {
                    return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
                }
            }
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
        }

        val scheme = uri.scheme?.lowercase() ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
        if (scheme != "http" && scheme != "https") {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
        }

        if (!uri.rawQuery.isNullOrEmpty() || candidate.contains("?")) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.HasQuery)
        }

        if (!uri.rawFragment.isNullOrEmpty() || candidate.contains("#")) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.HasFragment)
        }

        val pathSegments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        for (segment in pathSegments) {
            if (segment == "." || segment == "..") {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPath)
            }
        }
        val canonicalPath = if (pathSegments.isEmpty()) "" else "/" + pathSegments.joinToString("/")

        val port = uri.port
        if (port != -1 && port !in 1..65535) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
        }
        val authority = candidate.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
        val portStr = authority.substringAfterLast(':', "")
        if (portStr.isNotEmpty() && portStr.all { it.isDigit() } && authority.contains(":")) {
            val portNum = portStr.toLongOrNull()
            if (portNum != null && portNum !in 1..65535) {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
            }
        }

        val rawHost = uri.host ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
        if (rawHost.isEmpty()) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)

        val canonicalHost: String = when {
            rawHost.startsWith("[") && rawHost.endsWith("]") -> {
                val unbracketed = rawHost.substring(1, rawHost.length - 1)
                if (!unbracketed.contains(":")) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                "[${unbracketed.lowercase()}]"
            }
            rawHost.contains(":") -> {
                "[${rawHost.lowercase()}]"
            }
            rawHost.equals("localhost", ignoreCase = true) -> {
                "localhost"
            }
            else -> {
                if (rawHost.length > 253) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                if (rawHost.startsWith(".") || rawHost.endsWith(".")) {
                    return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                }
                val labels = rawHost.split('.')
                if (labels.size < 2) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                val labelRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$")
                for (label in labels) {
                    if (label.isEmpty() || label.length > 63 || !label.matches(labelRegex)) {
                        return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                    }
                }
                rawHost.lowercase()
            }
        }

        val portSuffix = if (port != -1) ":$port" else ""
        return ServerUrlValidationResult.Valid("${scheme.lowercase()}://$canonicalHost$portSuffix$canonicalPath")
    }

    private val _customServer = MutableStateFlow("")
    val customServerUrl: StateFlow<String> = _customServer.asStateFlow()

    val defaultServer: String = when (val res = parseAndNormalizeServerUrl(BuildConfig.LISTEN_TOGETHER_SERVER.ifBlank { "https://broker.emqx.io:8883" })) {
        is ServerUrlValidationResult.Valid -> res.normalizedUrl.ifBlank { "https://broker.emqx.io:8883" }
        is ServerUrlValidationResult.Invalid -> "https://broker.emqx.io:8883"
    }

    private val _effectiveIdleServer = MutableStateFlow(defaultServer)
    fun effectiveIdleServerBase(): String = _effectiveIdleServer.value

    fun resolveSwitchTarget(
        inviteServer: String?,
        customServer: String,
        idleServer: String = effectiveIdleServerBase(),
    ): String = inviteServer ?: customServer.ifBlank { idleServer }

    @Volatile
    private var activePartyServerBase: String? = null
    fun activePartyServerBase(): String? = activePartyServerBase

    val isUsingDefaultFallback: Boolean
        get() {
            val configured = _customServer.value
            return configured.isNotBlank() &&
                effectiveIdleServerBase() == defaultServer &&
                configured != defaultServer
        }

    val hasServer: Boolean get() = true

    enum class Health { UNKNOWN, CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(
        val health: Health = Health.UNKNOWN,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false,
    )

    private val _serverConnectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Checking)
    val serverConnectionState: StateFlow<ServerConnectionState> = _serverConnectionState.asStateFlow()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    fun isEligibleForFallback(error: Throwable): Boolean = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.PortUnreachableException,
        is java.net.SocketTimeoutException,
        is io.ktor.client.plugins.HttpRequestTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException -> true
        is PartyException -> (error.statusCode ?: 0) in 500..599
        else -> {
            val cause = error.cause
            cause != null && cause !== error && isEligibleForFallback(cause)
        }
    }

    const val CUSTOM_SERVER_TIMEOUT_MS = 6_000L
    const val DEFAULT_SERVER_TIMEOUT_MS = 10_000L

    private var isScreenActive: Boolean = false
    private var healthMonitorJob: Job? = null
    private var lastHealthProbeMs = 0L
    private val resolutionMutex = Mutex()
    private var activeResolutionJob: Job? = null
    private var resolutionGeneration = 0L

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
        if (active && !shouldSkipIdleProbe(
                screenActive = true,
                connectionLive = _state.value.connection == Connection.LIVE,
                inParty = _state.value.code != null,
                metered = runCatching { AppSettings.meteredConnection.value }.getOrNull(),
            )
        ) {
            refreshServerHealth(showChecking = false)
        }
    }

    internal fun shouldSkipIdleProbe(
        screenActive: Boolean,
        connectionLive: Boolean,
        inParty: Boolean,
        metered: Boolean?,
    ): Boolean {
        if (!screenActive) return true
        if (connectionLive) return true
        if (!inParty && metered == true) return true
        return false
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(HEALTH_POLL_INTERVAL_MS)
                val snapshot = _state.value
                if (!shouldSkipIdleProbe(
                        screenActive = isScreenActive,
                        connectionLive = snapshot.connection == Connection.LIVE,
                        inParty = snapshot.code != null,
                        metered = runCatching { AppSettings.meteredConnection.value }.getOrNull(),
                    )
                ) {
                    refreshServerHealth(showChecking = false)
                }
            }
        }
    }

    suspend fun resolveServerConnection(
        customServer: String,
        defaultServer: String,
        probeCustom: suspend () -> ProbeResult,
        probeDefault: suspend () -> ProbeResult,
    ): Pair<String, ServerConnectionState> {
        val normCustom = when (val res = parseAndNormalizeServerUrl(customServer)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> ""
        }
        val hasCustom = normCustom.isNotBlank() && normCustom != defaultServer

        return if (hasCustom) {
            val customProbe = probeCustom()
            if (customProbe.isOnline) {
                normCustom to ServerConnectionState.CustomOnline(customProbe.latencyMs)
            } else {
                val defaultProbe = probeDefault()
                if (defaultProbe.isOnline) {
                    defaultServer to ServerConnectionState.CustomFallback(defaultProbe.latencyMs)
                } else {
                    defaultServer to ServerConnectionState.Offline
                }
            }
        } else {
            val defaultProbe = probeDefault()
            if (defaultProbe.isOnline) {
                defaultServer to ServerConnectionState.DefaultOnline(defaultProbe.latencyMs)
            } else {
                defaultServer to ServerConnectionState.Offline
            }
        }
    }

    private suspend fun resolveServerConnectionSerialized(
        showChecking: Boolean = false,
        targetGeneration: Long? = null,
    ): ServerConnectionState {
        if (showChecking) {
            resolutionMutex.withLock {
                if (targetGeneration == null || targetGeneration == resolutionGeneration) {
                    _serverConnectionState.value = ServerConnectionState.Checking
                    _serverStatus.value = ServerStatus(Health.CHECKING)
                }
            }
        }

        val custom = _customServer.value
        val (effectiveServer, state) = resolveServerConnection(
            customServer = custom,
            defaultServer = defaultServer,
            probeCustom = { probeHealthWithLatency(custom, CUSTOM_SERVER_TIMEOUT_MS) },
            probeDefault = { probeHealthWithLatency(defaultServer, DEFAULT_SERVER_TIMEOUT_MS) },
        )

        resolutionMutex.withLock {
            if (targetGeneration == null || targetGeneration == resolutionGeneration) {
                _effectiveIdleServer.value = effectiveServer
                _serverConnectionState.value = state
                _serverStatus.value = ServerStatus(
                    health = state.health,
                    latencyMs = state.latencyMs ?: 0L,
                    isFallback = state.isFallback,
                )
            }
        }
        return state
    }

    fun refreshServerHealth(showChecking: Boolean = false) {
        val now = clock.nowMs()
        if (!showChecking && now - lastHealthProbeMs < HEALTH_MIN_GAP_MS) return
        lastHealthProbeMs = now
        scope.launch {
            val (job, _) = resolutionMutex.withLock {
                activeResolutionJob?.cancel()
                val nextGen = ++resolutionGeneration
                val newJob = scope.async(start = CoroutineStart.LAZY) {
                    resolveServerConnectionSerialized(showChecking = showChecking, targetGeneration = nextGen)
                }
                activeResolutionJob = newJob
                Pair(newJob, nextGen)
            }
            job.start()
        }
    }

    suspend fun probeHealthWithLatency(serverUrl: String, timeoutMs: Long): ProbeResult =
        withContext(Dispatchers.IO) {
            val endpoints = relayEndpointCandidatesFor(serverUrl)
            val timeout = timeoutMs.toInt().coerceIn(1_500, 10_000)
            for (endpoint in endpoints) {
                val rtt = MiniMqttClient.probeEndpoint(endpoint, timeout)
                if (rtt != null) {
                    return@withContext ProbeResult(isOnline = true, latencyMs = rtt)
                }
            }
            ProbeResult(isOnline = false, latencyMs = 0L)
        }

    data class HealthResolution(
        val resolvedServer: String,
        val health: Health,
        val isFallback: Boolean,
    )

    suspend fun computeHealthResolution(
        customServer: String,
        probeCustom: suspend () -> Boolean,
        probeDefault: suspend () -> Boolean,
    ): HealthResolution {
        val custom = normalizeServerBase(customServer)
        val hasCustom = custom.isNotBlank() && custom != defaultServer
        return if (hasCustom) {
            if (probeCustom()) {
                HealthResolution(resolvedServer = custom, health = Health.ONLINE, isFallback = false)
            } else if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = true)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        } else {
            if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = false)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    @Volatile
    private var mqttClient: MiniMqttClient? = null
    private var socketJob: Job? = null
    private var currentSnapshot: MqttPartyStateSnapshot = MqttPartyStateSnapshot()
    private val presenceMembers = LinkedHashMap<String, PartyMember>()
    private val seenCommandIds = LinkedHashSet<String>()
    private val seenChatIds = LinkedHashSet<String>()
    @Volatile
    private var myJoinedAtMs: Long = 0L

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawSaved = prefs.getString(KEY_SERVER, null)?.trim().orEmpty()
        val normalizedSaved = when (val res = parseAndNormalizeServerUrl(rawSaved)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> ""
        }
        _customServer.value = normalizedSaved
        _effectiveIdleServer.value = if (normalizedSaved.isNotBlank()) normalizedSaved else defaultServer
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()

        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            val snapshot = _state.value
                            if (!shouldSkipIdleProbe(
                                    screenActive = isScreenActive,
                                    connectionLive = snapshot.connection == Connection.LIVE,
                                    inParty = snapshot.code != null,
                                    metered = runCatching { AppSettings.meteredConnection.value }.getOrNull(),
                                )
                            ) {
                                refreshServerHealth(showChecking = false)
                            }
                        }

                        override fun onLost(network: Network) {
                            _serverConnectionState.value = ServerConnectionState.Offline
                            _serverStatus.value = ServerStatus(Health.OFFLINE)
                        }
                    },
                )
            }
        }

        startHealthMonitor()
        refreshServerHealth(showChecking = true)
    }

    suspend fun setCustomServerUrl(normalizedUrl: String): ServerConnectionState {
        require(parseAndNormalizeServerUrl(normalizedUrl) == ServerUrlValidationResult.Valid(normalizedUrl)) {
            "setCustomServerUrl accepts only canonical URLs produced by parseAndNormalizeServerUrl"
        }

        prefs.edit().putString(KEY_SERVER, normalizedUrl).apply()
        _customServer.value = normalizedUrl

        val (job, _) = resolutionMutex.withLock {
            activeResolutionJob?.cancel()
            val nextGen = ++resolutionGeneration
            val newJob = scope.async(start = CoroutineStart.LAZY) {
                resolveServerConnectionSerialized(showChecking = true, targetGeneration = nextGen)
            }
            activeResolutionJob = newJob
            Pair(newJob, nextGen)
        }
        job.start()
        return job.await()
    }

    /** Zero-login Musify Dengar Bersama: anyone can always create or join. */
    fun canJoin(): Boolean = true

    fun nickname(): String =
        if (::prefs.isInitialized) prefs.getString(KEY_NICKNAME, null).orEmpty() else ""

    fun setNickname(value: String) {
        if (!::prefs.isInitialized) return
        val filtered = SafeTextFilter.maskUnsafeText(value.trim().take(80))
        prefs.edit().putString(KEY_NICKNAME, filtered).apply()
    }

    fun ensureConnected() {
        val code = _state.value.code ?: return
        if (socketJob?.isActive == true && mqttClient?.isConnected == true) return
        startMqttSession(code, activePartyServerBase ?: effectiveIdleServerBase(), creating = false)
    }

    // ------------------------------------------------------------ joining --

    suspend fun createParty(nickname: String = nickname(), maxMembers: Int = 5): Result<String> = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val primary = effectiveIdleServerBase()
                val who = identity(nickname)
                val code = generateRoomCode()
                val myId = who.memberId
                val now = System.currentTimeMillis()
                myJoinedAtMs = now
                val me = PartyMember(
                    memberId = myId,
                    userId = who.userId,
                    displayName = who.name,
                    avatarUrl = who.avatar,
                    isHost = true,
                    connected = true,
                    joinedAtMs = now,
                    lastSeenMs = now,
                )
                val clampedMax = maxMembers.coerceIn(2, 10)
                val initialSnap = MqttPartyStateSnapshot(
                    code = code,
                    hostMemberId = myId,
                    createdAtMs = now,
                    maxMembers = clampedMax,
                    hostOnlyControl = false,
                    playback = PartyPlayback(
                        seq = 1L,
                        queueSeq = 1L,
                        autoplayEnabled = runCatching { AppSettings.autoplay.value }.getOrDefault(false),
                        updatedAtMs = now,
                    ),
                    queue = PartyQueue(seq = 1L),
                )

                val connectedClient = connectFirstHealthyRelay(
                    serverUrl = primary,
                    clientId = mqttClientId(code, myId),
                    willTopic = presenceTopic(code, myId),
                    willPayload = json.encodeToString(PartyMember.serializer(), me.copy(connected = false)),
                )

                withContext(NonCancellable) {
                    closeSessionLocked()
                    activePartyServerBase = if (
                        connectedClient.host == "broker.emqx.io" &&
                        normalizeServerBase(primary) == defaultServer
                    ) {
                        defaultServer
                    } else {
                        "https://${connectedClient.host}:${connectedClient.port}"
                    }
                    synchronized(stateMutex) {
                        currentSnapshot = initialSnap
                        presenceMembers.clear()
                        presenceMembers[myId] = me
                        seenCommandIds.clear()
                        seenChatIds.clear()
                    }
                    _chatMessages.value = emptyList()
                    _voteQueue.value = emptyList()
                    _activity.value = emptyList()
                    val monoNow = clock.nowMs()
                    clock.reset()
                    clock.record(monoNow, now, monoNow)
                    _state.value = State(
                        code = code,
                        you = me,
                        members = listOf(me),
                        maxMembers = clampedMax,
                        hostOnlyControl = false,
                        playback = initialSnap.playback,
                        queue = initialSnap.queue,
                        connection = Connection.LIVE,
                        clockSynced = true,
                        roundTripMs = 0L,
                    )
                    mqttClient = connectedClient
                    attachClientCallbacks(connectedClient, code, me)
                    connectedClient.subscribe(roomWildcard(code))
                    connectedClient.publish(presenceTopic(code, myId), json.encodeToString(PartyMember.serializer(), me), retain = true)
                    connectedClient.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), initialSnap), retain = true)
                    recordActivity(PartyActivity("created", me.displayName, now, "Ruang $code dibuat"))
                }
                startMqttLoop(code, me)
                code
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { failure ->
                    val uiMessage = failure.displayMessage()
                    Log.w(TAG, "createParty failed: ${redact(failure.message)}")
                    _state.update { it.copy(error = uiMessage) }
                    Result.failure(failure as? PartyException ?: PartyException("unreachable", uiMessage))
                },
            )
        }
    }

    suspend fun joinParty(code: String, nickname: String = nickname()): Result<String> = switchMutex.withLock {
        val targetServer = effectiveIdleServerBase()
        runCatching {
            doJoinOnBroker(targetServer, code, nickname)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { failure ->
                val uiMessage = failure.displayMessage()
                Log.w(TAG, "could not enter a party: ${redact(failure.message)}")
                _state.update { it.copy(error = uiMessage) }
                Result.failure(failure as? PartyException ?: PartyException("unreachable", uiMessage))
            },
        )
    }

    private suspend fun doJoinOnBroker(serverBase: String, code: String, nickname: String): String =
        withContext(Dispatchers.IO) {
            val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
            if (cleaned.length != CODE_LENGTH) {
                throw PartyException("bad_code", "A party code is six letters or digits.")
            }
            refuseIfRecentlyKicked(cleaned)

            val previewSnap = fetchRoomSnapshot(cleaned, serverBase)
                ?: throw PartyException("party_not_found", "Tidak ada ruang dengan kode $cleaned.")

            val snapshot = previewSnap.snapshot
            val existingMembers = previewSnap.members
            val who = identity(nickname)
            if (who.memberId in snapshot.kickedMemberIds) {
                recordKick(cleaned)
                throw PartyException("recently_kicked", "Couldn’t let you in — you’ve recently been kicked out of this party.")
            }
            val connectedCount = existingMembers.count { it.connected && it.memberId != who.memberId }
            if (connectedCount >= snapshot.maxMembers) {
                throw PartyException("party_full", "Party is full (${snapshot.maxMembers} listeners).")
            }

            val now = System.currentTimeMillis()
            myJoinedAtMs = now
            val isHost = snapshot.hostMemberId == who.memberId || existingMembers.none { it.connected && it.isHost }
            val me = PartyMember(
                memberId = who.memberId,
                userId = who.userId,
                displayName = who.name,
                avatarUrl = who.avatar,
                isHost = isHost,
                connected = true,
                joinedAtMs = now,
                lastSeenMs = now,
            )

            val client = connectFirstHealthyRelay(
                serverUrl = previewSnap.clusterUrl,
                clientId = mqttClientId(cleaned, who.memberId),
                willTopic = presenceTopic(cleaned, who.memberId),
                willPayload = json.encodeToString(PartyMember.serializer(), me.copy(connected = false)),
            )

            withContext(NonCancellable) {
                closeSessionLocked()
                activePartyServerBase = resolveHttpBase(previewSnap.clusterUrl)
                synchronized(stateMutex) {
                    currentSnapshot = if (isHost) snapshot.copy(hostMemberId = who.memberId) else snapshot
                    presenceMembers.clear()
                    existingMembers.forEach { presenceMembers[it.memberId] = it }
                    presenceMembers[me.memberId] = me
                    seenCommandIds.clear()
                    seenChatIds.clear()
                }
                _chatMessages.value = emptyList()
                _voteQueue.value = currentSnapshot.voteQueue
                _activity.value = emptyList()
                val monoNow = clock.nowMs()
                clock.reset()
                if (isHost) {
                    clock.record(monoNow, now, monoNow)
                }
                val mergedMembers = mergedMemberListLocked(me.memberId, currentSnapshot.hostMemberId)
                _state.value = State(
                    code = cleaned,
                    you = me,
                    members = mergedMembers,
                    maxMembers = currentSnapshot.maxMembers,
                    hostOnlyControl = currentSnapshot.hostOnlyControl,
                    playback = currentSnapshot.playback,
                    queue = currentSnapshot.queue,
                    connection = Connection.LIVE,
                    clockSynced = isHost,
                    roundTripMs = 0L,
                )
                mqttClient = client
                attachClientCallbacks(client, cleaned, me)
                client.subscribe(roomWildcard(cleaned))
                client.publish(presenceTopic(cleaned, me.memberId), json.encodeToString(PartyMember.serializer(), me), retain = true)
                if (isHost) {
                    client.publish(stateTopic(cleaned), json.encodeToString(MqttPartyStateSnapshot.serializer(), currentSnapshot), retain = true)
                } else {
                    sendCommand(MqttPartyCommand(action = "clock_ping", senderId = me.memberId, sentAtMonoMs = clock.nowMs()))
                }
                recordActivity(PartyActivity("joined", me.displayName, now, "Bergabung ke ruang $cleaned"))
            }
            startMqttLoop(cleaned, me)
            cleaned
        }

    suspend fun switchPartyWithRecovery(
        targetCustomServer: String,
        targetCode: String,
        nickname: String = nickname(),
    ): SwitchPartyResult = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val normTarget = when (val res = parseAndNormalizeServerUrl(targetCustomServer)) {
                is ServerUrlValidationResult.Valid -> res.normalizedUrl
                is ServerUrlValidationResult.Invalid -> return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "Target server address is invalid.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("Target server address is invalid.")
                }
            }
            val oldCode = _state.value.code
            val attempt = runCatching {
                doJoinOnBroker(normTarget.ifBlank { defaultServer }, targetCode, nickname)
            }
            val joined = attempt.getOrNull()
            if (joined != null) {
                if (normTarget.isNotBlank()) {
                    _customServer.value = normTarget
                    if (::prefs.isInitialized) prefs.edit().putString(KEY_SERVER, normTarget).apply()
                }
                SwitchPartyResult.Success(joined)
            } else {
                val msg = attempt.exceptionOrNull()?.displayMessage() ?: UNREACHABLE
                if (oldCode != null) {
                    SwitchPartyResult.TargetFailedRecovered(oldCode, msg)
                } else {
                    SwitchPartyResult.TargetFailedNoParty(msg)
                }
            }
        }
    }

    suspend fun leaveParty() = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val code = _state.value.code
            val you = _state.value.you
            val client = mqttClient
            if (code != null && you != null && client != null && client.isConnected) {
                runCatching {
                    client.publish(
                        presenceTopic(code, you.memberId),
                        json.encodeToString(PartyMember.serializer(), you.copy(connected = false, lastSeenMs = System.currentTimeMillis())),
                        retain = true,
                    )
                    if (you.isHost) {
                        val nextHost = synchronized(stateMutex) {
                            presenceMembers.values
                                .filter { it.connected && it.memberId != you.memberId }
                                .minWithOrNull(compareBy<PartyMember> { it.joinedAtMs }.thenBy { it.memberId })
                        }
                        if (nextHost != null) {
                            val updated = currentSnapshot.copy(hostMemberId = nextHost.memberId)
                            client.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), updated), retain = true)
                        } else {
                            // Empty room: clear retained state cleanly.
                            client.publish(stateTopic(code), "", retain = true)
                        }
                    }
                }
            }
            closeSessionLocked()
            clock.reset()
            activePartyServerBase = null
            _chatMessages.value = emptyList()
            _voteQueue.value = emptyList()
            _activity.value = emptyList()
            _state.value = State()
        }
    }

    // ------------------------------------------------------------ controls --

    fun play(positionMs: Long? = null) = dispatchControl(action = "play", positionMs = positionMs ?: 0L, hasPosition = positionMs != null)

    fun pause(positionMs: Long? = null) = dispatchControl(action = "pause", positionMs = positionMs ?: 0L, hasPosition = positionMs != null)

    fun seek(positionMs: Long) = dispatchControl(action = "seek", positionMs = positionMs, hasPosition = true)

    fun next() = dispatchControl(action = "next")

    fun previous() = dispatchControl(action = "previous")

    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        dispatchControl(action = "setTrack", track = track, positionMs = positionMs, isPlaying = isPlaying, hasPosition = true)

    fun setQueue(queue: List<PartyTrack>, index: Int) =
        dispatchControl(action = "setQueue", tracks = queue, queueIndex = index)

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) =
        dispatchControl(action = "queueAdd", tracks = tracks, playNext = playNext)

    fun queueRemove(videoId: String) =
        dispatchControl(action = "queueRemove", videoId = videoId)

    fun queueClear() =
        dispatchControl(action = "queueClear")

    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String? = null) =
        dispatchControl(action = "queueMove", fromIndex = fromIndex, toIndex = toIndex, videoId = videoId)

    fun setMaxMembers(value: Int) =
        dispatchControl(action = "setMaxMembers", maxMembers = value)

    fun kick(memberId: String) =
        dispatchControl(action = "kick", targetMemberId = memberId)

    fun setAutoplay(enabled: Boolean) =
        dispatchControl(action = "setAutoplay", enabled = enabled)

    fun setHostOnlyControl(enabled: Boolean) =
        dispatchControl(action = "setHostOnlyControl", enabled = enabled)

    // -------------------- Musify Dengar Bersama Chat & Antrean Voting -------

    fun sendChatMessage(rawText: String): Boolean {
        val st = _state.value
        val code = st.code ?: return false
        val you = st.you ?: return false
        val filtered = SafeTextFilter.maskUnsafeText(rawText)
        if (filtered.isBlank()) return false
        val msg = PartyChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = you.memberId,
            senderName = SafeTextFilter.maskUnsafeText(you.displayName).ifBlank { "Pendengar" },
            senderAvatar = you.avatarUrl,
            text = filtered,
            timestampMs = System.currentTimeMillis(),
            isHost = you.isHost,
        )
        appendChat(msg)
        scope.launch {
            runCatching {
                mqttClient?.publish(chatTopic(code), json.encodeToString(PartyChatMessage.serializer(), msg), retain = false)
            }
        }
        return true
    }

    fun proposeVoteTrack(song: Song) {
        val durationMs = TrackMatcher.secondsOf(song.durationText)?.let { it * 1000L }
        proposeVotePartyTrack(
            PartyTrack(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationMs = durationMs,
            ),
        )
    }

    fun proposeVotePartyTrack(track: PartyTrack) {
        val code = _state.value.code ?: return
        val you = _state.value.you ?: return
        val cmd = MqttPartyCommand(
            id = UUID.randomUUID().toString(),
            senderId = you.memberId,
            senderName = you.displayName,
            action = "vote_propose",
            track = track,
        )
        applyVoteCommandLocal(cmd)
        publishVoteOrHostSync(code, cmd)
    }

    fun toggleVote(videoId: String) {
        val code = _state.value.code ?: return
        val you = _state.value.you ?: return
        val cmd = MqttPartyCommand(
            id = UUID.randomUUID().toString(),
            senderId = you.memberId,
            senderName = you.displayName,
            action = "vote_toggle",
            videoId = videoId,
        )
        applyVoteCommandLocal(cmd)
        publishVoteOrHostSync(code, cmd)
    }

    fun removeVoteProposal(videoId: String) {
        val code = _state.value.code ?: return
        val you = _state.value.you ?: return
        val cmd = MqttPartyCommand(
            id = UUID.randomUUID().toString(),
            senderId = you.memberId,
            senderName = you.displayName,
            action = "vote_remove",
            videoId = videoId,
        )
        applyVoteCommandLocal(cmd)
        publishVoteOrHostSync(code, cmd)
    }

    fun playTopVotedTrack(): Boolean {
        val st = _state.value
        if (!st.inParty) return false
        val top = _voteQueue.value.firstOrNull() ?: return false
        if (st.controlsLocked) return false
        removeVoteProposal(top.track.videoId)
        val newQueue = listOf(top.track) + st.queue.items.filterNot { it.videoId == top.track.videoId }
        setQueue(newQueue, 0)
        setTrack(top.track, positionMs = 0L, isPlaying = true)
        return true
    }

    private fun publishVoteOrHostSync(code: String, cmd: MqttPartyCommand) {
        scope.launch {
            runCatching {
                mqttClient?.publish(voteTopic(code), json.encodeToString(MqttPartyCommand.serializer(), cmd), retain = false)
                if (_state.value.you?.isHost == true) {
                    val snap = synchronized(stateMutex) {
                        currentSnapshot = currentSnapshot.copy(voteQueue = _voteQueue.value)
                        currentSnapshot
                    }
                    mqttClient?.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), snap), retain = true)
                }
            }
        }
    }

    private fun dispatchControl(
        action: String,
        track: PartyTrack? = null,
        tracks: List<PartyTrack> = emptyList(),
        queueIndex: Int = -1,
        positionMs: Long = 0L,
        isPlaying: Boolean? = null,
        playNext: Boolean = false,
        videoId: String? = null,
        fromIndex: Int = -1,
        toIndex: Int = -1,
        maxMembers: Int? = null,
        targetMemberId: String? = null,
        enabled: Boolean? = null,
        hasPosition: Boolean = false,
    ) {
        val st = _state.value
        val code = st.code ?: return
        val you = st.you ?: return
        val cmd = MqttPartyCommand(
            id = UUID.randomUUID().toString(),
            senderId = you.memberId,
            senderName = you.displayName,
            action = action,
            track = track,
            tracks = tracks,
            queueIndex = queueIndex,
            positionMs = if (hasPosition) positionMs else (partyPositionMs() ?: st.playback.positionMs),
            isPlaying = isPlaying,
            playNext = playNext,
            videoId = videoId,
            fromIndex = fromIndex,
            toIndex = toIndex,
            maxMembers = maxMembers,
            targetMemberId = targetMemberId,
            enabled = enabled,
        )

        if (you.isHost || (!st.controlsLocked && action !in HOST_ONLY_ACTIONS)) {
            if (you.isHost) {
                applyAndPublishHostCommand(code, cmd)
            } else {
                sendCommand(cmd)
            }
        }
    }

    private fun sendCommand(cmd: MqttPartyCommand) {
        val code = _state.value.code ?: return
        scope.launch {
            runCatching {
                mqttClient?.publish(cmdTopic(code), json.encodeToString(MqttPartyCommand.serializer(), cmd), retain = false)
            }.onFailure { Log.w(TAG, "control not sent: ${redact(it.message)}") }
        }
    }

    private val _awaitingStart = MutableStateFlow(false)
    val awaitingStart: StateFlow<Boolean> = _awaitingStart.asStateFlow()

    fun setAwaitingStart(value: Boolean) {
        _awaitingStart.value = value
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        val track = playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val elapsed = (serverNow - playback.anchorMs).coerceAtLeast(0)
        val position = playback.positionMs + elapsed
        val duration = track.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    // -------------------------------------------------------- MQTT loop ---

    private fun startMqttSession(code: String, serverBase: String, creating: Boolean) {
        if (creating) return
        socketJob?.cancel()
        socketJob = scope.launch {
            val who = identity()
            val me = PartyMember(
                memberId = who.memberId,
                userId = who.userId,
                displayName = who.name,
                avatarUrl = who.avatar,
                isHost = _state.value.you?.isHost == true,
                connected = true,
                joinedAtMs = myJoinedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                lastSeenMs = System.currentTimeMillis(),
            )
            runCatching {
                val client = connectFirstHealthyRelay(
                    serverUrl = serverBase,
                    clientId = mqttClientId(code, me.memberId),
                    willTopic = presenceTopic(code, me.memberId),
                    willPayload = json.encodeToString(PartyMember.serializer(), me.copy(connected = false)),
                )
                mqttClient = client
                attachClientCallbacks(client, code, me)
                client.subscribe(roomWildcard(code))
                client.publish(presenceTopic(code, me.memberId), json.encodeToString(PartyMember.serializer(), me), retain = true)
                _state.update { it.copy(connection = Connection.LIVE, error = null) }
            }
            startMqttLoop(code, me)
        }
    }

    private fun startMqttLoop(code: String, me: PartyMember) {
        socketJob?.cancel()
        socketJob = scope.launch {
            var backoffMs = 1_500L
            while (currentCoroutineContext().isActive) {
                if (_state.value.code != code) return@launch
                val client = mqttClient
                if (client == null || !client.isConnected) {
                    _state.update { it.copy(connection = Connection.CONNECTING) }
                    val server = activePartyServerBase ?: effectiveIdleServerBase()
                    runCatching {
                        val nextClient = connectFirstHealthyRelay(
                            serverUrl = server,
                            clientId = mqttClientId(code, me.memberId),
                            willTopic = presenceTopic(code, me.memberId),
                            willPayload = json.encodeToString(PartyMember.serializer(), me.copy(connected = false)),
                        )
                        mqttClient = nextClient
                        attachClientCallbacks(nextClient, code, me)
                        nextClient.subscribe(roomWildcard(code))
                        nextClient.publish(presenceTopic(code, me.memberId), json.encodeToString(PartyMember.serializer(), me.copy(lastSeenMs = System.currentTimeMillis())), retain = true)
                        _state.update { it.copy(connection = Connection.LIVE, error = null) }
                        backoffMs = 1_500L
                    }.onFailure {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(20_000L)
                        continue
                    }
                } else {
                    val liveState = _state.value
                    if (liveState.you?.isHost == true) {
                        val now = System.currentTimeMillis()
                        val posNow = partyPositionMs() ?: liveState.playback.positionMs
                        val updatedSnap = synchronized(stateMutex) {
                            currentSnapshot = currentSnapshot.copy(
                                playback = currentSnapshot.playback.copy(effectivePositionMs = posNow, updatedAtMs = now),
                                voteQueue = _voteQueue.value,
                            )
                            currentSnapshot
                        }
                        runCatching {
                            client.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), updatedSnap), retain = true)
                        }
                    } else {
                        runCatching {
                            client.publish(
                                cmdTopic(code),
                                json.encodeToString(
                                    MqttPartyCommand.serializer(),
                                    MqttPartyCommand(action = "clock_ping", senderId = me.memberId, sentAtMonoMs = clock.nowMs()),
                                ),
                                retain = false,
                            )
                        }
                    }
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
        }
    }

    private fun attachClientCallbacks(client: MiniMqttClient, code: String, me: PartyMember) {
        client.onClosed = {
            if (_state.value.code == code && mqttClient === client) {
                _state.update { it.copy(connection = Connection.CONNECTING) }
            }
        }
        client.onMessage = { topic, payload, _ ->
            handleMqttMessage(code, me.memberId, topic, payload)
        }
    }

    private fun handleMqttMessage(code: String, myMemberId: String, topic: String, payload: String) {
        if (_state.value.code != code) return
        val base = topicPrefix(code)
        when {
            topic == "$base/state" -> {
                if (payload.isBlank()) return
                val snap = runCatching { json.decodeFromString(MqttPartyStateSnapshot.serializer(), payload) }.getOrNull() ?: return
                if (myMemberId in snap.kickedMemberIds) {
                    recordKick(code)
                    scope.launch { leaveParty() }
                    return
                }
                val acceptPlayback: Boolean
                val mergedMembers: List<PartyMember>
                val youUpdated: PartyMember?
                synchronized(stateMutex) {
                    acceptPlayback = snap.playback.seq >= currentSnapshot.playback.seq
                    val chosenPlayback = if (acceptPlayback) snap.playback else currentSnapshot.playback
                    val chosenQueue = if (snap.queue.seq >= currentSnapshot.queue.seq) snap.queue else currentSnapshot.queue
                    currentSnapshot = snap.copy(playback = chosenPlayback, queue = chosenQueue)
                    mergedMembers = mergedMemberListLocked(myMemberId, snap.hostMemberId)
                    youUpdated = mergedMembers.firstOrNull { it.memberId == myMemberId }
                }
                if (snap.voteQueue.isNotEmpty() || _voteQueue.value.isNotEmpty()) {
                    _voteQueue.value = snap.voteQueue.sortedWith(compareByDescending<PartyVoteProposal> { it.votes.size }.thenBy { it.addedAtMs })
                }
                _state.update { cur ->
                    cur.copy(
                        you = youUpdated ?: cur.you?.copy(isHost = snap.hostMemberId == myMemberId),
                        members = mergedMembers,
                        maxMembers = snap.maxMembers,
                        hostOnlyControl = snap.hostOnlyControl,
                        playback = currentSnapshot.playback,
                        queue = currentSnapshot.queue,
                        connection = Connection.LIVE,
                    )
                }
            }

            topic.startsWith("$base/presence/") -> {
                val member = runCatching { json.decodeFromString(PartyMember.serializer(), payload) }.getOrNull() ?: return
                val mergedMembers: List<PartyMember>
                val promotedSelf: Boolean
                synchronized(stateMutex) {
                    presenceMembers[member.memberId] = member
                    val hostStillConnected = presenceMembers[currentSnapshot.hostMemberId]?.connected == true
                    if (!hostStillConnected) {
                        val nextHost = presenceMembers.values
                            .filter { it.connected }
                            .minWithOrNull(compareBy<PartyMember> { it.joinedAtMs }.thenBy { it.memberId })
                        if (nextHost != null && nextHost.memberId != currentSnapshot.hostMemberId) {
                            currentSnapshot = currentSnapshot.copy(hostMemberId = nextHost.memberId)
                        }
                    }
                    promotedSelf = currentSnapshot.hostMemberId == myMemberId
                    mergedMembers = mergedMemberListLocked(myMemberId, currentSnapshot.hostMemberId)
                }
                _state.update { cur ->
                    cur.copy(
                        members = mergedMembers,
                        you = mergedMembers.firstOrNull { it.memberId == myMemberId } ?: cur.you?.copy(isHost = promotedSelf),
                    )
                }
                if (promotedSelf) {
                    val monoNow = clock.nowMs()
                    clock.reset()
                    clock.record(monoNow, System.currentTimeMillis(), monoNow)
                    _state.update { it.copy(clockSynced = true, roundTripMs = 0L) }
                    scope.launch {
                        runCatching {
                            mqttClient?.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), currentSnapshot), retain = true)
                        }
                    }
                }
            }

            topic == "$base/cmd" -> {
                val cmd = runCatching { json.decodeFromString(MqttPartyCommand.serializer(), payload) }.getOrNull() ?: return
                when (cmd.action) {
                    "clock_ping" -> {
                        if (_state.value.you?.isHost == true && cmd.senderId != myMemberId) {
                            val pong = MqttPartyCommand(
                                action = "clock_pong",
                                senderId = myMemberId,
                                targetMemberId = cmd.senderId,
                                sentAtMonoMs = cmd.sentAtMonoMs,
                                hostWallClockMs = System.currentTimeMillis(),
                            )
                            scope.launch {
                                runCatching { mqttClient?.publish(cmdTopic(code), json.encodeToString(MqttPartyCommand.serializer(), pong), retain = false) }
                            }
                        }
                    }
                    "clock_pong" -> {
                        if (cmd.targetMemberId == myMemberId && cmd.sentAtMonoMs > 0L && cmd.hostWallClockMs > 0L) {
                            val recv = clock.nowMs()
                            clock.record(cmd.sentAtMonoMs, cmd.hostWallClockMs, recv)
                            _state.update { it.copy(clockSynced = clock.synced, roundTripMs = clock.roundTripMs) }
                        }
                    }
                    else -> {
                        if (_state.value.you?.isHost == true) {
                            applyAndPublishHostCommand(code, cmd)
                        }
                    }
                }
            }

            topic == "$base/chat" -> {
                val msg = runCatching { json.decodeFromString(PartyChatMessage.serializer(), payload) }.getOrNull() ?: return
                val cleanMsg = msg.copy(
                    senderName = SafeTextFilter.maskUnsafeText(msg.senderName).ifBlank { "Pendengar" },
                    text = SafeTextFilter.maskUnsafeText(msg.text),
                )
                if (cleanMsg.text.isNotBlank()) appendChat(cleanMsg)
            }

            topic == "$base/vote" -> {
                val cmd = runCatching { json.decodeFromString(MqttPartyCommand.serializer(), payload) }.getOrNull() ?: return
                applyVoteCommandLocal(cmd)
                if (_state.value.you?.isHost == true) {
                    scope.launch {
                        val snap = synchronized(stateMutex) {
                            currentSnapshot = currentSnapshot.copy(voteQueue = _voteQueue.value)
                            currentSnapshot
                        }
                        runCatching { mqttClient?.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), snap), retain = true) }
                    }
                }
            }
        }
    }

    private fun applyVoteCommandLocal(cmd: MqttPartyCommand) {
        synchronized(stateMutex) {
            if (cmd.id.isNotBlank() && !seenCommandIds.add(cmd.id)) return
            if (seenCommandIds.size > 400) {
                seenCommandIds.firstOrNull()?.let { seenCommandIds.remove(it) }
            }
            val current = _voteQueue.value.toMutableList()
            when (cmd.action) {
                "vote_propose" -> {
                    val tr = cmd.track ?: return
                    val idx = current.indexOfFirst { it.track.videoId == tr.videoId }
                    if (idx >= 0) {
                        val existing = current[idx]
                        if (cmd.senderId !in existing.votes) {
                            current[idx] = existing.copy(votes = existing.votes + cmd.senderId)
                        }
                    } else {
                        current.add(
                            PartyVoteProposal(
                                track = tr,
                                proposedById = cmd.senderId,
                                proposedByName = cmd.senderName.ifBlank { "Pendengar" },
                                votes = listOf(cmd.senderId),
                                addedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                "vote_toggle" -> {
                    val vid = cmd.videoId ?: return
                    val idx = current.indexOfFirst { it.track.videoId == vid }
                    if (idx >= 0) {
                        val item = current[idx]
                        val nextVotes = if (cmd.senderId in item.votes) {
                            item.votes - cmd.senderId
                        } else {
                            item.votes + cmd.senderId
                        }
                        if (nextVotes.isEmpty()) current.removeAt(idx)
                        else current[idx] = item.copy(votes = nextVotes)
                    }
                }
                "vote_remove" -> {
                    val vid = cmd.videoId ?: return
                    current.removeAll { it.track.videoId == vid }
                }
            }
            val sorted = current.sortedWith(compareByDescending<PartyVoteProposal> { it.votes.size }.thenBy { it.addedAtMs })
            _voteQueue.value = sorted
            currentSnapshot = currentSnapshot.copy(voteQueue = sorted)
        }
    }

    private fun applyAndPublishHostCommand(code: String, cmd: MqttPartyCommand) {
        val now = System.currentTimeMillis()
        val snapToPublish: MqttPartyStateSnapshot?
        var activityEntry: PartyActivity? = null

        synchronized(stateMutex) {
            if (cmd.id.isNotBlank() && !seenCommandIds.add(cmd.id)) return
            if (seenCommandIds.size > 400) {
                seenCommandIds.firstOrNull()?.let { seenCommandIds.remove(it) }
            }
            val cur = currentSnapshot
            if (cur.hostOnlyControl && cmd.senderId != cur.hostMemberId) return
            if (cmd.action in HOST_ONLY_ACTIONS && cmd.senderId != cur.hostMemberId) return

            val pb = cur.playback
            val q = cur.queue
            val nextSeq = pb.seq + 1L
            var newPb = pb
            var newQ = q
            var newMax = cur.maxMembers
            var newHostOnly = cur.hostOnlyControl
            var newKicked = cur.kickedMemberIds

            when (cmd.action) {
                "play" -> {
                    val pos = if (cmd.positionMs >= 0L) cmd.positionMs else pb.positionMs
                    newPb = pb.copy(
                        seq = nextSeq,
                        isPlaying = true,
                        positionMs = pos,
                        anchorMs = now + RESUME_LEAD_MS,
                        effectivePositionMs = pos,
                        updatedBy = cmd.senderName,
                        updatedAtMs = now,
                    )
                    activityEntry = PartyActivity("played", cmd.senderName, now, pb.track?.title.orEmpty())
                }
                "pause" -> {
                    val pos = if (cmd.positionMs >= 0L) cmd.positionMs else (partyPositionMs() ?: pb.positionMs)
                    newPb = pb.copy(
                        seq = nextSeq,
                        isPlaying = false,
                        positionMs = pos,
                        anchorMs = now,
                        effectivePositionMs = pos,
                        updatedBy = cmd.senderName,
                        updatedAtMs = now,
                    )
                    activityEntry = PartyActivity("paused", cmd.senderName, now, pb.track?.title.orEmpty())
                }
                "seek" -> {
                    val pos = cmd.positionMs.coerceAtLeast(0L)
                    newPb = pb.copy(
                        seq = nextSeq,
                        positionMs = pos,
                        anchorMs = now,
                        effectivePositionMs = pos,
                        updatedBy = cmd.senderName,
                        updatedAtMs = now,
                    )
                    activityEntry = PartyActivity("seeked", cmd.senderName, now)
                }
                "setTrack" -> {
                    val tr = cmd.track ?: return
                    val items = q.items.toMutableList()
                    var idx = items.indexOfFirst { it.videoId == tr.videoId }
                    var qSeq = q.seq
                    if (idx < 0) {
                        items.add(0, tr)
                        idx = 0
                        qSeq += 1L
                    }
                    newQ = q.copy(seq = qSeq, index = idx, items = items)
                    val playing = cmd.isPlaying ?: true
                    val pos = cmd.positionMs.coerceAtLeast(0L)
                    newPb = pb.copy(
                        seq = nextSeq,
                        track = tr,
                        queueSeq = qSeq,
                        queueLength = items.size,
                        queueIndex = idx,
                        isPlaying = playing,
                        positionMs = pos,
                        anchorMs = if (playing) now + RESUME_LEAD_MS else now,
                        effectivePositionMs = pos,
                        updatedBy = cmd.senderName,
                        startedBy = cmd.senderId,
                        startedByName = cmd.senderName,
                        updatedAtMs = now,
                    )
                    activityEntry = PartyActivity("played", cmd.senderName, now, tr.title)
                }
                "next", "previous" -> {
                    if (q.items.isEmpty()) return
                    val curIdx = partyQueueIndexOf(q, pb, pb.track?.videoId)
                    val nextIdx = if (cmd.action == "next") {
                        (curIdx + 1).coerceAtMost(q.items.lastIndex)
                    } else {
                        (curIdx - 1).coerceAtLeast(0)
                    }
                    val target = q.items.getOrNull(nextIdx) ?: return
                    val qSeq = q.seq + 1L
                    newQ = q.copy(seq = qSeq, index = nextIdx)
                    newPb = pb.copy(
                        seq = nextSeq,
                        track = target,
                        queueSeq = qSeq,
                        queueLength = q.items.size,
                        queueIndex = nextIdx,
                        isPlaying = true,
                        positionMs = 0L,
                        anchorMs = now + RESUME_LEAD_MS,
                        effectivePositionMs = 0L,
                        updatedBy = cmd.senderName,
                        startedBy = cmd.senderId,
                        startedByName = cmd.senderName,
                        updatedAtMs = now,
                    )
                    activityEntry = PartyActivity(if (cmd.action == "next") "skipped" else "went_back", cmd.senderName, now, target.title)
                }
                "setQueue" -> {
                    val qSeq = q.seq + 1L
                    val items = cmd.tracks
                    val idx = if (items.isEmpty()) -1 else cmd.queueIndex.coerceIn(0, items.lastIndex)
                    val track = items.getOrNull(idx) ?: pb.track
                    newQ = PartyQueue(seq = qSeq, index = idx, items = items)
                    newPb = pb.copy(
                        seq = nextSeq,
                        track = track,
                        queueSeq = qSeq,
                        queueLength = items.size,
                        queueIndex = idx,
                        updatedBy = cmd.senderName,
                        updatedAtMs = now,
                    )
                }
                "queueAdd" -> {
                    if (cmd.tracks.isEmpty()) return
                    val items = q.items.toMutableList()
                    val existingIds = items.mapTo(HashSet()) { it.videoId }
                    val addable = cmd.tracks.filter { it.videoId !in existingIds }
                    if (addable.isEmpty()) return
                    val curIdx = partyQueueIndexOf(q, pb, pb.track?.videoId)
                    if (cmd.playNext && curIdx in items.indices) {
                        items.addAll(curIdx + 1, addable)
                    } else {
                        items.addAll(addable)
                    }
                    val qSeq = q.seq + 1L
                    newQ = q.copy(seq = qSeq, items = items, index = curIdx)
                    newPb = pb.copy(seq = nextSeq, queueSeq = qSeq, queueLength = items.size, queueIndex = curIdx, updatedBy = cmd.senderName, updatedAtMs = now)
                    activityEntry = PartyActivity("added", cmd.senderName, now, addable.first().title)
                }
                "queueRemove" -> {
                    val vid = cmd.videoId ?: return
                    val items = q.items.filterNot { it.videoId == vid }
                    val qSeq = q.seq + 1L
                    val idx = partyQueueIndexOf(q.copy(items = items), pb, pb.track?.videoId)
                    newQ = PartyQueue(seq = qSeq, index = idx, items = items)
                    newPb = pb.copy(seq = nextSeq, queueSeq = qSeq, queueLength = items.size, queueIndex = idx, updatedBy = cmd.senderName, updatedAtMs = now)
                }
                "queueClear" -> {
                    val currentTrack = pb.track
                    val items = if (currentTrack != null) listOf(currentTrack) else emptyList()
                    val qSeq = q.seq + 1L
                    val idx = if (items.isNotEmpty()) 0 else -1
                    newQ = PartyQueue(seq = qSeq, index = idx, items = items)
                    newPb = pb.copy(seq = nextSeq, queueSeq = qSeq, queueLength = items.size, queueIndex = idx, updatedBy = cmd.senderName, updatedAtMs = now)
                }
                "queueMove" -> {
                    val items = q.items.toMutableList()
                    if (cmd.fromIndex !in items.indices || cmd.toIndex !in items.indices) return
                    val moved = items.removeAt(cmd.fromIndex)
                    items.add(cmd.toIndex, moved)
                    val qSeq = q.seq + 1L
                    val idx = partyQueueIndexOf(q.copy(items = items), pb, pb.track?.videoId)
                    newQ = PartyQueue(seq = qSeq, index = idx, items = items)
                    newPb = pb.copy(seq = nextSeq, queueSeq = qSeq, queueLength = items.size, queueIndex = idx, updatedBy = cmd.senderName, updatedAtMs = now)
                }
                "setMaxMembers" -> {
                    newMax = (cmd.maxMembers ?: cur.maxMembers).coerceIn(2, 10)
                }
                "setHostOnlyControl" -> {
                    newHostOnly = cmd.enabled ?: cur.hostOnlyControl
                }
                "setAutoplay" -> {
                    newPb = pb.copy(seq = nextSeq, autoplayEnabled = cmd.enabled ?: pb.autoplayEnabled, updatedAtMs = now)
                }
                "kick" -> {
                    val victim = cmd.targetMemberId ?: return
                    if (victim != cur.hostMemberId && victim !in newKicked) {
                        newKicked = newKicked + victim
                        presenceMembers.remove(victim)
                    }
                }
                else -> return
            }

            snapToPublish = cur.copy(
                maxMembers = newMax,
                hostOnlyControl = newHostOnly,
                playback = newPb,
                queue = newQ,
                kickedMemberIds = newKicked,
                voteQueue = _voteQueue.value,
            )
            currentSnapshot = snapToPublish
        }

        if (snapToPublish != null) {
            val myId = _state.value.you?.memberId.orEmpty()
            val mergedMembers = synchronized(stateMutex) { mergedMemberListLocked(myId, snapToPublish.hostMemberId) }
            _state.update { cur ->
                cur.copy(
                    members = mergedMembers,
                    maxMembers = snapToPublish.maxMembers,
                    hostOnlyControl = snapToPublish.hostOnlyControl,
                    playback = snapToPublish.playback,
                    queue = snapToPublish.queue,
                )
            }
            activityEntry?.let(::recordActivity)
            scope.launch {
                runCatching {
                    mqttClient?.publish(stateTopic(code), json.encodeToString(MqttPartyStateSnapshot.serializer(), snapToPublish), retain = true)
                }
            }
        }
    }

    private fun mergedMemberListLocked(myMemberId: String, hostMemberId: String): List<PartyMember> =
        presenceMembers.values
            .filter { it.connected || it.memberId == myMemberId }
            .sortedWith(compareBy<PartyMember> { it.memberId != hostMemberId }.thenBy { it.joinedAtMs })
            .map { it.copy(isHost = it.memberId == hostMemberId) }

    private fun appendChat(msg: PartyChatMessage) {
        synchronized(stateMutex) {
            if (!seenChatIds.add(msg.id)) return
            if (seenChatIds.size > 300) {
                seenChatIds.firstOrNull()?.let { seenChatIds.remove(it) }
            }
        }
        _chatMessages.update { (it + msg).takeLast(120) }
    }

    // --------------------------------------------------------- preview ---

    private data class RoomLookupResult(
        val snapshot: MqttPartyStateSnapshot,
        val members: List<PartyMember>,
        val clusterUrl: String,
    )

    suspend fun previewParty(code: String, server: String? = null): Result<PartyPreview> = withContext(Dispatchers.IO) {
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            return@withContext Result.failure(PartyException("bad_code", "A party code is six letters or digits."))
        }
        runCatching {
            refuseIfRecentlyKicked(cleaned)
            val result = fetchRoomSnapshot(cleaned, server ?: effectiveIdleServerBase())
                ?: throw PartyException("party_not_found", "Tidak ada ruang dengan kode $cleaned.")
            val snap = result.snapshot
            val members = result.members.filter { it.connected }
            val host = members.firstOrNull { it.memberId == snap.hostMemberId } ?: members.firstOrNull()
            PartyPreview(
                code = cleaned,
                hostName = host?.displayName ?: "Host Musify",
                memberCount = members.size.coerceAtLeast(1),
                maxMembers = snap.maxMembers,
                isFull = members.size >= snap.maxMembers,
                members = members.map {
                    PartyPreviewMember(
                        displayName = it.displayName,
                        avatarUrl = it.avatarUrl,
                        isHost = it.memberId == snap.hostMemberId,
                    )
                },
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { failure ->
                val uiMessage = failure.displayMessage()
                Log.w(TAG, "could not look up a party: ${redact(failure.message)}")
                Result.failure(failure as? PartyException ?: PartyException("unreachable", uiMessage))
            },
        )
    }

    private fun fetchRoomSnapshot(code: String, serverBase: String): RoomLookupResult? {
        val groupedByHost = LinkedHashMap<String, MutableList<MiniMqttClient.RelayEndpoint>>()
        for (ep in relayEndpointCandidatesFor(serverBase)) {
            groupedByHost.getOrPut(ep.host) { ArrayList() }.add(ep)
        }
        val allowCrossClusterSearch = normalizeServerBase(serverBase).let { it.isBlank() || it == defaultServer }
        var anyClusterConnected = false
        var lastError: Throwable? = null

        for ((index, entry) in groupedByHost.entries.withIndex()) {
            val hostEndpoints = entry.value
            val client = runCatching {
                connectFirstHealthyEndpoint(
                    endpoints = hostEndpoints,
                    clientId = "musify-prev-${UUID.randomUUID().toString().take(8)}",
                )
            }.getOrElse { err ->
                lastError = err
                continue
            }
            anyClusterConnected = true

            var foundSnap: MqttPartyStateSnapshot? = null
            val members = LinkedHashMap<String, PartyMember>()
            val lock = Object()
            client.onMessage = { topic, payload, _ ->
                val base = topicPrefix(code)
                if (topic == "$base/state" && payload.isNotBlank()) {
                    runCatching { json.decodeFromString(MqttPartyStateSnapshot.serializer(), payload) }
                        .getOrNull()?.let { s ->
                            synchronized(lock) {
                                foundSnap = s
                                lock.notifyAll()
                            }
                        }
                } else if (topic.startsWith("$base/presence/") && payload.isNotBlank()) {
                    runCatching { json.decodeFromString(PartyMember.serializer(), payload) }
                        .getOrNull()?.let { m ->
                            synchronized(lock) {
                                members[m.memberId] = m
                            }
                        }
                }
            }
            try {
                client.subscribe(roomWildcard(code))
                val waitWindow = if (index == 0) 2_200L else 1_400L
                val deadline = System.currentTimeMillis() + waitWindow
                synchronized(lock) {
                    while (foundSnap == null) {
                        val rem = deadline - System.currentTimeMillis()
                        if (rem <= 0L) break
                        lock.wait(rem.coerceAtMost(200L))
                    }
                }
                if (foundSnap != null) {
                    Thread.sleep(180L) // allow retained presence frames to flush
                    val clusterUrl = if (
                        client.host == "broker.emqx.io" && allowCrossClusterSearch
                    ) {
                        defaultServer
                    } else {
                        "https://${client.host}:${client.port}"
                    }
                    return RoomLookupResult(foundSnap!!, members.values.toList(), clusterUrl)
                }
            } finally {
                client.close()
            }
            if (!allowCrossClusterSearch) break
        }

        if (!anyClusterConnected) {
            throw lastError ?: PartyException("unreachable", UNREACHABLE)
        }
        return null
    }

    private fun connectFirstHealthyRelay(
        serverUrl: String,
        clientId: String,
        willTopic: String? = null,
        willPayload: String? = null,
    ): MiniMqttClient = connectFirstHealthyEndpoint(
        endpoints = relayEndpointCandidatesFor(serverUrl),
        clientId = clientId,
        willTopic = willTopic,
        willPayload = willPayload,
    )

    private fun connectFirstHealthyEndpoint(
        endpoints: List<MiniMqttClient.RelayEndpoint>,
        clientId: String,
        willTopic: String? = null,
        willPayload: String? = null,
    ): MiniMqttClient {
        var lastError: Throwable? = null
        for (ep in endpoints) {
            val client = MiniMqttClient(ep.host, ep.port, clientId, ep.wsPath)
            try {
                client.connect(
                    willTopic = willTopic,
                    willPayload = willPayload,
                    willRetain = true,
                    timeoutMs = 5_000,
                )
                return client
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "MQTT endpoint ${ep.label} failed: ${redact(t.message)}")
                runCatching { client.close() }
            }
        }
        throw lastError ?: PartyException("unreachable", UNREACHABLE)
    }

    private fun relayEndpointCandidatesFor(serverUrl: String): List<MiniMqttClient.RelayEndpoint> {
        val raw = normalizeServerBase(serverUrl)
        if (raw.isBlank() || raw == defaultServer) return MiniMqttClient.DEFAULT_ENDPOINTS
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val parsed = runCatching { URI(withScheme) }.getOrNull()
        val host = parsed?.host?.takeIf { it.isNotBlank() } ?: return MiniMqttClient.DEFAULT_ENDPOINTS
        val port = parsed.port.takeIf { it in 1..65535 } ?: MiniMqttClient.DEFAULT_TLS_PORT
        val wsPath = if (parsed.scheme.equals("wss", ignoreCase = true) || parsed.scheme.equals("ws", ignoreCase = true)) {
            parsed.path?.takeIf { it.isNotBlank() } ?: "/mqtt"
        } else {
            null
        }
        return when {
            host.equals("broker.emqx.io", ignoreCase = true) -> MiniMqttClient.DEFAULT_ENDPOINTS
            host.equals("test.mosquitto.org", ignoreCase = true) -> {
                val mosquittoFirst = listOf(
                    MiniMqttClient.RelayEndpoint("test.mosquitto.org", 8886, wsPath = null),
                    MiniMqttClient.RelayEndpoint("test.mosquitto.org", 8081, wsPath = "/mqtt"),
                )
                mosquittoFirst + MiniMqttClient.DEFAULT_ENDPOINTS.filterNot { it.host.equals("test.mosquitto.org", ignoreCase = true) }
            }
            host.equals("broker.hivemq.com", ignoreCase = true) -> {
                val hiveFirst = listOf(
                    MiniMqttClient.RelayEndpoint("broker.hivemq.com", 8883, wsPath = null),
                    MiniMqttClient.RelayEndpoint("broker.hivemq.com", 8884, wsPath = "/mqtt"),
                )
                hiveFirst + MiniMqttClient.DEFAULT_ENDPOINTS.filterNot { it.host.equals("broker.hivemq.com", ignoreCase = true) }
            }
            else -> listOf(MiniMqttClient.RelayEndpoint(host, port, wsPath))
        }
    }

    private fun closeSessionLocked() {
        socketJob?.cancel()
        socketJob = null
        runCatching { mqttClient?.close() }
        mqttClient = null
    }

    // --------------------------------------------------------- identity ----

    private data class Identity(
        val memberId: String,
        val userId: String,
        val name: String,
        val avatar: String?,
    )

    private fun identity(nickname: String = nickname()): Identity {
        val devId = deviceId()
        val store = runCatching { BitChordApplication.authStore }.getOrNull()
        val account = if (store?.isSignedIn == true) store.activeSession else null
        val profile = account?.let { acc ->
            acc.profiles.firstOrNull { it.profileId == acc.activeProfileId } ?: acc.profiles.firstOrNull()
        }
        val rawName = nickname.trim().takeIf { it.isNotBlank() }
            ?: profile?.name?.takeIf { it.isNotBlank() }
            ?: account?.name?.takeIf { it.isNotBlank() }
            ?: account?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "Pendengar"
        val cleanName = SafeTextFilter.maskUnsafeText(rawName).ifBlank { "Pendengar" }
        val userId = if (account != null) {
            sha256("${account.accountId}:${profile?.profileId.orEmpty()}").take(24)
        } else {
            "guest-${sha256(devId).take(16)}"
        }
        val memberId = sha256("$userId:$devId").take(16)
        return Identity(
            memberId = memberId,
            userId = userId,
            name = cleanName,
            avatar = profile?.avatar?.takeIf { it.startsWith("http") },
        )
    }

    fun myAvatarUrl(): String? = identity().avatar

    private fun deviceId(): String {
        if (!::prefs.isInitialized) return "dev-local"
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }
    }

    // ------------------------------------------------------- recent kicks --

    private fun recentKicks(): Map<String, Long> {
        if (!::prefs.isInitialized) return emptyMap()
        val raw = prefs.getString(KEY_KICKED, null) ?: return emptyMap()
        val stored = runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
        }.getOrNull() ?: return emptyMap()
        val now = System.currentTimeMillis()
        val live = stored.filterValues { it > now }
        if (live.size != stored.size) writeKicks(live)
        return live
    }

    private fun writeKicks(entries: Map<String, Long>) {
        if (!::prefs.isInitialized) return
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_KICKED).apply()
            return
        }
        val encoded = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), entries)
        prefs.edit().putString(KEY_KICKED, encoded).apply()
    }

    private fun recordKick(code: String) {
        val normalised = code.filter { it.isLetterOrDigit() }.uppercase()
        if (normalised.isEmpty()) return
        writeKicks(recentKicks() + (normalised to System.currentTimeMillis() + KICK_BLOCK_MS))
    }

    private fun refuseIfRecentlyKicked(code: String) {
        if (recentKicks().containsKey(code)) {
            throw PartyException("recently_kicked", "Couldn’t let you in — you’ve recently been kicked out of this party.")
        }
    }

    private fun recordActivity(entry: PartyActivity) {
        _activity.value = (listOf(entry) + _activity.value).take(100)
    }

    private fun generateRoomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }

    private fun topicPrefix(code: String): String = "musify/dengar/v1/${code.uppercase()}"
    private fun roomWildcard(code: String): String = "${topicPrefix(code)}/#"
    private fun stateTopic(code: String): String = "${topicPrefix(code)}/state"
    private fun presenceTopic(code: String, memberId: String): String = "${topicPrefix(code)}/presence/$memberId"
    private fun cmdTopic(code: String): String = "${topicPrefix(code)}/cmd"
    private fun chatTopic(code: String): String = "${topicPrefix(code)}/chat"
    private fun voteTopic(code: String): String = "${topicPrefix(code)}/vote"

    private fun mqttClientId(code: String, memberId: String): String =
        "musify-${code.uppercase()}-${memberId.take(8)}-${Random.nextInt(1000, 9999)}"

    fun normalizeServerBase(value: String): String = value.trim().trimEnd('/')

    private fun resolveHttpBase(server: String): String {
        val raw = normalizeServerBase(server).ifBlank { defaultServer }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun redact(text: String?): String {
        var out = text.orEmpty()
        if (out.isEmpty()) return out
        listOfNotNull(defaultServer, _customServer.value, activePartyServerBase)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.substringAfter("://")) }
            .sortedByDescending(String::length)
            .forEach { out = out.replace(it, SERVER_PLACEHOLDER, ignoreCase = true) }
        return out.replace(ABSOLUTE_URL, SERVER_PLACEHOLDER)
    }

    private fun Throwable.displayMessage(): String = when (this) {
        is PartyException -> message ?: UNREACHABLE
        else -> UNREACHABLE
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    const val CODE_LENGTH = 6

    private const val TAG = "ListenTogether"
    private const val PREFS = "musify_listen_together"
    private const val KEY_SERVER = "server_url"
    private const val SERVER_PLACEHOLDER = "<party relay>"
    private const val UNREACHABLE = "Couldn’t reach the party relay."
    private val ABSOLUTE_URL = Regex("""(?:https?|wss?)://[^\s,;)\]}'\"]+""", RegexOption.IGNORE_CASE)
    private const val KEY_CODE = "party_code"
    private const val KEY_TOKEN = "party_token"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_NICKNAME = "party_nickname"
    private const val KEY_KICKED = "party_kicked_until"
    private const val KICK_BLOCK_MS = 24L * 60 * 60 * 1000
    private const val HEALTH_POLL_INTERVAL_MS = 10_000L
    private const val HEALTH_MIN_GAP_MS = 5_000L
    private const val HEARTBEAT_INTERVAL_MS = 5_000L
    private const val RESUME_LEAD_MS = 280L
    private val HOST_ONLY_ACTIONS = setOf("setMaxMembers", "setHostOnlyControl", "kick")
}

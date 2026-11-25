package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Where the player gets its lyrics.
 *
 * Sources tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.offered]; a [hidden][LyricsSource.hidden] one is
 * never asked, whatever the saved settings say:
 *
 *  - [LrcRed] — Apple Music TTML filed by ISRC, and the catalogue
 *    [BiniLyrics] itself answers from.
 *  - [BiniLyrics], [BetterLyrics] — Apple Music TTML, per-syllable, from
 *    independent hosts so one having a bad day doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; Apple syllable timing on live mirrors.
 *  - [Unison] — contributed TTML/LRC, plus recording ISRC hints.
 *  - [PaxSenix], [LrcLib], [Musixmatch], [KuGou] — fallbacks and line-synced
 *    catalogues.
 *
 * Every enabled non-lazy source starts concurrently at `t = 0`: [BiniLyrics]
 * never stalls the rest of the waterfall waiting on an up-front ISRC lookup.
 * When any provider finishes early with usable lyrics, [onPreliminaryResult]
 * delivers them immediately so the UI renders in hundreds of milliseconds while
 * higher-priority or word-synced providers within their short grace window can
 * still upgrade the result seamlessly.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    private data class LookupKey(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSeconds: Long,
        val album: String,
        val sequence: List<LyricsSource>,
        val prioritizeSyllableSync: Boolean,
        val isrc: String,
    )

    private class LookupListeners(
        val onSourceStarted: ((LyricsSource) -> Unit)?,
        val onSourceResult: ((LyricsSource, Result?) -> Unit)?,
        val onSourceCancelled: ((LyricsSource) -> Unit)?,
        val onPreliminaryResult: ((Result) -> Unit)?,
    )

    private class InFlightLookup(
        val deferred: CompletableDeferred<Result?>,
    ) {
        lateinit var job: Job
        val started: MutableSet<LyricsSource> = Collections.synchronizedSet(HashSet())
        val results: ConcurrentHashMap<LyricsSource, Result?> = ConcurrentHashMap()
        @Volatile var latestPreliminary: Result? = null
        val waiters = AtomicInteger(0)
        val listeners = CopyOnWriteArrayList<LookupListeners>()
    }

    private data class IndexedResult(
        val index: Int,
        val result: Result,
    ) {
        val isWordSynced: Boolean = result.lines.any { it.isWordSynced }
        val isTimed: Boolean = isWordSynced || result.lines.any { it.timeMs > 0 }
        val tier: Int = when {
            isWordSynced -> 3
            isTimed -> 2
            else -> 1
        }
    }

    private const val RESULT_CACHE_LIMIT = 150
    private const val WORD_GRACE_MS = 900L
    private const val LINE_UPGRADE_GRACE_MS = 2_000L
    private const val ISRC_CROSSFEED_WAIT_MS = 1_500L

    private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<LookupKey, InFlightLookup>()
    private val resultCache: MutableMap<LookupKey, Result> = Collections.synchronizedMap(
        object : LinkedHashMap<LookupKey, Result>(RESULT_CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<LookupKey, Result>) =
                size > RESULT_CACHE_LIMIT
        },
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not. On, a merely line-synced answer is kept only as a
     * fallback (and surfaced right away via [onPreliminaryResult]) while the
     * word-synced providers in [order] finish within a tight grace window.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = true,
        isrc: String? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
        /**
         * Fires immediately when a fast provider yields usable lyrics ahead of a
         * higher-priority or word-synced candidate still in flight, and again if
         * a better tier replaces it before the race closes.
         */
        onPreliminaryResult: ((Result) -> Unit)? = null,
    ): Result? {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }
        if (sequence.isEmpty()) return null

        // Every source but [SimpMusicLyrics] is asked for a name, and
        // YouTube's is not the name anyone catalogued. Cleaned once, here,
        // rather than by whichever source thought to do it for itself.
        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()
        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]

        val key = LookupKey(
            videoId = videoId,
            title = searchTitle,
            artist = searchArtist,
            durationSeconds = durationMs / 1_000L,
            album = album.orEmpty(),
            sequence = sequence,
            prioritizeSyllableSync = prioritizeSyllableSync,
            isrc = known.orEmpty(),
        )

        val cached = synchronized(resultCache) {
            resultCache[key] ?: resultCache.entries.firstOrNull { compatibleKey(it.key, key) }?.value
        }
        if (cached != null) {
            onPreliminaryResult?.invoke(cached)
            onSourceResult?.invoke(cached.source, cached)
            return cached
        }

        val callbackBundle = LookupListeners(
            onSourceStarted = onSourceStarted,
            onSourceResult = onSourceResult,
            onSourceCancelled = onSourceCancelled,
            onPreliminaryResult = onPreliminaryResult,
        )

        var createdHere = false
        var snapshotStarted: List<LyricsSource> = emptyList()
        var snapshotResults: Map<LyricsSource, Result?> = emptyMap()
        var snapshotPreliminary: Result? = null
        var joinedRun: InFlightLookup? = null
        var activeKey: LookupKey = key

        val compatibleActiveKey = inFlight.keys.firstOrNull { compatibleKey(it, key) }
        if (compatibleActiveKey != null && compatibleActiveKey != key) {
            inFlight.computeIfPresent(compatibleActiveKey) { _, existing ->
                if (!existing.deferred.isCompleted) {
                    existing.waiters.incrementAndGet()
                    snapshotStarted = synchronized(existing.started) { existing.started.toList() }
                    snapshotResults = existing.results.toMap()
                    snapshotPreliminary = existing.latestPreliminary
                    existing.listeners.add(callbackBundle)
                    joinedRun = existing
                    activeKey = compatibleActiveKey
                }
                existing
            }
        }

        val run = joinedRun ?: inFlight.compute(key) { _, existing ->
            if (existing != null && !existing.deferred.isCompleted) {
                existing.waiters.incrementAndGet()
                snapshotStarted = synchronized(existing.started) { existing.started.toList() }
                snapshotResults = existing.results.toMap()
                snapshotPreliminary = existing.latestPreliminary
                existing.listeners.add(callbackBundle)
                existing
            } else {
                createdHere = true
                val fresh = InFlightLookup(deferred = CompletableDeferred())
                fresh.waiters.incrementAndGet()
                fresh.listeners.add(callbackBundle)
                fresh.job = sharedScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val found = executeLookup(
                            videoId = videoId,
                            searchTitle = searchTitle,
                            searchArtist = searchArtist,
                            durationMs = durationMs,
                            album = album,
                            knownIsrc = known,
                            sequence = sequence,
                            prioritizeSyllableSync = prioritizeSyllableSync,
                            onSourceStarted = { source ->
                                fresh.started.add(source)
                                fresh.listeners.forEach { it.onSourceStarted?.invoke(source) }
                            },
                            onSourceResult = { source, result ->
                                fresh.results[source] = result
                                fresh.listeners.forEach { it.onSourceResult?.invoke(source, result) }
                            },
                            onSourceCancelled = { source ->
                                fresh.listeners.forEach { it.onSourceCancelled?.invoke(source) }
                            },
                            onPreliminaryResult = { result ->
                                fresh.latestPreliminary = result
                                fresh.listeners.forEach { it.onPreliminaryResult?.invoke(result) }
                            },
                        )
                        if (found != null) {
                            resultCache[key] = found
                        }
                        fresh.deferred.complete(found)
                    } catch (cancelled: CancellationException) {
                        fresh.deferred.cancel(cancelled)
                        throw cancelled
                    } catch (_: Exception) {
                        fresh.deferred.complete(null)
                    } finally {
                        inFlight.remove(key, fresh)
                    }
                }
                fresh
            }
        }!!

        if (createdHere) {
            run.job.start()
        } else {
            // Catch a joining caller's UI/drawer callbacks up to everything that
            // already started or answered while the first caller began the race.
            snapshotStarted.forEach { onSourceStarted?.invoke(it) }
            snapshotResults.forEach { (source, result) -> onSourceResult?.invoke(source, result) }
            snapshotPreliminary?.let { onPreliminaryResult?.invoke(it) }
        }

        return try {
            run.deferred.await()
        } finally {
            run.listeners.remove(callbackBundle)
            inFlight.computeIfPresent(activeKey) { _, current ->
                if (current === run && current.waiters.decrementAndGet() <= 0 && !current.deferred.isCompleted) {
                    current.job.cancel()
                    null
                } else {
                    current
                }
            }
        }
    }

    internal fun compatibleDurationSeconds(aSeconds: Long, bSeconds: Long): Boolean =
        aSeconds == bSeconds || (aSeconds > 0L && bSeconds > 0L && abs(aSeconds - bSeconds) <= 2L)

    private fun compatibleKey(a: LookupKey, b: LookupKey): Boolean {
        if (a == b) return true
        if (a.videoId != b.videoId ||
            a.title != b.title ||
            a.artist != b.artist ||
            a.album != b.album ||
            a.sequence != b.sequence ||
            a.prioritizeSyllableSync != b.prioritizeSyllableSync ||
            a.isrc != b.isrc
        ) {
            return false
        }
        return compatibleDurationSeconds(a.durationSeconds, b.durationSeconds)
    }

    private suspend fun executeLookup(
        videoId: String,
        searchTitle: String,
        searchArtist: String,
        durationMs: Long,
        album: String?,
        knownIsrc: String?,
        sequence: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        onSourceStarted: ((LyricsSource) -> Unit)?,
        onSourceResult: ((LyricsSource, Result?) -> Unit)?,
        onSourceCancelled: ((LyricsSource) -> Unit)?,
        onPreliminaryResult: ((Result) -> Unit)?,
    ): Result? = coroutineScope {
        val documents = SharedDocuments(this)
        val crossFedIsrc = CompletableDeferred<String?>().apply {
            if (!knownIsrc.isNullOrBlank()) complete(knownIsrc)
        }
        val crossFeeders = AtomicInteger(
            (if (LyricsSource.BINI_LYRICS in sequence) 1 else 0) +
                (if (LyricsSource.UNISON in sequence) 1 else 0),
        )
        if (crossFeeders.get() == 0 && !crossFedIsrc.isCompleted) {
            crossFedIsrc.complete(null)
        }
        fun finishCrossFeeder(foundIsrc: String?) {
            if (!foundIsrc.isNullOrBlank()) {
                remember(videoId, foundIsrc)
                crossFedIsrc.complete(foundIsrc)
            } else if (crossFeeders.decrementAndGet() <= 0 && !crossFedIsrc.isCompleted) {
                crossFedIsrc.complete(null)
            }
        }

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) CoroutineStart.LAZY else CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = startMode) {
                onSourceStarted?.invoke(source)
                try {
                    val found = fetch(
                        source = source,
                        videoId = videoId,
                        title = searchTitle,
                        artist = searchArtist,
                        durationMs = durationMs,
                        album = album,
                        isrc = knownIsrc,
                        crossFedIsrc = crossFedIsrc,
                        onResolvedIsrc = ::finishCrossFeeder,
                        get = documents::get,
                    )?.let { result(source, it) }
                    onSourceResult?.invoke(source, found)
                    found
                } catch (cancelled: CancellationException) {
                    if (source == LyricsSource.BINI_LYRICS || source == LyricsSource.UNISON) {
                        finishCrossFeeder(null)
                    }
                    onSourceCancelled?.invoke(source)
                    throw cancelled
                } catch (_: Exception) {
                    if (source == LyricsSource.BINI_LYRICS || source == LyricsSource.UNISON) {
                        finishCrossFeeder(null)
                    }
                    onSourceResult?.invoke(source, null)
                    null
                }
            }
        }

        try {
            race(
                racing = racing.map { (source, job) -> source to suspend { job.await() } },
                prioritizeSyllableSync = prioritizeSyllableSync,
                wordGraceMs = WORD_GRACE_MS,
                lineUpgradeGraceMs = LINE_UPGRADE_GRACE_MS,
                onPreliminaryResult = onPreliminaryResult,
            )
        } finally {
            racing.forEach { it.second.cancel() }
            documents.cancel()
        }
    }

    /**
     * Event-driven priority race across providers.
     *
     * Launches all non-lazy providers concurrently, delivers the first usable
     * hit immediately via [onPreliminaryResult], and returns the highest-priority
     * winner without stalling on broken/hanging mirrors past short grace windows.
     */
    internal suspend fun race(
        racing: List<Pair<LyricsSource, suspend () -> Result?>>,
        prioritizeSyllableSync: Boolean,
        wordGraceMs: Long = WORD_GRACE_MS,
        lineUpgradeGraceMs: Long = LINE_UPGRADE_GRACE_MS,
        onPreliminaryResult: ((Result) -> Unit)? = null,
    ): Result? = coroutineScope {
        if (racing.isEmpty()) return@coroutineScope null

        val nonLazyEntries = racing.mapIndexedNotNull { index, (source, task) ->
            if (source == LyricsSource.GENIUS) null
            else index to (source to async(start = CoroutineStart.DEFAULT) { runCatching { task() }.getOrNull() })
        }
        val geniusEntry = racing.withIndex().firstOrNull { it.value.first == LyricsSource.GENIUS }
        val outcomes = arrayOfNulls<IndexedResult?>(racing.size)
        val finished = BooleanArray(racing.size)
        val pending = nonLazyEntries.toMutableList()
        var lastPreliminary: IndexedResult? = null
        var deadlineAtMs: Long? = null

        try {
            while (pending.isNotEmpty()) {
                val next: Pair<Int, Result?> = if (deadlineAtMs == null) {
                    select<Pair<Int, Result?>> {
                        pending.forEach { (index, pair) ->
                            pair.second.onAwait { res -> index to res }
                        }
                    }
                } else {
                    val remaining = deadlineAtMs - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    withTimeoutOrNull(remaining) {
                        select<Pair<Int, Result?>> {
                            pending.forEach { (index, pair) ->
                                pair.second.onAwait { res -> index to res }
                            }
                        }
                    } ?: break
                }

                val (completedIndex, completedResult) = next
                pending.removeAll { it.first == completedIndex }
                finished[completedIndex] = true
                if (completedResult != null) {
                    outcomes[completedIndex] = IndexedResult(completedIndex, completedResult)
                }

                val best = bestOf(outcomes, prioritizeSyllableSync) ?: continue
                if (isBetterPreliminary(best, lastPreliminary)) {
                    lastPreliminary = best
                    onPreliminaryResult?.invoke(best.result)
                }

                val canFinishEarly = if (!prioritizeSyllableSync) {
                    best.isTimed && (0 until best.index).all { idx ->
                        racing[idx].first == LyricsSource.GENIUS || finished[idx]
                    }
                } else {
                    when {
                        best.isWordSynced -> (0 until best.index).all { idx ->
                            racing[idx].first == LyricsSource.GENIUS ||
                                !racing[idx].first.wordSynced ||
                                finished[idx]
                        }
                        best.isTimed -> nonLazyEntries.all { (idx, pair) ->
                            finished[idx] || (!pair.first.wordSynced && idx >= best.index)
                        }
                        else -> false
                    }
                }
                if (canFinishEarly) return@coroutineScope best.result

                val now = System.currentTimeMillis()
                val targetGrace = when {
                    best.isWordSynced || (!prioritizeSyllableSync && best.isTimed) -> wordGraceMs
                    best.isTimed -> lineUpgradeGraceMs
                    else -> null
                }
                if (targetGrace != null) {
                    val proposed = now + targetGrace
                    deadlineAtMs = deadlineAtMs?.let { minOf(it, proposed) } ?: proposed
                }
            }

            val bestNonLazy = bestOf(outcomes, prioritizeSyllableSync)?.result
            if (bestNonLazy != null) return@coroutineScope bestNonLazy

            if (geniusEntry != null) {
                return@coroutineScope runCatching { geniusEntry.value.second() }.getOrNull()
            }
            null
        } finally {
            pending.forEach { it.second.second.cancel() }
        }
    }

    private fun bestOf(
        outcomes: Array<IndexedResult?>,
        prioritizeSyllableSync: Boolean,
    ): IndexedResult? {
        var wordSynced: IndexedResult? = null
        var lineSynced: IndexedResult? = null
        var plain: IndexedResult? = null
        for (entry in outcomes) {
            if (entry == null) continue
            if (entry.isWordSynced) {
                if (wordSynced == null) wordSynced = entry
                if (!prioritizeSyllableSync && lineSynced == null) return entry
            } else if (entry.isTimed) {
                if (!prioritizeSyllableSync && wordSynced == null) return entry
                if (lineSynced == null) lineSynced = entry
            } else if (plain == null) {
                plain = entry
            }
        }
        return wordSynced ?: lineSynced ?: plain
    }

    private fun isBetterPreliminary(candidate: IndexedResult, previous: IndexedResult?): Boolean {
        if (previous == null) return true
        return when {
            candidate.tier > previous.tier -> true
            candidate.tier == previous.tier && candidate.index < previous.index -> true
            else -> false
        }
    }

    /**
     * The first word-synced result in [racing] order; failing that, the first
     * line-synced one; failing that, the first plain one. Without
     * [prioritizeSyllableSync], the first timed result wins outright.
     *
     * Plain and line-synced are kept apart: a plain result that happened to
     * arrive first must not shadow a line-synced one further down the order.
     */
    internal suspend fun pick(
        racing: List<Pair<LyricsSource, suspend () -> Result?>>,
        prioritizeSyllableSync: Boolean,
    ): Result? {
        var lineSynced: Result? = null
        var plain: Result? = null
        for ((source, await) in racing) {
            // If we already found a line-synced or better result, skip Genius completely
            if ((lineSynced ?: plain) != null && source == LyricsSource.GENIUS) continue

            val found = runCatching { await() }.getOrNull() ?: continue
            if (found.lines.any { it.isWordSynced }) return found
            if (found.lines.any { it.timeMs > 0 }) {
                if (!prioritizeSyllableSync) return found
                if (lineSynced == null) lineSynced = found
            } else if (plain == null) {
                plain = found
            }
        }
        return lineSynced ?: plain
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        crossFedIsrc: Deferred<String?>,
        onResolvedIsrc: (String?) -> Unit,
        /** Fetches a document once per lookup, however many sources want it. */
        get: suspend (String) -> String?,
    ): List<LyricLine>? {
        val found = when (source) {
            LyricsSource.LRC_RED -> {
                val direct = LrcRed.lyrics(title, artist, durationMs, isrc, get)
                if (direct != null) {
                    direct
                } else if (isrc.isNullOrBlank()) {
                    val discovered = withTimeoutOrNull(ISRC_CROSSFEED_WAIT_MS) { crossFedIsrc.await() }
                    if (!discovered.isNullOrBlank()) {
                        LrcRed.lyrics(title, artist, durationMs, discovered, get)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
            LyricsSource.BINI_LYRICS -> {
                val match = if (!isrc.isNullOrBlank()) {
                    BiniLyrics.lyrics(title, artist, durationMs, album, isrc, get)
                } else {
                    val hit = BiniLyrics.identify(title, artist, durationMs, album)
                    onResolvedIsrc(hit?.isrc)
                    hit?.let { BiniLyrics.lyricsFor(it, get) }
                }
                match?.also { remember(videoId, it.isrc) }?.lines
            }
            LyricsSource.UNISON -> {
                val match = Unison.lyricsMatch(title, artist, durationMs, album)
                onResolvedIsrc(match?.isrc)
                match?.lines
            }
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
            LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
            LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
            LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
            LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
            LyricsSource.GENIUS -> Genius.lyrics(title, artist)
        }
        // An instrumental's "pure music, please enjoy" is no lyrics, whoever relays it.
        return found?.takeUnless(KuGou::isInstrumentalNotice)
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a video id, once something has worked it out.
     *
     * Bounded, least-recently-used, and in memory only. This is a shortcut,
     * not a store: losing it costs one fuzzy match, which is what every lookup
     * did before any of this, and keeping it on disk would mean keeping a
     * wrong answer on disk too.
     */
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs.put(videoId, isrc)
    }

    /**
     * Documents fetched during one lookup, by URL, so two sources after the
     * same file make one request between them. That is the ordinary case, not
     * an edge: [BiniLyrics]' search answers with an [LrcRed] URL, and [LrcRed],
     * handed the ISRC that search found, asks for exactly that URL too.
     *
     * Each download runs in the lookup's own scope rather than the asking
     * source's, so the source that started it losing the race doesn't take
     * the other one's copy down with it; [cancel] ends whatever is left.
     */
    private class SharedDocuments(private val scope: CoroutineScope) {
        private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

        suspend fun get(url: String): String? =
            inFlight.computeIfAbsent(url) { scope.async(Dispatchers.IO) { lyricsGet(url) } }.await()

        fun cancel() = inFlight.values.forEach { it.cancel() }
    }
}

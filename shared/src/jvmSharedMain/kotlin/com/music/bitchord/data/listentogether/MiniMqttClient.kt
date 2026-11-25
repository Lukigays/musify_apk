package com.music.bitchord.data.listentogether

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Lightweight MQTT v3.1.1 client for Musify Dengar Bersama.
 *
 * Supports two encrypted transports that talk to the exact same broker cluster:
 * 1. Direct MQTT over TLS (`wsPath == null`, ports 8883 / 8886) using an IPv4-first
 *    connected TCP [Socket] wrapped into an [SSLSocket] with explicit SNI (avoiding
 *    Android Conscrypt's unbound `(port 0)` socket bug).
 * 2. MQTT over secure WebSocket (`wsPath != null`, e.g. `wss://broker.emqx.io:8084/mqtt`)
 *    via OkHttp when restrictive Wi-Fi/ISP firewalls block outbound MQTT TLS ports.
 */
class MiniMqttClient(
    val host: String,
    val port: Int = DEFAULT_TLS_PORT,
    val clientId: String,
    val wsPath: String? = null,
) {
    data class RelayEndpoint(
        val host: String,
        val port: Int,
        val wsPath: String? = null,
    ) {
        val label: String
            get() = if (wsPath.isNullOrBlank()) "$host:$port" else "wss://$host:$port$wsPath"
    }

    @Volatile
    var onMessage: ((topic: String, payload: String, retained: Boolean) -> Unit)? = null

    @Volatile
    var onClosed: (() -> Unit)? = null

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var webSocket: WebSocket? = null
    private var wsInput: ChunkedInputStream? = null
    private val writeLock = Any()
    private val closedByUs = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val nextPacketId = AtomicInteger(1)
    private val lastRxMs = AtomicLong(0L)
    private val connAckLock = Object()
    @Volatile
    private var connAckResult: Int? = null

    val isConnected: Boolean
        get() = running.get() && (webSocket != null || socket?.isClosed == false)

    fun connect(
        willTopic: String? = null,
        willPayload: String? = null,
        willRetain: Boolean = true,
        timeoutMs: Int = 8_000,
    ) {
        closedByUs.set(false)
        connAckResult = null

        val inputStream: InputStream
        val path = wsPath?.trim()?.takeIf { it.isNotEmpty() }
        if (path != null) {
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val stream = ChunkedInputStream()
            val openLatch = CountDownLatch(1)
            val openFailure = AtomicReference<Throwable?>(null)
            val opened = AtomicBoolean(false)

            val req = Request.Builder()
                .url("https://$host:$port$normalizedPath")
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()

            val wsClient = sharedWsClient(timeoutMs)
            val ws = wsClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.set(true)
                        lastRxMs.set(System.currentTimeMillis())
                        openLatch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        lastRxMs.set(System.currentTimeMillis())
                        stream.push(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        stream.finish()
                        webSocket.close(1000, null)
                        drop()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        stream.finish()
                        drop()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        openFailure.set(t)
                        stream.finish()
                        openLatch.countDown()
                        drop()
                    }
                },
            )

            val awaited = openLatch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!awaited || !opened.get()) {
                runCatching { ws.cancel() }
                stream.finish()
                val err = openFailure.get()
                throw IOException("WSS handshake failed for $host:$port$normalizedPath", err)
            }
            webSocket = ws
            wsInput = stream
            inputStream = stream
        } else {
            val sock = openTlsSocket(host, port, timeoutMs)
            socket = sock
            output = sock.outputStream
            inputStream = sock.inputStream
        }

        running.set(true)
        lastRxMs.set(System.currentTimeMillis())

        val reader = thread(isDaemon = true, name = "MusifyMqttRx-$host") {
            readLoop(inputStream)
        }

        val connectBytes = encodeConnectPacket(
            clientId = clientId,
            keepAliveSec = 30,
            willTopic = willTopic,
            willPayload = willPayload,
            willRetain = willRetain,
        )
        writeBytes(connectBytes)

        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(connAckLock) {
            while (connAckResult == null && running.get()) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) break
                connAckLock.wait(rem.coerceAtMost(250L))
            }
        }
        val code = connAckResult
        if (code == null || code != 0) {
            close()
            reader.interrupt()
            throw IllegalStateException(
                if (code == null) "MQTT CONNACK timed out ($host:$port)"
                else "MQTT connection refused: code=$code",
            )
        }

        thread(isDaemon = true, name = "MusifyMqttPing-$host") {
            while (running.get()) {
                try {
                    Thread.sleep(15_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                if (System.currentTimeMillis() - lastRxMs.get() > 50_000L) {
                    drop()
                    break
                }
                sendRaw(PINGREQ_PACKET)
            }
        }
    }

    fun subscribe(topic: String) {
        val pid = nextPacketId.getAndIncrement().let { if (it > 0xFFFF) 1 else it }
        sendRaw(encodeSubscribePacket(pid, topic))
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        sendRaw(encodePublishPacket(topic, payload, retain))
    }

    fun ping() {
        sendRaw(PINGREQ_PACKET)
    }

    fun close() {
        closedByUs.set(true)
        running.set(false)
        try {
            synchronized(writeLock) {
                val ws = webSocket
                if (ws != null) {
                    ws.send(DISCONNECT_PACKET.toByteString())
                    ws.close(1000, "bye")
                } else {
                    output?.write(DISCONNECT_PACKET)
                    output?.flush()
                }
            }
        } catch (_: Throwable) {
        }
        drop()
    }

    private fun sendRaw(bytes: ByteArray) {
        if (!running.get()) return
        try {
            writeBytes(bytes)
        } catch (_: Throwable) {
            drop()
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        synchronized(writeLock) {
            val ws = webSocket
            if (ws != null) {
                if (!ws.send(bytes.toByteString())) {
                    throw IOException("WebSocket MQTT send failed")
                }
                return
            }
            val out = output ?: throw IllegalStateException("Not connected")
            out.write(bytes)
            out.flush()
        }
    }

    private fun readLoop(input: InputStream) {
        try {
            while (running.get()) {
                val first = input.read()
                if (first < 0) break
                lastRxMs.set(System.currentTimeMillis())
                val len = readRemainingLength(input)
                val body = readExact(input, len)
                handlePacket(first, body)
            }
        } catch (_: Throwable) {
        } finally {
            drop()
        }
    }

    private fun handlePacket(header: Int, body: ByteArray) {
        when (header ushr 4) {
            2 -> { // CONNACK
                synchronized(connAckLock) {
                    connAckResult = if (body.size >= 2) body[1].toInt() and 0xFF else 255
                    connAckLock.notifyAll()
                }
            }
            3 -> { // PUBLISH
                if (body.size < 2) return
                val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                if (body.size < 2 + topicLen) return
                val topic = body.copyOfRange(2, 2 + topicLen).toString(Charsets.UTF_8)
                val qos = (header ushr 1) and 0x03
                val payloadStart = if (qos > 0) 2 + topicLen + 2 else 2 + topicLen
                if (payloadStart > body.size) return
                if (qos == 1 && body.size >= 2 + topicLen + 2) {
                    val pidHi = body[2 + topicLen]
                    val pidLo = body[2 + topicLen + 1]
                    sendRaw(byteArrayOf(0x40, 0x02, pidHi, pidLo))
                }
                val payload = body.copyOfRange(payloadStart, body.size).toString(Charsets.UTF_8)
                val retained = (header and 0x01) != 0
                runCatching { onMessage?.invoke(topic, payload, retained) }
            }
            // PINGRESP (13), SUBACK (9): updating lastRxMs above already keeps session alive.
        }
    }

    private fun drop() {
        val wasRunning = running.getAndSet(false)
        synchronized(connAckLock) {
            if (connAckResult == null) {
                connAckResult = -1
                connAckLock.notifyAll()
            }
        }
        runCatching { wsInput?.finish() }
        runCatching { webSocket?.cancel() }
        runCatching { socket?.close() }
        wsInput = null
        webSocket = null
        socket = null
        output = null
        if (wasRunning && !closedByUs.get()) {
            runCatching { onClosed?.invoke() }
        }
    }

    private class ChunkedInputStream : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var current: ByteArray? = null
        private var pos = 0
        @Volatile
        private var closed = false

        fun push(bytes: ByteArray) {
            if (bytes.isEmpty() || closed) return
            queue.offer(bytes)
        }

        fun finish() {
            closed = true
            queue.offer(EOF_SENTINEL)
        }

        override fun read(): Int {
            val single = ByteArray(1)
            val r = read(single, 0, 1)
            return if (r <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val cur = current
                if (cur != null) {
                    if (cur === EOF_SENTINEL) return -1
                    if (pos < cur.size) {
                        val n = minOf(len, cur.size - pos)
                        System.arraycopy(cur, pos, b, off, n)
                        pos += n
                        if (pos >= cur.size) {
                            current = null
                            pos = 0
                        }
                        return n
                    }
                    current = null
                    pos = 0
                }
                if (closed && queue.isEmpty()) return -1
                current = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    return -1
                }
                pos = 0
            }
        }

        override fun close() {
            finish()
        }

        private companion object {
            val EOF_SENTINEL = ByteArray(0)
        }
    }

    companion object {
        const val DEFAULT_TLS_PORT = 8883

        /**
         * Primary encrypted MQTT + WSS relay chain.
         * Note:
         * - `broker.emqx.io:8883` and `wss://broker.emqx.io:8084/mqtt` share the exact same
         *   EMQX topic space, so devices behind port-8883 firewalls automatically interoperate
         *   via WSS 8084 with devices on port 8883.
         * - `test.mosquitto.org` uses port `8886` (Let's Encrypt CA trusted by Android) and
         *   `8081` WSS instead of port `8883` (which uses Mosquitto's custom self-signed CA).
         */
        val DEFAULT_ENDPOINTS = listOf(
            RelayEndpoint("broker.emqx.io", 8883, wsPath = null),
            RelayEndpoint("broker.emqx.io", 8084, wsPath = "/mqtt"),
            RelayEndpoint("test.mosquitto.org", 8886, wsPath = null),
            RelayEndpoint("test.mosquitto.org", 8081, wsPath = "/mqtt"),
            RelayEndpoint("broker.hivemq.com", 8883, wsPath = null),
            RelayEndpoint("broker.hivemq.com", 8884, wsPath = "/mqtt"),
        )

        val DEFAULT_RELAYS = listOf(
            "broker.emqx.io" to 8883,
            "test.mosquitto.org" to 8886,
            "broker.hivemq.com" to 8883,
        )

        private val PINGREQ_PACKET = byteArrayOf(0xC0.toByte(), 0x00)
        private val DISCONNECT_PACKET = byteArrayOf(0xE0.toByte(), 0x00)

        private val ipv4FirstDns = Dns { hostname ->
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
        }

        private val baseWsClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(ipv4FirstDns)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        private fun sharedWsClient(timeoutMs: Int): OkHttpClient =
            baseWsClient.newBuilder()
                .connectTimeout(timeoutMs.toLong().coerceIn(2_000L, 12_000L), TimeUnit.MILLISECONDS)
                .build()

        private fun openTlsSocket(host: String, port: Int, timeoutMs: Int): SSLSocket {
            val allAddrs = InetAddress.getAllByName(host)
            val ordered = allAddrs.sortedBy { if (it is Inet4Address) 0 else 1 }
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val perIpTimeout = timeoutMs.coerceIn(1_500, 4_500)
            var lastErr: Throwable? = null
            for (addr in ordered) {
                var raw: Socket? = null
                try {
                    raw = Socket().apply {
                        tcpNoDelay = true
                        connect(InetSocketAddress(addr, port), perIpTimeout)
                    }
                    return (factory.createSocket(raw, host, port, true) as SSLSocket).apply {
                        soTimeout = perIpTimeout
                        val params = sslParameters
                        params.serverNames = listOf(SNIHostName(host))
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        sslParameters = params
                        startHandshake()
                        soTimeout = 60_000
                    }
                } catch (t: Throwable) {
                    lastErr = t
                    runCatching { raw?.close() }
                }
            }
            throw lastErr ?: IOException("Could not reach $host:$port")
        }

        fun encodeUtf8(value: String): ByteArray {
            val raw = value.toByteArray(Charsets.UTF_8)
            val out = ByteArray(2 + raw.size)
            out[0] = ((raw.size ushr 8) and 0xFF).toByte()
            out[1] = (raw.size and 0xFF).toByte()
            System.arraycopy(raw, 0, out, 2, raw.size)
            return out
        }

        fun encodePacket(headerByte: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(body.size + 5)
            out.write(headerByte)
            var n = body.size
            do {
                var digit = n % 128
                n /= 128
                if (n > 0) digit = digit or 0x80
                out.write(digit)
            } while (n > 0)
            out.write(body)
            return out.toByteArray()
        }

        fun encodeConnectPacket(
            clientId: String,
            keepAliveSec: Int = 30,
            willTopic: String? = null,
            willPayload: String? = null,
            willRetain: Boolean = true,
        ): ByteArray {
            val hasWill = !willTopic.isNullOrBlank() && willPayload != null
            var flags = 0x02 // clean session
            if (hasWill) {
                flags = flags or 0x04
                if (willRetain) flags = flags or 0x20
            }
            val body = ByteArrayOutputStream()
            body.write(encodeUtf8("MQTT"))
            body.write(0x04) // MQTT 3.1.1
            body.write(flags)
            body.write((keepAliveSec ushr 8) and 0xFF)
            body.write(keepAliveSec and 0xFF)
            body.write(encodeUtf8(clientId))
            if (hasWill) {
                body.write(encodeUtf8(willTopic!!))
                body.write(encodeUtf8(willPayload!!))
            }
            return encodePacket(0x10, body.toByteArray())
        }

        fun encodeSubscribePacket(packetId: Int, topic: String): ByteArray {
            val body = ByteArrayOutputStream()
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
            body.write(encodeUtf8(topic))
            body.write(0x00) // QoS 0
            return encodePacket(0x82, body.toByteArray())
        }

        fun encodePublishPacket(topic: String, payload: String, retain: Boolean = false): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(encodeUtf8(topic))
            body.write(payload.toByteArray(Charsets.UTF_8))
            val header = if (retain) 0x31 else 0x30
            return encodePacket(header, body.toByteArray())
        }

        fun probeBrokerTcp(host: String, port: Int = DEFAULT_TLS_PORT, timeoutMs: Int = 4_000): Long? {
            val t0 = System.nanoTime()
            return try {
                openTlsSocket(host, port, timeoutMs).use { _ -> }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            } catch (_: Throwable) {
                null
            }
        }

        fun probeEndpoint(endpoint: RelayEndpoint, timeoutMs: Int = 4_000): Long? {
            val path = endpoint.wsPath?.trim()?.takeIf { it.isNotEmpty() }
                ?: return probeBrokerTcp(endpoint.host, endpoint.port, timeoutMs)
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val t0 = System.nanoTime()
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val req = Request.Builder()
                .url("https://${endpoint.host}:${endpoint.port}$normalizedPath")
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()
            val ws = sharedWsClient(timeoutMs).newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        ok.set(true)
                        webSocket.close(1000, null)
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                },
            )
            latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!ok.get()) {
                runCatching { ws.cancel() }
                return null
            }
            return ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }

        private fun readRemainingLength(input: InputStream): Int {
            var mult = 1
            var value = 0
            var count = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("Unexpected EOF in MQTT varint")
                value += (b and 0x7F) * mult
                if ((b and 0x80) == 0) return value
                mult *= 128
                if (++count > 4) throw IllegalStateException("Malformed MQTT remaining length")
            }
        }

        private fun readExact(input: InputStream, count: Int): ByteArray {
            val buf = ByteArray(count)
            var off = 0
            while (off < count) {
                val r = input.read(buf, off, count - off)
                if (r < 0) throw EOFException("Unexpected EOF reading MQTT payload")
                off += r
            }
            return buf
        }
    }
}

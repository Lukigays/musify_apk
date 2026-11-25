package com.music.bitchord.data.listentogether

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Lightweight zero-dependency MQTT v3.1.1 over TLS client for Musify Dengar Bersama.
 * Mirrors Musify's `MiniMqtt` (supports CONNECT + LWT + retain, PUBLISH QoS 0,
 * SUBSCRIBE, PINGREQ/PINGRESP, and clean DISCONNECT over TLS 8883).
 */
class MiniMqttClient(
    val host: String,
    val port: Int = DEFAULT_TLS_PORT,
    val clientId: String,
) {
    @Volatile
    var onMessage: ((topic: String, payload: String, retained: Boolean) -> Unit)? = null

    @Volatile
    var onClosed: (() -> Unit)? = null

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private val closedByUs = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val nextPacketId = AtomicInteger(1)
    private val lastRxMs = AtomicLong(0L)
    private val connAckLock = Object()
    @Volatile
    private var connAckResult: Int? = null

    val isConnected: Boolean
        get() = running.get() && socket?.isClosed == false

    fun connect(
        willTopic: String? = null,
        willPayload: String? = null,
        willRetain: Boolean = true,
        timeoutMs: Int = 10_000,
    ) {
        closedByUs.set(false)
        connAckResult = null
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sock = (factory.createSocket() as SSLSocket).apply {
            soTimeout = 0
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = 60_000
            startHandshake()
        }
        socket = sock
        output = sock.outputStream
        running.set(true)
        lastRxMs.set(System.currentTimeMillis())

        val reader = thread(isDaemon = true, name = "MusifyMqttRx-$host") {
            readLoop(sock.inputStream)
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
                output?.write(DISCONNECT_PACKET)
                output?.flush()
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
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        output = null
        if (wasRunning && !closedByUs.get()) {
            runCatching { onClosed?.invoke() }
        }
    }

    companion object {
        const val DEFAULT_TLS_PORT = 8883
        val DEFAULT_RELAYS = listOf(
            "broker.emqx.io" to 8883,
            "broker.hivemq.com" to 8883,
            "test.mosquitto.org" to 8883,
        )

        private val PINGREQ_PACKET = byteArrayOf(0xC0.toByte(), 0x00)
        private val DISCONNECT_PACKET = byteArrayOf(0xE0.toByte(), 0x00)

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
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket() as SSLSocket).use { sock ->
                    sock.connect(InetSocketAddress(host, port), timeoutMs)
                    sock.soTimeout = timeoutMs
                    sock.startHandshake()
                }
                ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            } catch (_: Throwable) {
                null
            }
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

package com.music.bitchord.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeTextAndMqttTest {

    @Test
    fun `safe text filter catches profanity leetspeak and 18+ phrasing`() {
        assertFalse(SafeTextFilter.isUnsafeText("Lagu ini enak banget didengerin bareng"))
        assertTrue(SafeTextFilter.isUnsafeText("b0kep"))
        assertTrue(SafeTextFilter.isUnsafeText("b o k e p"))
        assertTrue(SafeTextFilter.isUnsafeText("open bo"))
        assertTrue(SafeTextFilter.isUnsafeText("anjjjjing"))
        assertTrue(SafeTextFilter.isUnsafeText("What the fck"))
    }

    @Test
    fun `safe text filter masks unsafe words while preserving context`() {
        val masked = SafeTextFilter.maskUnsafeText("lagu ini anjing bagus")
        assertFalse(SafeTextFilter.isUnsafeText(masked))
        assertTrue(masked.contains("bagus"))
        assertTrue(masked.contains("a*****") || masked.contains("*"))
    }

    @Test
    fun `mqtt packet encoders emit valid MQTT 3_1_1 headers and payloads`() {
        val connect = MiniMqttClient.encodeConnectPacket(
            clientId = "musify-test-1",
            keepAliveSec = 30,
            willTopic = "musify/dengar/v1/ABC123/presence/m1",
            willPayload = """{"connected":false}""",
            willRetain = true,
        )
        assertEquals(0x10, connect[0].toInt() and 0xFF)
        assertTrue(connect.toString(Charsets.UTF_8).contains("MQTT"))
        assertTrue(connect.toString(Charsets.UTF_8).contains("musify-test-1"))

        val sub = MiniMqttClient.encodeSubscribePacket(7, "musify/dengar/v1/ABC123/#")
        assertEquals(0x82, sub[0].toInt() and 0xFF)

        val pub = MiniMqttClient.encodePublishPacket(
            topic = "musify/dengar/v1/ABC123/state",
            payload = """{"code":"ABC123"}""",
            retain = true,
        )
        assertEquals(0x31, pub[0].toInt() and 0xFF)
    }
}

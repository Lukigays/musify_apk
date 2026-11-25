package com.music.bitchord

import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.MiniMqttClient
import com.music.bitchord.data.listentogether.MqttPartyCommand
import com.music.bitchord.data.listentogether.MqttPartyStateSnapshot
import com.music.bitchord.data.listentogether.PartyChatMessage
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyQueue
import com.music.bitchord.data.listentogether.PartyTrack
import com.music.bitchord.data.listentogether.PartyVoteProposal
import com.music.bitchord.data.listentogether.SafeTextFilter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherMusifyParityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `zero-login Musify Dengar Bersama permits guests and defaults to public TLS MQTT relay`() {
        assertTrue(ListenTogether.canJoin())
        assertTrue(ListenTogether.hasServer)
        assertEquals("https://broker.emqx.io:8883", ListenTogether.defaultServer)
    }

    @Test
    fun `mqtt snapshot chat and vote proposals roundtrip cleanly`() {
        val track = PartyTrack(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            durationMs = 213_000L,
        )
        val proposal = PartyVoteProposal(
            track = track,
            proposedById = "member-1",
            proposedByName = "Pendengar",
            votes = listOf("member-1", "member-2"),
            addedAtMs = 1_700_000_000_000L,
        )
        val snap = MqttPartyStateSnapshot(
            code = "MUSIFY",
            hostMemberId = "member-1",
            maxMembers = 8,
            hostOnlyControl = true,
            playback = PartyPlayback(seq = 4L, track = track, isPlaying = true, positionMs = 12_500L),
            queue = PartyQueue(seq = 2L, index = 0, items = listOf(track)),
            kickedMemberIds = listOf("bad-actor"),
            voteQueue = listOf(proposal),
        )

        val raw = json.encodeToString(MqttPartyStateSnapshot.serializer(), snap)
        val decoded = json.decodeFromString(MqttPartyStateSnapshot.serializer(), raw)
        assertEquals("MUSIFY", decoded.code)
        assertEquals("member-1", decoded.hostMemberId)
        assertEquals(8, decoded.maxMembers)
        assertTrue(decoded.hostOnlyControl)
        assertEquals(1, decoded.voteQueue.size)
        assertEquals(2, decoded.voteQueue.first().votes.size)
        assertEquals(listOf("bad-actor"), decoded.kickedMemberIds)

        val chat = PartyChatMessage(
            id = "c1",
            senderId = "member-2",
            senderName = "Luky",
            text = SafeTextFilter.maskUnsafeText("lagunya keren tapi jangan b0kep di chat"),
            timestampMs = 1_700_000_001_000L,
        )
        val decodedChat = json.decodeFromString(
            PartyChatMessage.serializer(),
            json.encodeToString(PartyChatMessage.serializer(), chat),
        )
        assertFalse(SafeTextFilter.isUnsafeText(decodedChat.text))
        assertTrue(decodedChat.text.contains("lagunya keren"))
    }

    @Test
    fun `vote command model preserves action and track`() {
        val cmd = MqttPartyCommand(
            id = "cmd-1",
            senderId = "m1",
            senderName = "Host",
            action = "vote_propose",
            track = PartyTrack(videoId = "abc123", title = "Sunset", artist = "Band"),
        )
        val decoded = json.decodeFromString(
            MqttPartyCommand.serializer(),
            json.encodeToString(MqttPartyCommand.serializer(), cmd),
        )
        assertEquals("vote_propose", decoded.action)
        assertEquals("abc123", decoded.track?.videoId)
    }

    @Test
    fun `mqtt relay list includes primary EMQX TLS plus WSS fallback`() {
        val relays = MiniMqttClient.DEFAULT_RELAYS
        assertTrue(relays.contains("broker.emqx.io" to 8883))
        assertTrue(relays.contains("test.mosquitto.org" to 8886))
        assertTrue(MiniMqttClient.DEFAULT_ENDPOINTS.any { it.host == "broker.emqx.io" && it.wsPath == "/mqtt" })
    }
}

package moe.rukamori.archivetune.desktop.party

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tel.schich.libdatachannel.DataChannelCallback
import tel.schich.libdatachannel.GatheringState
import tel.schich.libdatachannel.LibDataChannelArchDetect
import tel.schich.libdatachannel.PeerConnection
import tel.schich.libdatachannel.PeerConnectionConfiguration
import tel.schich.libdatachannel.SessionDescriptionType

class PlayTogetherManagerTest {
    @Test
    fun `quick tunnel invite becomes secure websocket uri`() {
        val uri = PlayTogetherManager.parseInvite(
            "https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef",
        )

        assertEquals("wss", uri.scheme)
        assertEquals("small-cloud-name.trycloudflare.com", uri.host)
        assertEquals("/party", uri.path)
        assertEquals("token=0123456789abcdef", uri.query)
    }

    @Test
    fun `rejects a non quick tunnel host`() {
        assertFailsWith<IllegalArgumentException> {
            PlayTogetherManager.parseInvite("https://example.com/party?token=0123456789abcdef")
        }
    }

    @Test
    fun `rejects invite without access token`() {
        assertFailsWith<IllegalArgumentException> {
            PlayTogetherManager.parseInvite("https://small-cloud-name.trycloudflare.com/party")
        }
    }

    @Test
    fun `bundled WebRTC data channel negotiates locally`() {
        LibDataChannelArchDetect.initialize()

        PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT).use { host ->
            PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT).use { guest ->
                val hostDescription = CompletableFuture<String>()
                val guestDescription = CompletableFuture<String>()
                val guestChannelFuture = CompletableFuture<tel.schich.libdatachannel.DataChannel>()

                host.onGatheringStateChange.register { peer, state ->
                    if (state == GatheringState.RTC_GATHERING_COMPLETE) hostDescription.complete(peer.localDescription())
                }
                guest.onGatheringStateChange.register { peer, state ->
                    if (state == GatheringState.RTC_GATHERING_COMPLETE) guestDescription.complete(peer.localDescription())
                }
                guest.onDataChannel.register { _, channel -> guestChannelFuture.complete(channel) }

                val hostChannel = host.createDataChannel("archivetune-test")
                assertEquals("archivetune-test", hostChannel.label())
                val hostOpen = CompletableFuture<Unit>()
                hostChannel.onOpen.register { hostOpen.complete(Unit) }

                val offer = hostDescription.get(10, TimeUnit.SECONDS)
                guest.setRemoteDescription(offer, SessionDescriptionType.OFFER)
                host.setAnswer(guestDescription.get(10, TimeUnit.SECONDS))

                val guestChannel = guestChannelFuture.get(10, TimeUnit.SECONDS)
                val received = CompletableFuture<String>()
                guestChannel.onMessage.register(
                    DataChannelCallback.Message.handleText { _, text -> received.complete(text) },
                )

                if (!hostChannel.isOpen) hostOpen.get(10, TimeUnit.SECONDS)
                assertTrue(hostChannel.isOpen)

                hostChannel.sendMessage("party-state")
                assertEquals("party-state", received.get(10, TimeUnit.SECONDS))
            }
        }
    }
}

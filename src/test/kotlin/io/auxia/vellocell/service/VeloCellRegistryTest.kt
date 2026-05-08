package io.auxia.vellocell.service

import io.auxia.vellocell.grpc.serverEvent
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VeloCellRegistryTest {

    private fun event(content: String = "x") = serverEvent {
        messageId = "m"
        roomId = 1L
        senderId = 1L
        senderUsername = "u"
        this.content = content
        timestampMs = 0L
    }

    @Test
    fun `register closes the previous channel if user reconnects`() {
        val r = VeloCellRegistry()
        val first = Channel<io.auxia.vellocell.grpc.ServerEvent>(8)
        val second = Channel<io.auxia.vellocell.grpc.ServerEvent>(8)

        r.register(42L, first)
        r.register(42L, second)

        assertTrue(first.isClosedForSend, "stale session must be closed on re-register")
        assertFalse(second.isClosedForSend, "new session must remain open")
    }

    @Test
    fun `send evicts the user when their bounded channel is full`() {
        val r = VeloCellRegistry()
        val ch = Channel<io.auxia.vellocell.grpc.ServerEvent>(2)
        r.register(7L, ch)

        // Fill the buffer.
        repeat(2) { r.send(7L, event("ok")) }
        // This one should fail to enqueue and evict the user.
        r.send(7L, event("overflow"))

        assertTrue(ch.isClosedForSend, "slow consumer should be evicted")
    }

    @Test
    fun `send to unknown user is a no-op`() {
        val r = VeloCellRegistry()
        // Should not throw.
        r.send(999L, event())
    }

    @Test
    fun `remove closes the channel`() {
        val r = VeloCellRegistry()
        val ch = Channel<io.auxia.vellocell.grpc.ServerEvent>(4)
        r.register(1L, ch)
        r.remove(1L)
        assertTrue(ch.isClosedForSend)
    }

    @Test
    fun `successful send delivers exactly the event`() {
        val r = VeloCellRegistry()
        val ch = Channel<io.auxia.vellocell.grpc.ServerEvent>(4)
        r.register(1L, ch)
        r.send(1L, event("hello"))

        val received = ch.tryReceive().getOrNull()
        assertNotNull(received)
        assertTrue(received.content == "hello")
    }
}

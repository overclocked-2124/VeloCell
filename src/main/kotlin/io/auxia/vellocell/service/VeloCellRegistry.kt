package io.auxia.vellocell.service

import io.auxia.vellocell.grpc.ServerEvent
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class VeloCellRegistry {

    private val log = LoggerFactory.getLogger(VeloCellRegistry::class.java)

    // Maps userId → the bounded coroutine Channel feeding their gRPC response stream.
    private val sessions = ConcurrentHashMap<Long, Channel<ServerEvent>>()

    /**
     * Register a session. If the same user already has a session (e.g. they
     * reconnected from a second client without the first cleaning up), the
     * previous channel is closed so its consumer loop unblocks and tears down.
     */
    fun register(userId: Long, channel: Channel<ServerEvent>) {
        val previous = sessions.put(userId, channel)
        if (previous != null) {
            log.warn("User {} re-registered — closing stale session", userId)
            previous.close()
        }
        log.info("User {} connected — active sessions: {}", userId, sessions.size)
    }

    fun remove(userId: Long) {
        sessions.remove(userId)?.close()
        log.info("User {} disconnected — active sessions: {}", userId, sessions.size)
    }

    /**
     * Non-blocking delivery. If the user's bounded buffer is full (slow
     * consumer) or already closed, evict them and let the gRPC stream tear
     * down. Producers MUST NOT block on slow consumers — that would let one
     * laggy client stall the entire fan-out.
     */
    fun send(userId: Long, event: ServerEvent) {
        val channel = sessions[userId] ?: return
        val result = channel.trySend(event)
        if (!result.isSuccess) {
            log.warn("Drop+evict for user {} ({})", userId, result)
            sessions.remove(userId, channel)
            channel.close()
        }
    }
}

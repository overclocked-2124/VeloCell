package io.auxia.vellocell.service

import io.auxia.vellocell.entity.Message
import io.auxia.vellocell.entity.User
import io.auxia.vellocell.grpc.ClientEvent
import io.auxia.vellocell.repository.MembershipRepository
import io.auxia.vellocell.repository.MessageRepository
import io.auxia.vellocell.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ProcessedMessage(
    val message: Message,
    val sender: User,
    val memberIds: List<Long>
)

/**
 * Persistence side of the write-first pipeline.
 *
 * The @Transactional boundary makes the idempotency check + save atomic for the
 * common case. The very narrow race where two concurrent inserts of the same
 * message_id slip past existsById is handled by the DB unique-PK constraint;
 * the resulting DataIntegrityViolationException is caught at the call site in
 * VeloCellGrpcService.connectStream and treated as "already persisted by the
 * winning request".
 *
 * SECURITY: event.senderId is currently trusted from the wire. A real auth
 * layer must derive it from a verified principal (gRPC metadata interceptor)
 * before this code is exposed beyond a demo.
 */
@Service
@Transactional
class MessageProcessor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository
) {
    private val log = LoggerFactory.getLogger(MessageProcessor::class.java)

    /**
     * Returns null when the message is rejected for any reason: duplicate UUID,
     * unknown sender, or sender not a member of the target room.
     */
    fun process(event: ClientEvent): ProcessedMessage? {
        // Fast-path idempotency check.
        if (messageRepository.existsById(event.messageId)) {
            log.debug("Duplicate message {} — skipping", event.messageId)
            return null
        }

        // Validate sender BEFORE persisting so we don't write orphan rows.
        val sender = userRepository.findById(event.senderId).orElse(null) ?: run {
            log.warn("Reject {} — unknown sender {}", event.messageId, event.senderId)
            return null
        }

        // Authorization: sender must belong to the room. Existence of a
        // membership row also implies the room exists, so no separate check.
        if (!membershipRepository.existsByUserIdAndRoomId(event.senderId, event.roomId)) {
            log.warn("Reject {} — sender {} not a member of room {}",
                event.messageId, event.senderId, event.roomId)
            return null
        }

        val message = messageRepository.save(
            Message(
                id = event.messageId,
                roomId = event.roomId,
                senderId = event.senderId,
                content = event.content
            )
        )

        val memberIds = membershipRepository.findByRoomId(event.roomId).map { it.userId }
        return ProcessedMessage(message, sender, memberIds)
    }
}

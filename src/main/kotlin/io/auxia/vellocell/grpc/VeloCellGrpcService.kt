package io.auxia.vellocell.grpc

import io.auxia.vellocell.entity.Membership
import io.auxia.vellocell.entity.Room
import io.auxia.vellocell.entity.User
import io.auxia.vellocell.repository.MembershipRepository
import io.auxia.vellocell.repository.MessageRepository
import io.auxia.vellocell.repository.RoomRepository
import io.auxia.vellocell.repository.UserRepository
import io.auxia.vellocell.service.MessageProcessor
import io.auxia.vellocell.service.VeloCellRegistry
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException

// Bounded buffer per session. A slow consumer that fills this gets evicted
// rather than blocking producers — see VeloCellRegistry.send.
private const val SESSION_BUFFER = 256

@GrpcService
class VeloCellGrpcService(
    private val userRepository: UserRepository,
    private val roomRepository: RoomRepository,
    private val membershipRepository: MembershipRepository,
    private val messageRepository: MessageRepository,
    private val registry: VeloCellRegistry,
    private val messageProcessor: MessageProcessor
) : VeloCellServiceGrpcKt.VeloCellServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(VeloCellGrpcService::class.java)

    // ─── Unary RPCs ───────────────────────────────────────────────────────────

    override suspend fun getOrCreateUser(request: GetOrCreateUserRequest): GetOrCreateUserResponse =
        withContext(Dispatchers.IO) {
            // Two callers racing on the same username can both pass findByUsername
            // and both attempt save; the unique constraint guarantees only one wins.
            // The loser re-reads — the winner's row is now visible.
            val user = userRepository.findByUsername(request.username)
                ?: try {
                    userRepository.save(User(username = request.username))
                } catch (e: DataIntegrityViolationException) {
                    userRepository.findByUsername(request.username)
                        ?: throw StatusException(Status.INTERNAL.withDescription("user upsert race"))
                }
            getOrCreateUserResponse {
                userId = user.id
                username = user.username
            }
        }

    override suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse =
        withContext(Dispatchers.IO) {
            val room = roomRepository.findByName(request.name)
                ?: try {
                    roomRepository.save(Room(name = request.name))
                } catch (e: DataIntegrityViolationException) {
                    roomRepository.findByName(request.name)
                        ?: throw StatusException(Status.INTERNAL.withDescription("room upsert race"))
                }
            createRoomResponse {
                roomId = room.id
                name = room.name
            }
        }

    override suspend fun joinRoom(request: JoinRoomRequest): JoinRoomResponse =
        withContext(Dispatchers.IO) {
            if (!userRepository.existsById(request.userId))
                throw StatusException(Status.NOT_FOUND.withDescription("User ${request.userId} not found"))
            if (!roomRepository.existsById(request.roomId))
                throw StatusException(Status.NOT_FOUND.withDescription("Room ${request.roomId} not found"))

            if (membershipRepository.existsByUserIdAndRoomId(request.userId, request.roomId))
                return@withContext joinRoomResponse { success = true; message = "Already a member" }

            try {
                membershipRepository.save(Membership(userId = request.userId, roomId = request.roomId))
            } catch (e: DataIntegrityViolationException) {
                // Concurrent join with the same (user, room) — the unique
                // constraint won; treat as already a member.
                return@withContext joinRoomResponse { success = true; message = "Already a member" }
            }
            log.info("User {} joined room {}", request.userId, request.roomId)
            joinRoomResponse { success = true; message = "Joined successfully" }
        }

    /**
     * History is served via a dedicated unary call so the bidi stream is never
     * burdened with bulk data transfer (architectural guardrail).
     * Results are reversed so the client receives oldest-first.
     */
    override suspend fun getRoomHistory(request: GetHistoryRequest): GetHistoryResponse =
        withContext(Dispatchers.IO) {
            val messages = messageRepository.findTop50ByRoomIdOrderByTimestampDesc(request.roomId)
            val userMap = userRepository.findAllById(messages.map { it.senderId }.distinct())
                .associateBy { it.id }

            getHistoryResponse {
                this.messages += messages.reversed().map { msg ->
                    serverEvent {
                        messageId = msg.id
                        roomId = msg.roomId
                        senderId = msg.senderId
                        senderUsername = userMap[msg.senderId]?.username ?: "unknown"
                        content = msg.content
                        timestampMs = msg.timestamp.toEpochMilli()
                    }
                }
            }
        }

    // ─── Bidirectional Stream ─────────────────────────────────────────────────

    /**
     * Core real-time pipeline. Architecture:
     *  - incomingJob collects ClientEvents, persists via MessageProcessor, then
     *    fan-outs the resulting ServerEvent to every online room member via the registry.
     *  - The outer channelFlow loop drains the user's own outChannel and writes
     *    each event to their gRPC response stream.
     *
     * The first event from a client is a zero-content handshake — it registers
     * the stream in the registry without being persisted.
     *
     * SECURITY: event.senderId is taken from the wire. A real auth layer must
     * replace this with a value derived from authenticated metadata. See
     * MessageProcessor's class comment.
     */
    override fun connectStream(requests: Flow<ClientEvent>): Flow<ServerEvent> = channelFlow {
        var userId = -1L
        val outChannel = Channel<ServerEvent>(SESSION_BUFFER)

        coroutineScope {
            val incomingJob = launch {
                try {
                    requests.collect { event ->
                        if (userId == -1L) {
                            userId = event.senderId
                            registry.register(userId, outChannel)
                            log.info("ConnectStream opened for user {}", userId)
                        }
                        // Empty-content events are handshake signals — not persisted.
                        if (event.content.isBlank()) return@collect

                        val processed = withContext(Dispatchers.IO) {
                            try {
                                messageProcessor.process(event)
                            } catch (e: DataIntegrityViolationException) {
                                // Concurrent insert with the same UUID raced past
                                // existsById; the DB unique constraint rejected this
                                // one. The winner will broadcast — we just skip.
                                log.debug("Race-induced duplicate {}", event.messageId)
                                null
                            }
                        } ?: return@collect

                        val outEvent = serverEvent {
                            messageId = processed.message.id
                            roomId = processed.message.roomId
                            senderId = processed.message.senderId
                            senderUsername = processed.sender.username
                            content = processed.message.content
                            timestampMs = processed.message.timestamp.toEpochMilli()
                        }
                        processed.memberIds.forEach { memberId ->
                            registry.send(memberId, outEvent)
                        }
                    }
                } finally {
                    if (userId != -1L) registry.remove(userId)
                    outChannel.close()
                }
            }

            // Forward outgoing events from the registry channel to the gRPC stream.
            for (event in outChannel) {
                send(event)
            }

            incomingJob.join()
        }
    }
}

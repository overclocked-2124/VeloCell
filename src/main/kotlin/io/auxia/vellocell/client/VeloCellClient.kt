package io.auxia.vellocell.client

import io.auxia.vellocell.grpc.*
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: ./gradlew runClient -Pusername=<your_name>")
        return
    }
    val username = args[0]

    val grpcChannel = ManagedChannelBuilder.forAddress("localhost", 9090)
        .usePlaintext()
        .build()
    val stub = VeloCellServiceGrpcKt.VeloCellServiceCoroutineStub(grpcChannel)

    // Channel that bridges the blocking input loop into the outgoing gRPC Flow.
    val messageQueue = Channel<ClientEvent>(Channel.UNLIMITED)
    // Shared with the receive coroutine — needs atomic visibility.
    val currentRoomId = AtomicLong(-1L)

    runBlocking {
        // ── Step 1: identify this client ──────────────────────────────────────
        val me = stub.getOrCreateUser(getOrCreateUserRequest { this.username = username })
        val myUserId = me.userId
        println("[VeloCell] Logged in as '${me.username}' (id=$myUserId)")
        println("[VeloCell] Commands: /create <room>  /join <room>  /list")
        println()

        // ── Step 2: open bidi stream, attach receive coroutine ────────────────
        val serverFlow = stub.connectStream(
            flow { for (event in messageQueue) emit(event) }
        )

        // Send the handshake so the server registers us before the first message.
        messageQueue.send(clientEvent {
            messageId = UUID.randomUUID().toString()
            roomId = 0L
            senderId = myUserId
            content = ""
        })

        val receiveJob = launch(Dispatchers.IO) {
            try {
                serverFlow.collect { event ->
                    if (event.roomId == currentRoomId.get()) {
                        // \r clears any partial input on the line before printing.
                        print("\r[${event.senderUsername}]: ${event.content}\n> ")
                    }
                }
            } catch (e: CancellationException) {
                // Normal teardown when the input loop ends — stay quiet.
                throw e
            } catch (e: Exception) {
                println("\n[VeloCell] Stream closed: ${e.message}")
            }
        }

        // ── Step 3: blocking command loop (runs on the main coroutine) ────────
        while (isActive) {
            print("> ")
            val input = readLine()?.trim() ?: break

            when {
                input.startsWith("/create ") -> {
                    val roomName = input.removePrefix("/create ").trim()
                    val resp = stub.createRoom(createRoomRequest { name = roomName })
                    println("[VeloCell] Room '${resp.name}' ready (id=${resp.roomId})")
                }

                input.startsWith("/join ") -> {
                    val roomName = input.removePrefix("/join ").trim()
                    val roomResp = stub.createRoom(createRoomRequest { name = roomName })
                    val joinResp = stub.joinRoom(joinRoomRequest {
                        userId = myUserId
                        roomId = roomResp.roomId
                    })
                    if (joinResp.success) {
                        currentRoomId.set(roomResp.roomId)
                        println("[VeloCell] Joined '${roomName}'. ${joinResp.message}")
                    }
                }

                input == "/list" -> {
                    val roomId = currentRoomId.get()
                    if (roomId == -1L) {
                        println("[VeloCell] Join a room first with /join <name>")
                    } else {
                        val history = stub.getRoomHistory(getHistoryRequest { this.roomId = roomId })
                        println("─── History (${history.messagesList.size} messages) ───")
                        history.messagesList.forEach { println("[${it.senderUsername}]: ${it.content}") }
                        println("────────────────────────────────────")
                    }
                }

                input.isNotEmpty() -> {
                    val roomId = currentRoomId.get()
                    if (roomId == -1L) {
                        println("[VeloCell] Join a room first with /join <name>")
                    } else {
                        messageQueue.send(clientEvent {
                            messageId = UUID.randomUUID().toString()
                            this.roomId = roomId
                            senderId = myUserId
                            content = input
                        })
                    }
                }
            }
        }

        messageQueue.close()
        receiveJob.cancelAndJoin()
    }

    grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
}

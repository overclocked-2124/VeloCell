package io.auxia.vellocell.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "messages")
class Message(
    @Id
    val id: String, // Client-generated UUID — acts as the idempotency key

    @Column(name = "room_id", nullable = false)
    val roomId: Long,

    @Column(name = "sender_id", nullable = false)
    val senderId: Long,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(nullable = false)
    val timestamp: Instant = Instant.now()
)

package io.auxia.vellocell.repository

import io.auxia.vellocell.entity.Message
import org.springframework.data.jpa.repository.JpaRepository

interface MessageRepository : JpaRepository<Message, String> {
    fun findTop50ByRoomIdOrderByTimestampDesc(roomId: Long): List<Message>
}

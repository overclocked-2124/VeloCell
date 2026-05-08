package io.auxia.vellocell.repository

import io.auxia.vellocell.entity.Room
import org.springframework.data.jpa.repository.JpaRepository

interface RoomRepository : JpaRepository<Room, Long> {
    fun findByName(name: String): Room?
}

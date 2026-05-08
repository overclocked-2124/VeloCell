package io.auxia.vellocell.repository

import io.auxia.vellocell.entity.Membership
import org.springframework.data.jpa.repository.JpaRepository

interface MembershipRepository : JpaRepository<Membership, Long> {
    fun findByRoomId(roomId: Long): List<Membership>
    fun existsByUserIdAndRoomId(userId: Long, roomId: Long): Boolean
}

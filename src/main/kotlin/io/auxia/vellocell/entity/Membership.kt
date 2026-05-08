package io.auxia.vellocell.entity

import jakarta.persistence.*

@Entity
@Table(
    name = "memberships",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "room_id"])]
)
class Membership(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "room_id", nullable = false)
    val roomId: Long
)

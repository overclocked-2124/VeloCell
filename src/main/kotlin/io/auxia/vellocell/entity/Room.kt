package io.auxia.vellocell.entity

import jakarta.persistence.*

@Entity
@Table(name = "rooms")
class Room(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val name: String
)

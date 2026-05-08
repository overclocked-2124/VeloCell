package io.auxia.vellocell.service

import io.auxia.vellocell.entity.Membership
import io.auxia.vellocell.entity.Room
import io.auxia.vellocell.entity.User
import io.auxia.vellocell.grpc.clientEvent
import io.auxia.vellocell.repository.MembershipRepository
import io.auxia.vellocell.repository.MessageRepository
import io.auxia.vellocell.repository.RoomRepository
import io.auxia.vellocell.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class MessageProcessorTest @Autowired constructor(
    private val processor: MessageProcessor,
    private val users: UserRepository,
    private val rooms: RoomRepository,
    private val memberships: MembershipRepository,
    private val messages: MessageRepository
) {

    private var senderId: Long = 0
    private var outsiderId: Long = 0
    private var roomId: Long = 0

    @BeforeEach
    fun setUp() {
        messages.deleteAll()
        memberships.deleteAll()
        users.deleteAll()
        rooms.deleteAll()

        senderId = users.save(User(username = "alice")).id
        outsiderId = users.save(User(username = "mallory")).id
        roomId = rooms.save(Room(name = "general")).id
        memberships.save(Membership(userId = senderId, roomId = roomId))
    }

    @Test
    fun `happy path persists message and returns members`() {
        val event = clientEvent {
            messageId = UUID.randomUUID().toString()
            this.roomId = this@MessageProcessorTest.roomId
            this.senderId = this@MessageProcessorTest.senderId
            content = "hello"
        }

        val processed = processor.process(event)

        assertNotNull(processed)
        assertEquals("hello", processed.message.content)
        assertEquals(senderId, processed.sender.id)
        assertTrue(processed.memberIds.contains(senderId))
        assertTrue(messages.existsById(event.messageId))
    }

    @Test
    fun `duplicate message id is rejected and only one row is written`() {
        val mid = UUID.randomUUID().toString()
        val event = clientEvent {
            messageId = mid
            this.roomId = this@MessageProcessorTest.roomId
            this.senderId = this@MessageProcessorTest.senderId
            content = "first"
        }

        val first = processor.process(event)
        val second = processor.process(event.toBuilder().setContent("second").build())

        assertNotNull(first)
        assertNull(second, "duplicate UUID must be rejected")
        assertEquals(1, messages.count())
        // Original content wins — the duplicate didn't overwrite.
        assertEquals("first", messages.findById(mid).get().content)
    }

    @Test
    fun `unknown sender is rejected without writing a message`() {
        val event = clientEvent {
            messageId = UUID.randomUUID().toString()
            this.roomId = this@MessageProcessorTest.roomId
            senderId = 99_999L // does not exist
            content = "ghost"
        }

        val processed = processor.process(event)

        assertNull(processed)
        assertEquals(0, messages.count())
    }

    @Test
    fun `non-member is rejected without writing a message`() {
        val event = clientEvent {
            messageId = UUID.randomUUID().toString()
            this.roomId = this@MessageProcessorTest.roomId
            senderId = outsiderId // exists, but not a member of room
            content = "intruder"
        }

        val processed = processor.process(event)

        assertNull(processed)
        assertEquals(0, messages.count())
    }
}

package moe.reimu.ancsreceiver.ancs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NotificationEvent(
    val eventId: Byte,
    val eventFlags: Byte,
    val categoryId: Byte,
    val categoryCount: Byte,
    val uid: Int,
) {
    val isSilent
        get() = eventFlags.toInt().and(1.shl(0)) != 0

    val isImportant
        get() = eventFlags.toInt().and(1.shl(1)) != 0

    val isExisting
        get() = eventFlags.toInt().and(1.shl(2)) != 0

    val hasPositiveAction
        get() = eventFlags.toInt().and(1.shl(3)) != 0

    val hasNegativeAction
        get() = eventFlags.toInt().and(1.shl(4)) != 0

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(eventId)
        buffer.put(eventFlags)
        buffer.put(categoryId)
        buffer.put(categoryCount)
        buffer.putInt(uid)
        return buffer.array()
    }

    companion object {
        fun parse(data: ByteArray): NotificationEvent {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            return NotificationEvent(
                eventId = buffer.get(),
                eventFlags = buffer.get(),
                categoryId = buffer.get(),
                categoryCount = buffer.get(),
                uid = buffer.getInt()
            )
        }
    }
}

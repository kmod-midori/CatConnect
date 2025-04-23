package moe.reimu.ancsreceiver.ancs

import moe.reimu.ancsreceiver.ancs.AncsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

data class NotificationAttributeRequest(val uid: Int, val attributes: List<AttributeRequest>) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(1024)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0)
        buffer.putInt(uid)
        for (attribute in attributes) {
            attribute.writeToBuffer(buffer)
        }
        return Arrays.copyOf(buffer.array(), buffer.position())
    }

    companion object {
        fun parse(data: ByteArray): NotificationAttributeRequest {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.get() != 0.toByte()) {
                throw IllegalArgumentException("Invalid data format")
            }
            val uid = buffer.getInt()
            val attributes = mutableListOf<AttributeRequest>()

            while (buffer.hasRemaining()) {
                val id = buffer.get()
                val lengthLimit =
                    if (id == AncsConstants.NotificationAttributeIDTitle || id == AncsConstants.NotificationAttributeIDSubtitle || id == AncsConstants.NotificationAttributeIDMessage) {
                        buffer.getShort()
                    } else {
                        null
                    }
                attributes.add(
                    AttributeRequest(id, lengthLimit)
                )
            }

            return NotificationAttributeRequest(uid, attributes)
        }
    }
}
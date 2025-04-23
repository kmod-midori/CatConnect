package moe.reimu.ancsreceiver.ancs

import java.nio.ByteBuffer

data class AttributeRequest(val id: Byte, val lengthLimit: Short? = null) {
    fun writeToBuffer(buffer: ByteBuffer) {
        buffer.put(id)
        if (lengthLimit != null) {
            buffer.putShort(lengthLimit)
        }
    }
}

package moe.reimu.ancsreceiver.ancs

import java.nio.ByteBuffer

data class Attribute(val id: Byte, val value: String) {
    fun writeToBuffer(buffer: ByteBuffer) {
        val valueRaw = value.encodeToByteArray()
        val length = valueRaw.size
        buffer.put(id)
        buffer.putShort(length.toShort())
        buffer.put(valueRaw)
    }

    companion object {
        fun readFromBuffer(buffer: ByteBuffer): Attribute {
            val id = buffer.get()
            val length = buffer.short.toInt()
            val valueRaw = ByteArray(length)
            buffer.get(valueRaw)
            return Attribute(id, valueRaw.decodeToString())
        }
    }
}

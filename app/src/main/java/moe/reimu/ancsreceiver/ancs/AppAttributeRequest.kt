package moe.reimu.ancsreceiver.ancs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class AppAttributeRequest(appId: String) {
    private val command = ByteBuffer.allocateDirect(1024)

    init {
        command.order(ByteOrder.LITTLE_ENDIAN)
        command.put(1) // CommandIDGetAppAttributes
        command.put(appId.encodeToByteArray())
        command.put(0) // NULL
    }

    fun addAttribute(id: Byte) {
        command.put(id)
    }

    fun toByteArray(): ByteArray {
        return Arrays.copyOfRange(
            command.array(),
            command.arrayOffset(),
            command.arrayOffset() + command.position()
        )
    }
}
package moe.reimu.ancsreceiver.ams

data class EntityUpdateNotification(val entityId: Byte, val attributeId: Byte, val entityUpdateFlags: Byte, val value: String) {
    companion object {
        fun parse(data: ByteArray) : EntityUpdateNotification {
            if (data.size < 3) {
                throw IllegalArgumentException("Data size is too small")
            }
            val entityId = data[0]
            val attributeId = data[1]
            val entityUpdateFlags = data[2]
            val value = String(data, 3, data.size - 3)
            return EntityUpdateNotification(entityId, attributeId, entityUpdateFlags, value)
        }
    }
}
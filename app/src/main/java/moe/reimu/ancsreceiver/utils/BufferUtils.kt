package moe.reimu.ancsreceiver.utils

import java.nio.ByteBuffer

fun readTlvAsMap(buffer: ByteBuffer): Map<Byte, String> {
    val ret = mutableMapOf<Byte, String>()
    while (buffer.hasRemaining()) {
        val aid = buffer.get()
        val alen = buffer.getShort().toInt()
        val adata = ByteArray(alen)
        buffer.get(adata)
        ret[aid] = adata.decodeToString()
    }
    return ret
}

fun readNullTerminatedString(buffer: ByteBuffer): String {
    val raw = mutableListOf<Byte>()
    while (buffer.hasRemaining()) {
        val b = buffer.get()
        if (b == 0.toByte()) break
        raw.add(b)
    }
    return raw.toByteArray().decodeToString()
}
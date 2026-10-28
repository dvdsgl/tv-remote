package xyz.dvdsgl.tvremote.companion

import java.io.ByteArrayOutputStream

/** HAP TLV8 (pyatv auth/hap_tlv8.py). Values over 255 bytes are split into repeated tags. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11

    fun write(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            while (pos < value.size) {
                val n = minOf(255, value.size - pos)
                out.write(tag)
                out.write(n)
                out.write(value, pos, n)
                pos += n
            }
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 2 <= data.size) {
            val tag = data[pos].toInt() and 0xFF
            val n = data[pos + 1].toInt() and 0xFF
            require(pos + 2 + n <= data.size) { "truncated TLV8" }
            val value = data.copyOfRange(pos + 2, pos + 2 + n)
            out[tag] = out[tag]?.plus(value) ?: value
            pos += 2 + n
        }
        return out
    }
}

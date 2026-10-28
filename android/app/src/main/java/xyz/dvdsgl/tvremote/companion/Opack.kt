package xyz.dvdsgl.tvremote.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * OPACK, Apple's binary serialization used by the Companion protocol. Ported from pyatv's
 * support/opack.py, including its quirks, so packed bytes match pyatv exactly: repeated
 * values (anything longer than one byte) become back-references, containers count as
 * values when packing but not when unpacking, and absolute time unpacks as an integer.
 *
 * Kotlin types: null, Boolean, Long (Int is accepted when packing), Double (Float when a
 * 32-bit float is unpacked), String, ByteArray, UUID, List, Map (insertion ordered).
 * Negative Longs pack as unsigned 64-bit values (the session id can use the top bit).
 */
object Opack {
    fun pack(value: Any?): ByteArray = pack(value, ArrayList())

    fun unpack(data: ByteArray): Any? = Reader(data).read()

    private fun pack(value: Any?, objects: MutableList<ByteArray>): ByteArray {
        var bytes = when (value) {
            null -> byteArrayOf(0x04)
            is Boolean -> byteArrayOf(if (value) 0x01 else 0x02)
            is UUID -> ByteBuffer.allocate(17).put(0x05).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
            is Int -> packInt(value.toLong())
            is Long -> packInt(value)
            is Float -> packDouble(value.toDouble())
            is Double -> packDouble(value)
            is String -> sized(0x40, 0x61, 0x20, value.toByteArray(Charsets.UTF_8))
            is ByteArray -> sized(0x70, 0x91, 0x20, value, bytesLength = true)
            is List<*> -> container(0xD0, value.size, value.map { pack(it, objects) })
            is Map<*, *> -> container(0xE0, value.size, value.entries.flatMap { listOf(pack(it.key, objects), pack(it.value, objects)) })
            else -> throw IllegalArgumentException("can't pack ${value::class.java}")
        }
        val index = objects.indexOfFirst { it.contentEquals(bytes) }
        if (index >= 0) {
            bytes = when {
                index < 0x21 -> byteArrayOf((0xA0 + index).toByte())
                index <= 0xFF -> byteArrayOf(0xC1.toByte()) + le(index.toLong(), 1)
                index <= 0xFFFF -> byteArrayOf(0xC2.toByte()) + le(index.toLong(), 2)
                else -> byteArrayOf(0xC3.toByte()) + le(index.toLong(), 4)
            }
        } else if (bytes.size > 1) {
            objects.add(bytes)
        }
        return bytes
    }

    private fun packInt(v: Long): ByteArray = when {
        v < 0 -> byteArrayOf(0x33) + le(v, 8)
        v < 0x28 -> byteArrayOf((v + 8).toByte())
        v <= 0xFF -> byteArrayOf(0x30) + le(v, 1)
        v <= 0xFFFF -> byteArrayOf(0x31) + le(v, 2)
        v <= 0xFFFFFFFFL -> byteArrayOf(0x32) + le(v, 4)
        else -> byteArrayOf(0x33) + le(v, 8)
    }

    private fun packDouble(v: Double) =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put(0x36).putDouble(v).array()

    /** Strings and data: short form up to 32 bytes, then 1, 2, 3/4 or 4/8 length bytes. */
    private fun sized(short: Int, long: Int, maxShort: Int, data: ByteArray, bytesLength: Boolean = false): ByteArray {
        val n = data.size.toLong()
        val head = when {
            n <= maxShort -> byteArrayOf((short + n).toByte())
            n <= 0xFF -> byteArrayOf(long.toByte()) + le(n, 1)
            n <= 0xFFFF -> byteArrayOf((long + 1).toByte()) + le(n, 2)
            !bytesLength && n <= 0xFFFFFF -> byteArrayOf((long + 2).toByte()) + le(n, 3)
            bytesLength -> byteArrayOf((long + 2).toByte()) + le(n, 4)
            else -> byteArrayOf((long + 3).toByte()) + le(n, 4)
        }
        return head + data
    }

    private fun container(base: Int, count: Int, parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(base + minOf(count, 0xF))
        parts.forEach { out.write(it) }
        if (count >= 0xF) out.write(0x03)
        return out.toByteArray()
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        private val objects = ArrayList<Any?>()

        fun read(): Any? {
            val tag = u8()
            var keep = true
            val value: Any? = when {
                tag == 0x01 -> { keep = false; true }
                tag == 0x02 -> { keep = false; false }
                tag == 0x04 -> { keep = false; null }
                tag == 0x05 -> { val b = ByteBuffer.wrap(take(16)); UUID(b.long, b.long) }
                tag == 0x06 -> int(8) // absolute time, kept as an integer like pyatv
                tag in 0x08..0x2F -> { keep = false; (tag - 8).toLong() }
                tag == 0x35 -> ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).float
                tag == 0x36 -> ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).double
                (tag and 0xF0) == 0x30 -> int(1 shl (tag and 0xF))
                tag in 0x40..0x60 -> String(take(tag - 0x40), Charsets.UTF_8)
                tag in 0x61..0x64 -> String(take(int(tag and 0xF).toInt()), Charsets.UTF_8)
                tag in 0x70..0x90 -> take(tag - 0x70)
                tag in 0x91..0x94 -> take(int(1 shl ((tag and 0xF) - 1)).toInt())
                (tag and 0xF0) == 0xD0 -> {
                    keep = false
                    val out = ArrayList<Any?>()
                    if ((tag and 0xF) == 0xF) {
                        while (peek() != 0x03) out.add(read())
                        pos++
                    } else repeat(tag and 0xF) { out.add(read()) }
                    out
                }
                (tag and 0xE0) == 0xE0 -> {
                    keep = false
                    val out = LinkedHashMap<Any?, Any?>()
                    if ((tag and 0xF) == 0xF) {
                        while (peek() != 0x03) { val k = read(); out[k] = read() }
                        pos++
                    } else repeat(tag and 0xF) { val k = read(); out[k] = read() }
                    out
                }
                tag in 0xA0..0xC0 -> objects[tag - 0xA0]
                tag in 0xC1..0xC4 -> objects[int(tag - 0xC0).toInt()]
                else -> throw IllegalArgumentException("unknown OPACK tag 0x${tag.toString(16)}")
            }
            if (keep && objects.none { same(it, value) }) objects.add(value)
            return value
        }

        private fun same(a: Any?, b: Any?) = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
        private fun u8() = data[pos++].toInt() and 0xFF
        private fun peek() = data[pos].toInt() and 0xFF
        private fun take(n: Int): ByteArray {
            require(n >= 0 && pos + n <= data.size) { "truncated OPACK data" }
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }
        private fun int(n: Int): Long {
            val b = take(n)
            var v = 0L
            for (i in minOf(n, 8) - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
    }
}

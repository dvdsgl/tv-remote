package xyz.dvdsgl.tvremote.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Just enough binary plist ("bplist00") for the Companion keyboard, which wraps its text
 * session in NSKeyedArchiver archives. The writer lays objects out like Python's plistlib
 * (which pyatv uses), so the RTI payloads match pyatv byte for byte.
 *
 * Kotlin types: null, Boolean, Long, Double, String, ByteArray, [Uid], List, Map<String, *>.
 */
object Bplist {
    /** A keyed-archiver object reference (plist UID). Compared by identity, as in plistlib. */
    class Uid(val value: Int)

    fun read(data: ByteArray): Any? {
        require(data.size >= 40 && String(data, 0, 8, Charsets.US_ASCII) == "bplist00") { "not a binary plist" }
        val trailer = ByteBuffer.wrap(data, data.size - 32, 32)
        trailer.position(trailer.position() + 6)
        val offsetSize = trailer.get().toInt()
        val refSize = trailer.get().toInt()
        val count = trailer.long.toInt()
        val top = trailer.long.toInt()
        val tableAt = trailer.long.toInt()
        val offsets = IntArray(count) { uint(data, tableAt + it * offsetSize, offsetSize).toInt() }
        return Reader(data, offsets, refSize).obj(top, 0)
    }

    private class Reader(val data: ByteArray, val offsets: IntArray, val refSize: Int) {
        fun obj(ref: Int, depth: Int): Any? {
            require(depth < 64) { "plist too deep" }
            var pos = offsets[ref]
            val marker = data[pos++].toInt() and 0xFF
            val info = marker and 0xF
            fun length(): Int {
                if (info != 0xF) return info
                val m = data[pos++].toInt() and 0xF
                val n = 1 shl m
                return uint(data, pos, n).toInt().also { pos += n }
            }
            return when (marker shr 4) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> (1 shl info).let { n -> if (n == 16) uint(data, pos + 8, 8) else uint(data, pos, n) }
                0x2 -> if (info == 2) ByteBuffer.wrap(data, pos, 4).float.toDouble() else ByteBuffer.wrap(data, pos, 8).double
                0x3 -> ByteBuffer.wrap(data, pos, 8).double // date, seconds since 2001
                0x4 -> { val n = length(); data.copyOfRange(pos, pos + n) }
                0x5 -> { val n = length(); String(data, pos, n, Charsets.US_ASCII) }
                0x6 -> { val n = length(); String(data, pos, 2 * n, Charsets.UTF_16BE) }
                0x8 -> Uid(uint(data, pos, info + 1).toInt())
                0xA, 0xC -> {
                    val n = length()
                    List(n) { obj(uint(data, pos + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val n = length()
                    val out = LinkedHashMap<String, Any?>()
                    for (i in 0 until n) {
                        val k = obj(uint(data, pos + i * refSize, refSize).toInt(), depth + 1)
                        out[k.toString()] = obj(uint(data, pos + (n + i) * refSize, refSize).toInt(), depth + 1)
                    }
                    out
                }
                else -> throw IllegalArgumentException("unsupported plist marker 0x${marker.toString(16)}")
            }
        }
    }

    private fun uint(data: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        return v
    }

    fun write(root: Any?): ByteArray = Writer().write(root)

    private class Writer {
        private val objects = ArrayList<Any?>()
        private val scalars = HashMap<Any, Int>()
        private val containers = java.util.IdentityHashMap<Any, Int>()
        private val out = ByteArrayOutputStream()
        private var refSize = 1

        // Strings, numbers and data are shared by value; containers and UIDs by identity.
        private fun key(v: Any?): Any? = when (v) {
            null, is Uid, is List<*>, is Map<*, *> -> null
            is ByteArray -> "b:" + v.hex()
            is Int -> "n:$v"
            is Long -> "n:$v"
            else -> v::class.java.name + ":" + v
        }

        private fun flatten(v: Any?) {
            val k = key(v)
            if (k != null) { if (k in scalars) return } else if (v != null && v in containers) return
            val ref = objects.size
            objects.add(v)
            if (k != null) scalars[k] = ref else if (v != null) containers[v] = ref
            when (v) {
                is Map<*, *> -> { v.keys.forEach(::flatten); v.values.forEach(::flatten) }
                is List<*> -> v.forEach(::flatten)
            }
        }

        private fun ref(v: Any?): Int = key(v)?.let { scalars[it] } ?: containers[v]!!

        fun write(root: Any?): ByteArray {
            require(root != null)
            flatten(root)
            refSize = sizeFor(objects.size.toLong())
            out.write("bplist00".toByteArray())
            val offsets = LongArray(objects.size)
            for ((i, v) in objects.withIndex()) {
                offsets[i] = out.size().toLong()
                obj(v)
            }
            val tableAt = out.size().toLong()
            val offsetSize = sizeFor(tableAt)
            offsets.forEach { be(it, offsetSize) }
            out.write(ByteArray(6))
            out.write(offsetSize)
            out.write(refSize)
            be(objects.size.toLong(), 8)
            be(ref(root).toLong(), 8)
            be(tableAt, 8)
            return out.toByteArray()
        }

        private fun obj(v: Any?) {
            when (v) {
                null -> out.write(0x00)
                false -> out.write(0x08)
                true -> out.write(0x09)
                is Int, is Long -> {
                    val n = (v as Number).toLong()
                    when {
                        n < 0 -> { out.write(0x13); be(n, 8) }
                        n < 1L shl 8 -> { out.write(0x10); be(n, 1) }
                        n < 1L shl 16 -> { out.write(0x11); be(n, 2) }
                        n < 1L shl 32 -> { out.write(0x12); be(n, 4) }
                        else -> { out.write(0x13); be(n, 8) }
                    }
                }
                is Double -> { out.write(0x23); be(java.lang.Double.doubleToLongBits(v), 8) }
                is ByteArray -> { size(0x40, v.size); out.write(v) }
                is String -> {
                    if (v.all { it.code < 128 }) {
                        size(0x50, v.length); out.write(v.toByteArray(Charsets.US_ASCII))
                    } else {
                        val b = v.toByteArray(Charsets.UTF_16BE)
                        size(0x60, b.size / 2); out.write(b)
                    }
                }
                is Uid -> when {
                    v.value < 1 shl 8 -> { out.write(0x80); be(v.value.toLong(), 1) }
                    v.value < 1 shl 16 -> { out.write(0x81); be(v.value.toLong(), 2) }
                    else -> { out.write(0x83); be(v.value.toLong(), 4) }
                }
                is List<*> -> { size(0xA0, v.size); v.forEach { be(ref(it).toLong(), refSize) } }
                is Map<*, *> -> {
                    size(0xD0, v.size)
                    v.keys.forEach { be(ref(it).toLong(), refSize) }
                    v.values.forEach { be(ref(it).toLong(), refSize) }
                }
                else -> throw IllegalArgumentException("can't write ${v::class.java}")
            }
        }

        private fun size(token: Int, n: Int) {
            when {
                n < 15 -> out.write(token or n)
                n < 1 shl 8 -> { out.write(token or 0xF); out.write(0x10); be(n.toLong(), 1) }
                n < 1 shl 16 -> { out.write(token or 0xF); out.write(0x11); be(n.toLong(), 2) }
                else -> { out.write(token or 0xF); out.write(0x12); be(n.toLong(), 4) }
            }
        }

        private fun be(v: Long, n: Int) {
            for (i in n - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
        }

        private fun sizeFor(count: Long) = when {
            count < 1L shl 8 -> 1
            count < 1L shl 16 -> 2
            count < 1L shl 32 -> 4
            else -> 8
        }
    }

    /**
     * Follows a key path through an NSKeyedArchiver archive from "$top", resolving UIDs,
     * like pyatv's keyed_archiver.read_archive_properties. Null if any step is missing.
     */
    fun archiveValue(archive: ByteArray, vararg path: String): Any? {
        val root = read(archive) as? Map<*, *> ?: return null
        val objects = root["\$objects"] as? List<*> ?: return null
        var element: Any? = root["\$top"]
        for (key in path) {
            element = (element as? Map<*, *>)?.get(key) ?: return null
            if (element is Uid) element = objects.getOrNull(element.value) ?: return null
        }
        return element
    }

    private fun classInfo(name: String) = linkedMapOf("\$classname" to name, "\$classes" to listOf(name, "NSObject"))

    /** RTI payload that inserts [text] into the TV's focused field (pyatv get_rti_input_text_payload). */
    fun rtiInputText(sessionUuid: ByteArray, text: String): ByteArray = write(linkedMapOf(
        "\$version" to 100000L,
        "\$archiver" to "RTIKeyedArchiver",
        "\$top" to linkedMapOf("textOperations" to Uid(1)),
        "\$objects" to listOf(
            "\$null",
            linkedMapOf("keyboardOutput" to Uid(2), "\$class" to Uid(7), "targetSessionUUID" to Uid(5)),
            linkedMapOf("insertionText" to Uid(3), "\$class" to Uid(4)),
            text,
            classInfo("TIKeyboardOutput"),
            linkedMapOf("NS.uuidbytes" to sessionUuid, "\$class" to Uid(6)),
            classInfo("NSUUID"),
            classInfo("RTITextOperations"),
        ),
    ))

    /** RTI payload that clears the TV's focused field (pyatv get_rti_clear_text_payload). */
    fun rtiClearText(sessionUuid: ByteArray): ByteArray = write(linkedMapOf(
        "\$version" to 100000L,
        "\$archiver" to "RTIKeyedArchiver",
        "\$top" to linkedMapOf("textOperations" to Uid(1)),
        "\$objects" to listOf(
            "\$null",
            linkedMapOf("\$class" to Uid(7), "targetSessionUUID" to Uid(5), "keyboardOutput" to Uid(2), "textToAssert" to Uid(4)),
            linkedMapOf("\$class" to Uid(3)),
            classInfo("TIKeyboardOutput"),
            "",
            linkedMapOf("NS.uuidbytes" to sessionUuid, "\$class" to Uid(6)),
            classInfo("NSUUID"),
            classInfo("RTITextOperations"),
        ),
    ))
}

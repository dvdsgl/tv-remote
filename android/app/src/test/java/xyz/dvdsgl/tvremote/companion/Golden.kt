package xyz.dvdsgl.tvremote.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.math.BigInteger
import java.util.UUID

/** Vectors from tools/golden.py (pyatv 0.18.0), in src/test/resources/companion_golden.json. */
object Golden {
    val json: JSONObject by lazy {
        JSONObject(Golden::class.java.getResource("/companion_golden.json")!!.readText())
    }

    fun bytes(hex: String) = hex.unhex()

    /** Rebuilds a value from tools/golden.py's typed JSON. */
    fun value(t: JSONObject): Any? = when (t.getString("t")) {
        "null" -> null
        "bool" -> t.getBoolean("v")
        "uuid" -> UUID.fromString(t.getString("v"))
        "int" -> BigInteger(t.getString("v")).toLong()
        "float" -> t.getDouble("v")
        "str" -> t.getString("v")
        "bytes" -> bytes(t.getString("v"))
        "list" -> t.getJSONArray("v").let { a -> List(a.length()) { value(a.getJSONObject(it)) } }
        "dict" -> LinkedHashMap<Any?, Any?>().also { m ->
            val a = t.getJSONArray("v")
            for (i in 0 until a.length()) {
                val kv = a.getJSONArray(i)
                m[value(kv.getJSONObject(0))] = value(kv.getJSONObject(1))
            }
        }
        else -> error("unknown type")
    }

    /** Deep equality across ByteArrays, ordered maps, and Float vs Double. */
    fun same(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size &&
            a.entries.zip(b.entries).all { (x, y) -> same(x.key, y.key) && same(x.value, y.value) }
        a is List<*> && b is List<*> -> a.size == b.size && a.zip(b).all { (x, y) -> same(x, y) }
        a is Float || b is Float -> a is Number && b is Number && a.toDouble() == b.toDouble()
        else -> a == b
    }

    fun assertSame(message: String, expected: Any?, actual: Any?) =
        assertTrue("$message: expected $expected, got $actual", same(expected, actual))

    fun objects(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }
}

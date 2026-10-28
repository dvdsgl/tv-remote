package xyz.dvdsgl.tvremote.companion

import java.math.BigInteger

/**
 * X25519 (RFC 7748, section 5) and Ed25519 (RFC 8032, section 6 reference code), written
 * with BigInteger. Android 13's providers don't document importing raw 32-byte X25519 or
 * Ed25519 keys (Ed25519 signing isn't listed at all), so these are done here instead.
 * They are slow next to native code (a few milliseconds per operation) and not constant
 * time, which is fine for a handful of handshake operations per connection on a home LAN.
 */
private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
private val TWO = BigInteger.valueOf(2)

private fun inv(x: BigInteger): BigInteger = x.modPow(P - TWO, P)

private fun leInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

private fun leBytes(v: BigInteger, n: Int = 32): ByteArray {
    val be = v.toByteArray()
    val out = ByteArray(n)
    for (i in 0 until minOf(n, be.size)) out[i] = be[be.size - 1 - i]
    return out
}

object X25519 {
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun publicKey(privateKey: ByteArray) = scalarMult(privateKey, BASE)

    /** The shared secret; throws if the peer's key is a low-order point (all-zero result). */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val out = scalarMult(privateKey, peerPublic)
        require(out.any { it.toInt() != 0 }) { "bad X25519 public key" }
        return out
    }

    private fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kn = leInt(k)
        val uc = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = leInt(uc).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (kn.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { x2 = x3.also { x3 = x2 }; z2 = z3.also { z3 = z2 } }
            swap = kt
            val a = (x2 + z2).mod(P); val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P); val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P); val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P); val cb = (c * b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = (x1 * (da - cb).pow(2)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e)).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return leBytes((x2 * inv(z2)).mod(P))
    }
}

object Ed25519 {
    private val D: BigInteger = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
    private val Q: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)

    /** A point in extended coordinates (X, Y, Z, T). */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val G: Point = run {
        val y = (BigInteger.valueOf(4) * inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0)!!
        Point(x, y, BigInteger.ONE, (x * y).mod(P))
    }
    private val ZERO_POINT = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    fun publicKey(seed: ByteArray): ByteArray = compress(mul(expand(seed).first, G))

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val (a, prefix) = expand(seed)
        val pub = compress(mul(a, G))
        val r = hashModQ(prefix, message)
        val rs = compress(mul(r, G))
        val h = hashModQ(rs, pub, message)
        val s = (r + h * a).mod(Q)
        return rs + leBytes(s)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rs = signature.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        val s = leInt(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val h = hashModQ(rs, publicKey, message)
        return equal(mul(s, G), add(r, mul(h, a)))
    }

    private fun expand(seed: ByteArray): Pair<BigInteger, ByteArray> {
        require(seed.size == 32)
        val h = Crypto.sha512(seed)
        var a = leInt(h.copyOfRange(0, 32))
        a = a.and(BigInteger.ONE.shiftLeft(254) - BigInteger.valueOf(8)).or(BigInteger.ONE.shiftLeft(254))
        return a to h.copyOfRange(32, 64)
    }

    private fun hashModQ(vararg parts: ByteArray) = leInt(Crypto.sha512(*parts)).mod(Q)

    private fun add(p: Point, q: Point): Point {
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (TWO * p.t * q.t * D).mod(P)
        val d = (TWO * p.z * q.z).mod(P)
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun mul(s: BigInteger, p: Point): Point {
        var q = ZERO_POINT
        var base = p
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) q = add(q, base)
            base = add(base, base)
        }
        return q
    }

    private fun equal(p: Point, q: Point) =
        (p.x * q.z - q.x * p.z).mod(P).signum() == 0 && (p.y * q.z - q.y * p.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * inv(D * y * y + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * SQRT_M1).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P - x
        return x
    }

    private fun compress(p: Point): ByteArray {
        val zi = inv(p.z)
        val x = (p.x * zi).mod(P)
        val y = (p.y * zi).mod(P)
        return leBytes(if (x.testBit(0)) y.setBit(255) else y)
    }

    private fun decompress(s: ByteArray): Point? {
        var y = leInt(s)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, (x * y).mod(P))
    }
}

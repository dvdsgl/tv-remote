package xyz.dvdsgl.tvremote.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import javax.crypto.KeyAgreement

/** The pure-Kotlin X25519 and Ed25519 against the JDK's own implementations, on random keys. */
class CurveCrossCheckTest {
    private fun le(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        return ByteArray(32) { if (it < be.size) be[be.size - 1 - it] else 0 }
    }

    @Test fun x25519MatchesJdk() {
        val gen = KeyPairGenerator.getInstance("X25519")
        repeat(20) {
            val a = gen.generateKeyPair()
            val b = gen.generateKeyPair()
            val aPriv = (a.private as XECPrivateKey).scalar.get()
            val bPub = le((b.public as XECPublicKey).u)
            assertArrayEquals(le((a.public as XECPublicKey).u), X25519.publicKey(aPriv))
            val ka = KeyAgreement.getInstance("X25519").apply { init(a.private); doPhase(b.public, true) }
            assertArrayEquals(ka.generateSecret(), X25519.sharedSecret(aPriv, bPub))
        }
    }

    @Test fun ed25519MatchesJdk() {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        repeat(20) { i ->
            val pair = gen.generateKeyPair()
            val seed = (pair.private as EdECPrivateKey).bytes.get()
            val point = (pair.public as EdECPublicKey).point
            val pub = le(point.y).also { if (point.isXOdd) it[31] = (it[31].toInt() or 0x80).toByte() }
            assertArrayEquals(pub, Ed25519.publicKey(seed))
            val msg = Crypto.random(i * 13)
            val sig = Signature.getInstance("Ed25519").apply { initSign(pair.private); update(msg) }.sign()
            assertArrayEquals(sig, Ed25519.sign(seed, msg))
            assertTrue(Ed25519.verify(pub, msg, sig))
        }
    }
}

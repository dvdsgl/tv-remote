package xyz.dvdsgl.tvremote.companion

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Platform primitives: SHA-512, HMAC-SHA512 (for HKDF) and ChaCha20-Poly1305 all come from
 * the JCA provider (Conscrypt on Android, which has had ChaCha20-Poly1305 since API 28;
 * SunJCE in JVM tests). X25519 and Ed25519 are in Curve25519.kt.
 */
object Crypto {
    private val random = SecureRandom()

    fun random(n: Int) = ByteArray(n).also(random::nextBytes)

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach(md::update)
        return md.digest()
    }

    /** HKDF-SHA512 with a 32-byte output, as pyatv's hkdf_expand. An empty salt means 64 zero bytes. */
    fun hkdf(salt: String, info: String, secret: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA512")
        val s = salt.toByteArray().takeIf { it.isNotEmpty() } ?: ByteArray(64)
        extract.init(SecretKeySpec(s, "HmacSHA512"))
        val prk = extract.doFinal(secret)
        val expand = Mac.getInstance("HmacSHA512")
        expand.init(SecretKeySpec(prk, "HmacSHA512"))
        expand.update(info.toByteArray())
        expand.update(1.toByte())
        return expand.doFinal().copyOf(32)
    }

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    /** Throws javax.crypto.AEADBadTagException if the tag doesn't match. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)

    /** A nonce for a pairing message label such as "PS-Msg05": four zero bytes, then the label. */
    fun labelNonce(label: String) = ByteArray(4) + label.toByteArray()

    // Android names the cipher "ChaCha20/Poly1305/NoPadding"; the JDK calls it "ChaCha20-Poly1305".
    private val transformation: String by lazy {
        listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305").first {
            try { Cipher.getInstance(it); true } catch (_: Exception) { false }
        }
    }

    // A fresh Cipher per message: the JDK refuses to re-init one with the same key and nonce.
    private fun chacha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?): Cipher {
        require(nonce.size == 12)
        return Cipher.getInstance(transformation).apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad != null && aad.isNotEmpty()) updateAAD(aad)
        }
    }
}

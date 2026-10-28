package xyz.dvdsgl.tvremote.companion

import java.math.BigInteger

/**
 * SRP-6a client for HAP pair-setup: 3072-bit group from RFC 5054, generator 5, SHA-512,
 * user "Pair-Setup". Mirrors srptools 1.0.1 as pyatv drives it, including how it turns
 * numbers into bytes: k and u hash padded values, everything else hashes the minimal
 * big-endian bytes (so a hash with a leading zero byte loses it when hashed again).
 *
 * [privateKey] is the client secret a; pyatv uses the new Ed25519 seed for it.
 */
class Srp(private val pin: String, privateKey: ByteArray) {
    private val a = BigInteger(1, privateKey)
    private val publicA: BigInteger = G.modPow(a, N)

    /** A, as sent in M3. */
    val publicKey: ByteArray = minimal(publicA)
    /** Session key K = H(S), set by [process]. */
    lateinit var sessionKey: ByteArray
        private set
    /** Client proof M, set by [process]. */
    lateinit var proof: ByteArray
        private set
    /** The proof the accessory should send in M4: H(A | M | K). */
    lateinit var expectedServerProof: ByteArray
        private set

    fun process(serverPublic: ByteArray, salt: ByteArray) {
        val b = BigInteger(1, serverPublic)
        require(b.mod(N).signum() != 0) { "bad SRP public key" }
        val u = BigInteger(1, Crypto.sha512(pad(publicA), pad(b)))
        val x = BigInteger(1, Crypto.sha512(salt, Crypto.sha512("$USER:$pin".toByteArray())))
        val v = G.modPow(x, N)
        val s = (b - K * v).mod(N).modPow(a + u * x, N)
        sessionKey = Crypto.sha512(minimal(s))
        val hn = BigInteger(1, Crypto.sha512(minimal(N)))
        val hg = BigInteger(1, Crypto.sha512(minimal(G)))
        val hu = BigInteger(1, Crypto.sha512(USER.toByteArray()))
        proof = Crypto.sha512(minimal(hn.xor(hg)), minimal(hu), salt, minimal(publicA), minimal(b), sessionKey)
        expectedServerProof = Crypto.sha512(minimal(publicA), proof, sessionKey)
    }

    companion object {
        private const val USER = "Pair-Setup"
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16)
        private val G = BigInteger.valueOf(5)
        private val K = BigInteger(1, Crypto.sha512(minimal(N), pad(G)))

        private fun pad(v: BigInteger): ByteArray {
            val m = minimal(v)
            return ByteArray(384 - m.size) + m
        }

        /** Big-endian bytes without leading zeros (srptools' int_to_bytes; zero is one zero byte). */
        fun minimal(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            return b.copyOfRange(i, b.size)
        }
    }
}

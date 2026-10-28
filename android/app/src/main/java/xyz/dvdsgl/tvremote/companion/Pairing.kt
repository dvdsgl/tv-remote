package xyz.dvdsgl.tvremote.companion

import java.util.UUID

/** Thrown when the TV rejects or fails pairing or pair-verify. */
class PairingException(message: String) : Exception(message)

/**
 * Long-term pairing keys, stored the way pyatv stores them (HapCredentials.__str__):
 * hex ltpk:ltsk:atv_id:client_id. ltpk is the TV's Ed25519 public key, ltsk our Ed25519
 * seed, atv_id the TV's pairing identifier and client_id ours.
 */
class Credentials(val ltpk: ByteArray, val ltsk: ByteArray, val atvId: ByteArray, val clientId: ByteArray) {
    override fun toString() = listOf(ltpk, ltsk, atvId, clientId).joinToString(":") { it.hex() }

    companion object {
        fun parse(s: String): Credentials {
            val parts = s.split(":")
            if (parts.size != 4 || parts.any { it.isEmpty() }) throw IllegalArgumentException("invalid credentials")
            val (ltpk, ltsk, atvId, clientId) = parts.map { it.unhex() }
            return Credentials(ltpk, ltsk, atvId, clientId)
        }
    }
}

/** Throws if a pairing reply carries a TLV error (wrong PIN, back-off, busy, ...). */
internal fun checkTlv(tlv: Map<Int, ByteArray>): Map<Int, ByteArray> {
    val error = tlv[Tlv8.ERROR] ?: return tlv
    val code = error.firstOrNull()?.toInt() ?: 0
    throw PairingException(when (code) {
        2 -> "Wrong code"
        3 -> "The TV is refusing pairing for now (too many tries). Wait a minute and try again."
        4 -> "The TV has too many paired devices"
        5 -> "Too many tries"
        6 -> "Pairing isn't available on the TV right now"
        7 -> "The TV is busy pairing with something else"
        else -> "Pairing failed (error $code)"
    })
}

/**
 * HAP pair-setup M1 to M6 for Companion (pyatv companion/auth.py and auth/hap_srp.py).
 * The TV shows a 4-digit PIN after M1. [seed] becomes both our long-term Ed25519 key and
 * the SRP secret, as in pyatv; [pairingId] is a UUID string, sent as ASCII.
 */
class PairSetup(
    private val seed: ByteArray = Crypto.random(32),
    private val pairingId: ByteArray = UUID.randomUUID().toString().toByteArray(),
) {
    private val authPublic = Ed25519.publicKey(seed)
    private lateinit var srp: Srp
    private lateinit var salt: ByteArray
    private lateinit var serverPublic: ByteArray
    private lateinit var sessionKey: ByteArray

    fun m1(): ByteArray = Tlv8.write(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1))

    /** Reads M2 (salt and the TV's SRP public key). */
    fun m2(tlv: ByteArray) {
        val t = checkTlv(Tlv8.read(tlv))
        salt = t[Tlv8.SALT] ?: throw PairingException("No salt from the TV")
        serverPublic = t[Tlv8.PUBLIC_KEY] ?: throw PairingException("No public key from the TV")
    }

    fun m3(pin: String): ByteArray {
        srp = Srp(pin, seed)
        srp.process(serverPublic, salt)
        return Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof)
    }

    /**
     * Reads M4 and checks the TV's SRP proof, which shows it knew the PIN (pyatv skips this
     * check), then builds M5: our identifier, public key and signature, encrypted.
     */
    fun m5(m4: ByteArray, name: String?): ByteArray {
        val t = checkTlv(Tlv8.read(m4))
        val proof = t[Tlv8.PROOF] ?: throw PairingException("No proof from the TV")
        if (!proof.contentEquals(srp.expectedServerProof)) throw PairingException("The TV's pairing proof doesn't match")
        val k = srp.sessionKey
        val deviceX = Crypto.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k)
        sessionKey = Crypto.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k)
        val signature = Ed25519.sign(seed, deviceX + pairingId + authPublic)
        val items = mutableListOf(Tlv8.IDENTIFIER to pairingId, Tlv8.PUBLIC_KEY to authPublic, Tlv8.SIGNATURE to signature)
        if (!name.isNullOrEmpty()) items.add(Tlv8.NAME to Opack.pack(mapOf("name" to name)))
        val encrypted = Crypto.seal(sessionKey, Crypto.labelNonce("PS-Msg05"), Tlv8.write(*items.toTypedArray()))
        return Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to encrypted)
    }

    /** Reads M6 (the TV's identifier and long-term public key) and returns the credentials. */
    fun m6(m6: ByteArray): Credentials {
        val t = checkTlv(Tlv8.read(m6))
        val encrypted = t[Tlv8.ENCRYPTED_DATA] ?: throw PairingException("No data from the TV")
        val inner = try {
            Tlv8.read(Crypto.open(sessionKey, Crypto.labelNonce("PS-Msg06"), encrypted))
        } catch (e: Exception) {
            throw PairingException("Couldn't decrypt the TV's reply")
        }
        val id = inner[Tlv8.IDENTIFIER] ?: throw PairingException("No identifier from the TV")
        val ltpk = inner[Tlv8.PUBLIC_KEY] ?: throw PairingException("No key from the TV")
        return Credentials(ltpk, seed, id, pairingId)
    }
}

/**
 * HAP pair-verify for Companion: an X25519 exchange, each side signing both public keys
 * with its long-term Ed25519 key. Afterwards [keys] gives the frame keys (output, input).
 */
class PairVerify(private val credentials: Credentials, private val privateKey: ByteArray = Crypto.random(32)) {
    private val publicKey = X25519.publicKey(privateKey)
    private lateinit var shared: ByteArray

    fun m1(): ByteArray = Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to publicKey)

    /** Reads M2, checks the TV is the one we paired with, and builds M3. */
    fun m3(m2: ByteArray): ByteArray {
        val t = checkTlv(Tlv8.read(m2))
        val serverPublic = t[Tlv8.PUBLIC_KEY] ?: throw PairingException("No public key from the TV")
        val encrypted = t[Tlv8.ENCRYPTED_DATA] ?: throw PairingException("No data from the TV")
        shared = X25519.sharedSecret(privateKey, serverPublic)
        val key = Crypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val inner = try {
            Tlv8.read(Crypto.open(key, Crypto.labelNonce("PV-Msg02"), encrypted))
        } catch (e: Exception) {
            throw PairingException("Couldn't decrypt the TV's reply")
        }
        val id = inner[Tlv8.IDENTIFIER] ?: throw PairingException("No identifier from the TV")
        val signature = inner[Tlv8.SIGNATURE] ?: throw PairingException("No signature from the TV")
        if (!id.contentEquals(credentials.atvId)) throw PairingException("This isn't the TV we paired with")
        if (!Ed25519.verify(credentials.ltpk, serverPublic + id + publicKey, signature)) {
            throw PairingException("The TV's signature doesn't match")
        }
        val ours = Ed25519.sign(credentials.ltsk, publicKey + credentials.clientId + serverPublic)
        val tlv = Tlv8.write(Tlv8.IDENTIFIER to credentials.clientId, Tlv8.SIGNATURE to ours)
        val sealed = Crypto.seal(key, Crypto.labelNonce("PV-Msg03"), tlv)
        return Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to sealed)
    }

    /** Reads M4, which only carries an error if the TV didn't accept us. */
    fun m4(m4: ByteArray) {
        try {
            checkTlv(Tlv8.read(m4))
        } catch (e: PairingException) {
            throw PairingException("The TV no longer accepts this pairing. Pair again.")
        }
    }

    /** Frame keys: (output, input) = HKDF of the shared secret with ClientEncrypt/ServerEncrypt-main. */
    fun keys(): Pair<ByteArray, ByteArray> =
        Crypto.hkdf("", "ClientEncrypt-main", shared) to Crypto.hkdf("", "ServerEncrypt-main", shared)
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun String.unhex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

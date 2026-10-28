package xyz.dvdsgl.tvremote.companion

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** The TV answered a request with an error ("_em"), or didn't answer in time. */
class CompanionException(message: String) : IOException(message)

/**
 * One TCP connection to a Companion port (pyatv companion/connection.py and protocol.py).
 * Frames are a 1-byte type, a 3-byte big-endian length and the payload. After
 * pair-verify, payloads are sealed with ChaCha20-Poly1305: 12-byte little-endian counter
 * nonces per direction, the frame header as AAD. Requests carry an "_x" id and are matched
 * to their responses; auth frames are matched by type (only one exchange at a time).
 *
 * A reader thread delivers events to [onEvent] and the end of the connection to [onClosed];
 * both run on that thread.
 */
class Connection(
    private val onEvent: (name: String, content: Map<*, *>) -> Unit = { _, _ -> },
    private val onClosed: (Exception?) -> Unit = {},
    firstXid: Long = Random.nextLong(0, 1L shl 16),
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val pending = ConcurrentHashMap<Any, CompletableFuture<Map<*, *>>>()
    private val xid = AtomicLong(firstXid)
    @Volatile private var outKey: ByteArray? = null
    @Volatile private var inKey: ByteArray? = null
    private var outCounter = 0L
    private var inCounter = 0L
    @Volatile var isOpen = false
        private set

    fun open(host: String, port: Int, timeoutMs: Int = 5_000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        output = s.getOutputStream()
        isOpen = true
        Thread({ readLoop(DataInputStream(s.getInputStream())) }, "companion-read").apply { isDaemon = true }.start()
    }

    fun close() {
        isOpen = false
        try { socket?.close() } catch (_: Exception) {}
    }

    fun enableEncryption(output: ByteArray, input: ByteArray) {
        synchronized(this) { outKey = output; outCounter = 0 }
        inCounter = 0
        inKey = input
    }

    /** Sends an OPACK frame without waiting, adding an "_x" id if the message has none. */
    fun send(frameType: Int, message: Map<String, Any?>) {
        val m = if ("_x" in message) message else message + ("_x" to xid.getAndIncrement())
        sendFrame(frameType, Opack.pack(m))
    }

    /** Sends a request ("_t" 2) and waits for its response; throws if the TV reports an error. */
    fun request(identifier: String, content: Map<String, Any?>, timeoutMs: Long = 5_000): Map<*, *> {
        val id = xid.getAndIncrement()
        val future = CompletableFuture<Map<*, *>>()
        pending[id] = future
        try {
            send(FRAME_E_OPACK, linkedMapOf("_i" to identifier, "_t" to 2, "_c" to content, "_x" to id))
            val reply = await(future, timeoutMs, identifier)
            reply["_em"]?.let { throw CompanionException("$identifier failed: $it") }
            return reply
        } finally {
            pending.remove(id)
        }
    }

    /** Sends an event ("_t" 1), which has no response. */
    fun event(identifier: String, content: Map<String, Any?>) =
        send(FRAME_E_OPACK, linkedMapOf("_i" to identifier, "_t" to 1, "_c" to content))

    /**
     * Pair-setup or pair-verify step: sends [message] in a PS_/PV_ frame and returns the
     * TV's reply, which always comes back as PS_Next or PV_Next.
     */
    fun exchangeAuth(frameType: Int, message: Map<String, Any?>, timeoutMs: Long = 10_000): Map<*, *> {
        val key = if (frameType == FRAME_PS_START || frameType == FRAME_PS_NEXT) FRAME_PS_NEXT else FRAME_PV_NEXT
        val future = CompletableFuture<Map<*, *>>()
        pending[key] = future
        try {
            send(frameType, message)
            return await(future, timeoutMs, "pairing")
        } finally {
            pending.remove(key)
        }
    }

    private fun await(future: CompletableFuture<Map<*, *>>, timeoutMs: Long, what: String): Map<*, *> = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        throw CompanionException("The TV didn't answer ($what)")
    } catch (e: ExecutionException) {
        throw (e.cause as? IOException) ?: IOException(e.cause)
    }

    private fun sendFrame(frameType: Int, payload: ByteArray) {
        synchronized(this) {
            val out = output ?: throw IOException("not connected")
            val key = outKey
            val length = payload.size + if (key != null && payload.isNotEmpty()) 16 else 0
            val header = byteArrayOf(frameType.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
            val body = if (key != null && payload.isNotEmpty()) Crypto.seal(key, nonce(outCounter++), payload, header) else payload
            out.write(header + body)
            out.flush()
        }
    }

    private fun readLoop(input: DataInputStream) {
        var error: Exception? = null
        try {
            val header = ByteArray(4)
            while (true) {
                input.readFully(header)
                val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                var payload = ByteArray(length)
                input.readFully(payload)
                val key = inKey
                if (key != null && payload.isNotEmpty()) payload = Crypto.open(key, nonce(inCounter++), payload, header)
                handle(header[0].toInt() and 0xFF, payload)
            }
        } catch (e: Exception) {
            if (isOpen) error = e
        }
        isOpen = false
        try { socket?.close() } catch (_: Exception) {}
        val reason = error as? IOException ?: IOException("connection closed", error)
        pending.values.forEach { it.completeExceptionally(reason) }
        onClosed(error)
    }

    private fun handle(frameType: Int, payload: ByteArray) {
        val message = try { Opack.unpack(payload) as? Map<*, *> } catch (_: Exception) { null } ?: return
        when (frameType) {
            FRAME_PS_START, FRAME_PS_NEXT, FRAME_PV_START, FRAME_PV_NEXT -> pending[frameType]?.complete(message)
            FRAME_U_OPACK, FRAME_E_OPACK, FRAME_P_OPACK -> when ((message["_t"] as? Number)?.toInt()) {
                1 -> {
                    val name = message["_i"] as? String ?: return
                    try { onEvent(name, message["_c"] as? Map<*, *> ?: emptyMap<String, Any>()) } catch (_: Exception) {}
                }
                3 -> (message["_x"] as? Number)?.let { pending[it.toLong()]?.complete(message) }
            }
        }
    }

    companion object {
        const val FRAME_PS_START = 3
        const val FRAME_PS_NEXT = 4
        const val FRAME_PV_START = 5
        const val FRAME_PV_NEXT = 6
        const val FRAME_U_OPACK = 7
        const val FRAME_E_OPACK = 8
        const val FRAME_P_OPACK = 9

        fun nonce(counter: Long) = ByteArray(12).also { for (i in 0 until 8) it[i] = (counter ushr (8 * i)).toByte() }
    }
}

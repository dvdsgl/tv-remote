package xyz.dvdsgl.tvremote.companion

import kotlin.random.Random

/** How we introduce ourselves to the TV in "_systemInfo" and in pairing. */
data class ClientInfo(
    val name: String,
    /** Random 12 hex digits, kept per install (pyatv's rp_id). */
    val rpId: String,
    /** A MAC-style id, kept per install (pyatv's device_id). */
    val deviceId: String,
    val model: String = "iPhone10,6",
)

/** HID button codes ("_hidC"). */
object Hid {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 3
    const val RIGHT = 4
    const val MENU = 5
    const val SELECT = 6
    const val HOME = 7
    const val VOLUME_UP = 8
    const val VOLUME_DOWN = 9
    const val SIRI = 10
    const val SLEEP = 12
    const val WAKE = 13
    const val PLAY_PAUSE = 14
    const val PAGE_DOWN = 19 // opens Control Center
}

/** Touch phases ("_tPh"), as in pyatv's TouchAction. */
object Touch {
    const val PRESS = 1
    const val HOLD = 3
    const val RELEASE = 4
}

/**
 * Pairs with an Apple TV over Companion. The TV shows a PIN once [begin] returns; pass it
 * to [finish]. Ported from pyatv companion/pairing.py and auth.py.
 */
class Pairing(private val host: String, private val port: Int, private val name: String) {
    private val connection = Connection()
    private val setup = PairSetup()

    fun begin() {
        connection.open(host, port)
        val reply = connection.exchangeAuth(Connection.FRAME_PS_START, linkedMapOf("_pd" to setup.m1(), "_pwTy" to 1))
        setup.m2(pairingData(reply))
    }

    fun finish(pin: String): Credentials {
        val m4 = connection.exchangeAuth(Connection.FRAME_PS_NEXT, linkedMapOf("_pd" to setup.m3(pin.padStart(4, '0')), "_pwTy" to 1))
        val m6 = connection.exchangeAuth(Connection.FRAME_PS_NEXT, linkedMapOf("_pd" to setup.m5(pairingData(m4), name), "_pwTy" to 1))
        return setup.m6(pairingData(m6))
    }

    fun close() = connection.close()
}

internal fun pairingData(message: Map<*, *>): ByteArray =
    message["_pd"] as? ByteArray ?: throw PairingException("No pairing data from the TV")

/**
 * A verified Companion session with one Apple TV: the parts of pyatv's CompanionAPI,
 * CompanionPower, CompanionKeyboard, CompanionRemoteControl and CompanionTouchGestures
 * that the remote uses. Methods block, so call them off the main thread, one at a time.
 * [onChange] and [onClosed] run on the connection's reader thread.
 */
class AppleTv(
    private val host: String,
    private val port: Int,
    private val credentials: Credentials,
    private val info: ClientInfo,
    private val onChange: () -> Unit = {},
    private val onClosed: (Exception?) -> Unit = {},
) {
    private val connection = Connection(::onEvent, ::closed)
    private var sid = 0L
    private var touchBase = System.nanoTime()
    private val subscribed = mutableListOf<String>()
    @Volatile private var closing = false

    /** True (awake or screensaver), false (asleep) or null (unknown). */
    @Volatile var power: Boolean? = null
        private set
    /** Whether a text field on the TV has focus (the keyboard is up). */
    @Volatile var keyboard = false
        private set
    val isOpen get() = connection.isOpen

    /** Connects, runs pair-verify and sets up the session like pyatv's CompanionAPI.connect. */
    fun connect() {
        connection.open(host, port)
        try {
            val verify = PairVerify(credentials)
            val m2 = connection.exchangeAuth(Connection.FRAME_PV_START, linkedMapOf("_pd" to verify.m1(), "_auTy" to 4))
            val m4 = connection.exchangeAuth(Connection.FRAME_PV_NEXT, linkedMapOf("_pd" to verify.m3(pairingData(m2))))
            verify.m4(pairingData(m4))
            val (output, input) = verify.keys()
            connection.enableEncryption(output, input)

            connection.request("_systemInfo", linkedMapOf(
                "_bf" to 0, "_cf" to 512, "_clFl" to 128,
                // A null "_i" stops the TV from pushing power state events (pyatv).
                "_i" to info.rpId,
                "_idsID" to credentials.clientId,
                "_pubID" to info.deviceId,
                "_sf" to 256, "_sv" to "170.18",
                "model" to info.model, "name" to info.name,
            ))
            touchBase = System.nanoTime()
            connection.request("_touchStart", linkedMapOf("_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0))
            val localSid = Random.nextLong(0, 1L shl 32)
            val session = connection.request("_sessionStart", linkedMapOf("_srvT" to SERVICE, "_sid" to localSid))
            val remoteSid = ((session["_c"] as? Map<*, *>)?.get("_sid") as? Number)?.toLong()
                ?: throw CompanionException("No session id from the TV")
            sid = (remoteSid shl 32) or localSid
            // tvOS only answers FetchAttentionState once a TV Remote session is registered.
            try { connection.request("TVRCSessionStart", linkedMapOf("ProtocolVersionKey" to "1.2")) } catch (_: Exception) {}
            focus(textInputStart())
            subscribe("_iMC")

            // Power: a snapshot if the TV still answers it, then pushed updates either way.
            try {
                val state = connection.request("FetchAttentionState", linkedMapOf())
                power = powerFrom((state["_c"] as? Map<*, *>)?.get("state"))
            } catch (_: Exception) {}
            try {
                subscribe("SystemStatus")
                subscribe("TVSystemStatus")
            } catch (_: Exception) {}
        } catch (e: Exception) {
            close(polite = false)
            throw e
        }
    }

    /** Ends the session politely (best effort), then closes the socket. */
    fun close(polite: Boolean = true) {
        if (closing) return
        closing = true
        if (polite && connection.isOpen) {
            try {
                for (event in subscribed.toList()) connection.event("_interest", linkedMapOf("_deregEvents" to listOf(event)))
                connection.request("_sessionStop", linkedMapOf("_srvT" to SERVICE, "_sid" to sid), 1_000)
                connection.request("_touchStop", linkedMapOf("_i" to 1), 1_000)
                connection.request("_tiStop", linkedMapOf(), 1_000)
            } catch (_: Exception) {}
        }
        connection.close()
    }

    /**
     * A remote button by the name the app uses (the bridge's key names). home_hold opens
     * the app switcher (a double press of TV, as on the Siri Remote); top_menu holds Back.
     */
    fun key(name: String) {
        when (name) {
            "home_hold" -> { press(Hid.HOME); press(Hid.HOME) }
            "top_menu" -> hold(Hid.MENU)
            "skip_forward" -> skip(10.0)
            "skip_backward" -> skip(-10.0)
            else -> press(KEYS[name] ?: throw IllegalArgumentException("unknown key $name"))
        }
    }

    fun hid(command: Int, down: Boolean) {
        connection.request("_hidC", linkedMapOf("_hBtS" to if (down) 1 else 2, "_hidC" to command))
    }

    fun press(command: Int) { hid(command, true); hid(command, false) }

    fun hold(command: Int, millis: Long = 1_000) {
        hid(command, true)
        Thread.sleep(millis)
        hid(command, false)
    }

    /** A touch at x, y in 0..1000; fire and forget, like pyatv's hid_event. */
    fun touch(x: Int, y: Int, phase: Int) {
        connection.event("_hidT", linkedMapOf(
            "_ns" to System.nanoTime() - touchBase, "_tFg" to 1,
            "_cx" to x.coerceIn(0, 1000), "_tPh" to phase, "_cy" to y.coerceIn(0, 1000),
        ))
    }

    /** Skips by [seconds] (negative to go back), as the iPhone remote's 10-second skip. */
    fun skip(seconds: Double) {
        connection.request("_mcc", linkedMapOf("_mcc" to MCC_SKIP_BY, "_skpS" to seconds))
    }

    fun turnOn() = hid(Hid.WAKE, false)

    fun turnOff() = hid(Hid.SLEEP, false)

    /**
     * pyatv's text_input_command: restarts the text session to get the field's current
     * contents, optionally clears it, appends [append], and returns the resulting text
     * (null if no text field has focus).
     */
    fun text(append: String, clear: Boolean): String? {
        try { connection.request("_tiStop", linkedMapOf(), 2_000) } catch (_: Exception) {}
        val content = textInputStart()
        focus(content)
        val data = content["_tiD"] as? ByteArray ?: return null
        val session = Bplist.archiveValue(data, "sessionUUID") as? ByteArray ?: return null
        var current = Bplist.archiveValue(data, "documentState", "docSt", "contextBeforeInput") as? String ?: ""
        if (clear) {
            connection.event("_tiC", linkedMapOf("_tiV" to 1, "_tiD" to Bplist.rtiClearText(session)))
            current = ""
        }
        if (append.isNotEmpty()) {
            connection.event("_tiC", linkedMapOf("_tiV" to 1, "_tiD" to Bplist.rtiInputText(session, append)))
            current += append
        }
        return current
    }

    private fun textInputStart(): Map<*, *> =
        connection.request("_tiStart", linkedMapOf())["_c"] as? Map<*, *> ?: emptyMap<String, Any>()

    private fun subscribe(event: String) {
        if (event in subscribed) return
        connection.event("_interest", linkedMapOf("_regEvents" to listOf(event)))
        subscribed.add(event)
    }

    private fun focus(content: Map<*, *>) {
        val focused = "_tiD" in content
        if (focused != keyboard) { keyboard = focused; onChange() }
    }

    private fun onEvent(name: String, content: Map<*, *>) {
        when (name) {
            "_tiStarted", "_tiStopped" -> focus(content)
            "SystemStatus", "TVSystemStatus" -> {
                val p = powerFrom(content["state"])
                if (p != power) { power = p; onChange() }
            }
        }
    }

    private fun closed(error: Exception?) {
        if (!closing) onClosed(error)
    }

    companion object {
        private const val SERVICE = "com.apple.tvremoteservices"
        private const val MCC_SKIP_BY = 7
        private val KEYS = mapOf(
            "up" to Hid.UP, "down" to Hid.DOWN, "left" to Hid.LEFT, "right" to Hid.RIGHT,
            "select" to Hid.SELECT, "menu" to Hid.MENU, "home" to Hid.HOME,
            "volume_up" to Hid.VOLUME_UP, "volume_down" to Hid.VOLUME_DOWN,
            "play_pause" to Hid.PLAY_PAUSE, "control_center" to Hid.PAGE_DOWN,
        )

        /** SystemStatus: 1 asleep, 2 screensaver, 3 awake, 4 idle. */
        fun powerFrom(state: Any?): Boolean? = when ((state as? Number)?.toInt()) {
            1 -> false
            2, 3, 4 -> true
            else -> null
        }
    }
}

package xyz.dvdsgl.tvremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import xyz.dvdsgl.tvremote.companion.AppleTv
import xyz.dvdsgl.tvremote.companion.Hid
import xyz.dvdsgl.tvremote.companion.ClientInfo
import xyz.dvdsgl.tvremote.companion.CompanionException
import xyz.dvdsgl.tvremote.companion.Credentials
import xyz.dvdsgl.tvremote.companion.Pairing
import xyz.dvdsgl.tvremote.companion.PairingException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Talks to the Apple TVs directly over Companion. It keeps the JSON ops the app used with
 * the old pyatv bridge (now retired): devices, select, key, touch, power, text,
 * pair_begin, pair_pin and siri, with replies {"ok": ...} and {"ev": "state", ...} events.
 *
 * All TV work runs on one worker thread; callbacks are delivered on the main thread.
 * Paired credentials and the last known address of each TV live in the "tvs" preferences.
 */
class Remote(
    context: Context,
    private val onConnected: (Boolean) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
) {
    private class Tv(val name: String, val model: String, val host: String, val port: Int, val seen: Boolean)
    private class Failure(message: String, val flag: String? = null) : Exception(message)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "tv").apply { isDaemon = true } }
    private val prefs = context.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    private val tvs = ConcurrentHashMap<String, Tv>()
    private val discovery = Discovery(context, ::found)
    private val info: ClientInfo
    @Volatile private var running = false
    // Worker thread only:
    private var device: String? = null
    private var tv: AppleTv? = null
    private var tvId: String? = null
    private var pairing: Pairing? = null
    private var pairingId: String? = null
    private var reconnecting = false

    /** True while started (the app is in the foreground). */
    @Volatile var connected = false
        private set

    init {
        val saved = try { JSONObject(prefs.getString("tvs", "{}")!!) } catch (_: Exception) { JSONObject() }
        for (id in saved.keys()) {
            val t = saved.getJSONObject(id)
            tvs[id] = Tv(t.getString("name"), t.getString("model"), t.getString("host"), t.getInt("port"), seen = false)
        }
        if (!prefs.contains("rpId")) {
            val mac = Random.nextBytes(6).also { it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() } // locally administered
            prefs.edit()
                .putString("rpId", Random.nextBytes(6).joinToString("") { "%02x".format(it) })
                .putString("deviceId", mac.joinToString(":") { "%02X".format(it) })
                .apply()
        }
        info = ClientInfo(NAME, prefs.getString("rpId", "")!!, prefs.getString("deviceId", "")!!)
    }

    fun start() {
        if (running) return
        running = true
        connected = true
        discovery.start()
        main.post { onConnected(true) }
    }

    fun stop() {
        running = false
        connected = false
        discovery.stop()
        worker.execute { disconnect() }
    }

    /** Fire and forget (keys, touches, typing). Errors are dropped. */
    fun send(msg: JSONObject) {
        worker.execute { try { handle(msg) } catch (_: Exception) {} }
    }

    /** Request with a reply; [done] gets {"ok": false, "error": ...} on failure or after 20 s. */
    fun request(msg: JSONObject, done: (JSONObject) -> Unit) {
        val finished = AtomicBoolean(false)
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) done(JSONObject().put("ok", false).put("error", "Timed out"))
        }
        main.postDelayed(timeout, 20_000)
        worker.execute {
            val reply = try {
                (handle(msg) ?: JSONObject()).put("ok", true)
            } catch (e: Failure) {
                JSONObject().put("ok", false).put("error", e.message).apply { e.flag?.let { put(it, true) } }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            main.post {
                main.removeCallbacks(timeout)
                if (finished.compareAndSet(false, true)) done(reply)
            }
        }
    }

    private fun handle(msg: JSONObject): JSONObject? = when (val op = msg.optString("op")) {
        "devices" -> {
            if (msg.optBoolean("rescan") || tvs.values.none { it.seen }) {
                discovery.restart()
                waitFor(3_000) { tvs.values.any { it.seen } }
            }
            val list = JSONArray()
            for ((id, t) in tvs.entries.sortedBy { it.value.name }) {
                list.put(JSONObject().put("id", id).put("name", t.name).put("model", t.model)
                    .put("paired", prefs.contains("cred:$id")).put("connected", id == tvId && tv?.isOpen == true))
            }
            JSONObject().put("devices", list)
        }
        "select" -> {
            val id = msg.getString("device")
            if (id != device) disconnect()
            device = id
            connect()
            state(id)
        }
        "key" -> { connect().key(msg.getString("key")); null }
        // Hold to talk, like the Siri Remote: button down when pressed, up when released.
        "siri" -> { connect().hid(Hid.SIRI, msg.getBoolean("down")); null }
        "touch" -> {
            val t = tv?.takeIf { it.isOpen && tvId == device }
            // Touches are only worth sending live; reconnect in the background instead.
            if (t == null) {
                if (!reconnecting) {
                    reconnecting = true
                    worker.execute { reconnecting = false; try { connect(); device?.let(::postState) } catch (_: Exception) {} }
                }
            } else t.touch(msg.getInt("x"), msg.getInt("y"), msg.getInt("mode"))
            null
        }
        "power" -> { connect().let { if (msg.optBoolean("on")) it.turnOn() else it.turnOff() }; null }
        "text" -> {
            val t = connect()
            val text = when {
                msg.has("set") -> t.text(msg.getString("set"), clear = true)
                msg.optBoolean("clear") -> t.text("", clear = true)
                else -> t.text("", clear = false)
            }
            JSONObject().put("text", text ?: "")
        }
        "pair_begin" -> {
            pairing?.close()
            pairing = null
            val id = msg.getString("device")
            val t = lookup(id)
            val p = Pairing(t.host, t.port, NAME)
            try {
                p.begin()
            } catch (e: Exception) {
                p.close()
                throw failure(e, t.name)
            }
            pairing = p
            pairingId = id
            JSONObject().put("pin_on_tv", true)
        }
        "pair_pin" -> {
            val p = pairing ?: throw Failure("Pairing not started")
            val id = pairingId!!
            pairing = null
            try {
                val creds = p.finish(msg.getString("pin"))
                prefs.edit().putString("cred:$id", creds.toString()).apply()
            } catch (e: Exception) {
                throw failure(e, tvs[id]?.name ?: "the TV")
            } finally {
                p.close()
            }
            if (tvId == id) disconnect() // the old session used the old pairing
            JSONObject().put("paired", true)
        }
        else -> throw Failure("Unknown op $op")
    }

    /** The open session to the selected TV, connecting (and pair-verifying) if needed. */
    private fun connect(): AppleTv {
        val id = device ?: throw Failure("No TV selected")
        tv?.let { if (it.isOpen && tvId == id) return it }
        disconnect()
        val t = lookup(id)
        val creds = prefs.getString("cred:$id", null)?.let {
            try { Credentials.parse(it) } catch (_: Exception) { null }
        } ?: throw Failure("Not paired", "unpaired")
        lateinit var atv: AppleTv
        atv = AppleTv(t.host, t.port, creds, info,
            onChange = { worker.execute { if (tv === atv) postState(id) } },
            onClosed = { worker.execute { lost(atv, id) } })
        try {
            atv.connect()
        } catch (e: PairingException) {
            // The TV no longer knows us (it was reset, or the pairing was removed in Settings).
            throw Failure(e.message ?: "Not paired", "unpaired")
        } catch (e: Exception) {
            throw failure(e, t.name)
        }
        tv = atv
        tvId = id
        return atv
    }

    private fun disconnect() {
        tv?.close()
        tv = null
        tvId = null
    }

    /** The TV dropped the connection: tell the UI, and try once to get it back. */
    private fun lost(atv: AppleTv, id: String) {
        if (tv !== atv) return
        tv = null
        tvId = null
        postState(id)
        if (running) worker.schedule({
            if (running && tv == null && device == id) try { connect(); postState(id) } catch (_: Exception) {}
        }, 1_500, TimeUnit.MILLISECONDS)
    }

    /** A known TV, waiting a few seconds for discovery if it hasn't been seen yet. */
    private fun lookup(id: String): Tv {
        tvs[id]?.let { return it }
        waitFor(4_000) { tvs.containsKey(id) }
        return tvs[id] ?: throw Failure("TV not found on the network", "unknown")
    }

    private fun found(f: Discovery.Found) {
        val old = tvs[f.id]
        tvs[f.id] = Tv(f.name, f.model, f.host, f.port, seen = true)
        if (old == null || old.host != f.host || old.port != f.port || old.name != f.name || old.model != f.model) {
            val saved = JSONObject()
            for ((id, t) in tvs) {
                saved.put(id, JSONObject().put("name", t.name).put("model", t.model).put("host", t.host).put("port", t.port))
            }
            prefs.edit().putString("tvs", saved.toString()).apply()
        }
    }

    private fun state(id: String): JSONObject {
        val t = tv?.takeIf { it.isOpen && tvId == id }
        val st = JSONObject().put("ev", "state").put("device", id).put("connected", t != null)
        if (t != null) {
            st.put("power", when (t.power) { true -> "on"; false -> "off"; null -> "unknown" })
            st.put("keyboard", t.keyboard)
        }
        return st
    }

    private fun postState(id: String) {
        val st = state(id)
        main.post { onEvent(st) }
    }

    private fun failure(e: Exception, name: String): Exception = when (e) {
        is Failure -> e
        is PairingException, is CompanionException -> Failure(e.message ?: "Failed")
        is IOException -> Failure("Can't reach $name")
        else -> Failure(e.message ?: e.javaClass.simpleName)
    }

    private fun waitFor(millis: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + millis
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    companion object {
        /** How the app shows up on the TV (Settings > Remotes and Devices). */
        const val NAME = "Remote"
    }
}

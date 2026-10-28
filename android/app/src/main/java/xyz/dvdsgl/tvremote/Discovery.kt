package xyz.dvdsgl.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.Executor

/**
 * Finds Apple TVs advertising Companion (`_companion-link._tcp`) with NsdManager and reports
 * each resolved one to [onFound], on a binder thread. HomePods advertise Companion too but
 * can't pair, so only "AppleTV*" models are kept (or, without a model, devices whose flags
 * say they pair with a PIN, as pyatv reads them).
 */
class Discovery(context: Context, private val onFound: (Found) -> Unit) {
    data class Found(val id: String, val name: String, val model: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val direct = Executor { it.run() }
    private var listener: NsdManager.DiscoveryListener? = null
    private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    private val queue = ArrayDeque<NsdServiceInfo>() // Android 13 resolves one service at a time
    private var resolving = false

    @Synchronized fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { synchronized(this@Discovery) { if (listener === this) listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
        }
        listener = l
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.values.forEach { try { nsd.unregisterServiceInfoCallback(it) } catch (_: Exception) {} }
        }
        callbacks.clear()
        queue.clear()
    }

    /** Starts over, so TVs that are already known get reported (and re-resolved) again. */
    fun restart() { stop(); start() }

    @Synchronized private fun resolve(info: NsdServiceInfo) {
        if (listener == null) return
        if (Build.VERSION.SDK_INT >= 34) {
            if (info.serviceName in callbacks) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val host = info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
                    report(info, host?.hostAddress)
                }
            }
            callbacks[info.serviceName] = cb
            try { nsd.registerServiceInfoCallback(info, direct, cb) } catch (_: Exception) { callbacks.remove(info.serviceName) }
        } else {
            queue.addLast(info)
            next()
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun next() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                report(info, info.host?.hostAddress)
                done()
            }
            private fun done() {
                synchronized(this@Discovery) { resolving = false }
                next()
            }
        })
    }

    private fun report(info: NsdServiceInfo, host: String?) {
        if (host == null || info.port <= 0) return
        val txt = info.attributes.entries.associate { (k, v) -> k.lowercase() to (v?.toString(Charsets.UTF_8) ?: "") }
        val model = txt["rpmd"]
        val flags = txt["rpfl"]?.removePrefix("0x")?.toLongOrNull(16) ?: 0
        if (model != null && !model.startsWith("AppleTV")) return
        if (model == null && flags and PAIRING_WITH_PIN == 0L) return
        // tvOS 16 and later advertise a static rpMRtID; the service name is the fallback.
        val id = txt["rpmrtid"]?.takeIf { it.isNotEmpty() } ?: info.serviceName
        onFound(Found(id, info.serviceName, modelName(model), host, info.port))
    }

    companion object {
        const val TYPE = "_companion-link._tcp"
        private const val PAIRING_WITH_PIN = 0x4000L

        /** pyatv's model names (convert.model_str). */
        fun modelName(model: String?) = when (model) {
            "AppleTV5,3" -> "Apple TV 4"
            "AppleTV6,2" -> "Apple TV 4K"
            "AppleTV11,1" -> "Apple TV 4K (gen 2)"
            "AppleTV14,1" -> "Apple TV 4K (gen 3)"
            else -> "Apple TV"
        }
    }
}

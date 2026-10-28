package xyz.dvdsgl.tvremote

import android.app.Activity

/**
 * The open-source (GitHub) build: dark mode is free. The Play build has the real upgrade
 * (src/play); both expose the same API to MainActivity.
 */
class Upgrade(activity: Activity, onChange: (owned: Boolean) -> Unit) {
    companion object { const val FREE = true }
    sealed interface Result { data object Bought : Result; data object Cancelled : Result; data class Failed(val message: String) : Result }
    val owned = true
    val price: String? = null
    fun start() {}
    fun stop() {}
    fun buy(done: (Result) -> Unit) = done(Result.Bought)
}

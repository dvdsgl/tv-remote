package xyz.dvdsgl.tvremote.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/**
 * The Kotlin client against pyatv's fake Companion Apple TV (tests/fake_device/companion.py):
 * pairing, pair-verify, session setup, keys, touch, power, power events and the keyboard.
 * Runs only under tools/e2e.sh, which starts the fake and sets FAKE_TV.
 */
class CompanionE2ETest {
    private val fake = System.getenv("FAKE_TV").orEmpty()

    private fun ctl(command: String): JSONObject {
        val (host, _, port) = fake.split(":")
        Socket(host, port.toInt()).use { s ->
            s.getOutputStream().write("$command\n".toByteArray())
            return JSONObject(BufferedReader(InputStreamReader(s.getInputStream())).readLine())
        }
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun pairConnectAndControl() {
        assumeTrue("set by tools/e2e.sh", fake.isNotEmpty())
        val (host, port) = fake.split(":").let { it[0] to it[1].toInt() }

        // Wrong PIN first: the fake answers M4 with an authentication error.
        val wrong = Pairing(host, port, "TV Remote")
        wrong.begin()
        assertEquals("Wrong code", assertThrows(PairingException::class.java) { wrong.finish("1234") }.message)
        wrong.close()

        val pairing = Pairing(host, port, "TV Remote")
        pairing.begin()
        val creds = pairing.finish("1111") // pyatv's fake PIN
        pairing.close()
        assertTrue(ctl("state").getBoolean("has_paired"))
        val parsed = Credentials.parse(creds.toString())

        var changes = 0
        var closedWith: Exception? = null
        val tv = AppleTv(host, port, parsed, ClientInfo("TV Remote", "cafecafecafe", "02:11:22:33:44:55"),
            onChange = { changes++ }, onClosed = { closedWith = it })
        tv.connect()

        var st = ctl("state")
        val info = st.getJSONObject("system_info")
        assertEquals("TV Remote", info.getString("name"))
        assertEquals("cafecafecafe", info.getString("_i"))
        assertEquals("02:11:22:33:44:55", info.getString("_pubID"))
        assertEquals(parsed.clientId.hex(), info.getString("_idsID"))
        assertEquals("com.apple.tvremoteservices", st.getString("service_type"))
        assertEquals(1000.0, st.getDouble("touch_width"), 0.0)
        assertEquals("1.2", st.getString("tv_rc_protocol_version"))
        eventually("interests") {
            ctl("state").getJSONArray("interests").toList().containsAll(listOf("_iMC", "SystemStatus", "TVSystemStatus"))
        }
        assertEquals(true, tv.power)
        assertTrue(tv.keyboard)

        for (key in listOf("up", "down", "left", "right", "select", "menu", "home", "volume_up", "volume_down", "play_pause", "control_center")) {
            tv.key(key)
            assertEquals(key, ctl("state").getString("latest_button"))
        }
        tv.key("home_hold")
        assertEquals("home", ctl("state").getString("latest_button"))
        tv.key("skip_forward")
        assertEquals(20.0, ctl("state").getDouble("duration"), 0.0)
        tv.key("skip_backward")
        tv.key("skip_backward")
        assertEquals(0.0, ctl("state").getDouble("duration"), 0.0)

        tv.touch(500, 600, Touch.PRESS)
        eventually("touch press") { ctl("state").optJSONObject("touch")?.let { it.getInt("mode") == 1 && it.getInt("x") == 500 && it.getInt("y") == 600 } == true }
        tv.touch(1200, -5, Touch.RELEASE)
        eventually("touch release") { ctl("state").optJSONObject("touch")?.let { it.getInt("mode") == 4 && it.getInt("x") == 1000 && it.getInt("y") == 0 } == true }

        tv.turnOff()
        assertFalse(ctl("state").getBoolean("powered_on"))
        tv.turnOn()
        assertTrue(ctl("state").getBoolean("powered_on"))
        ctl("status 1")
        eventually("asleep event") { tv.power == false }
        ctl("status 3")
        eventually("awake event") { tv.power == true }

        assertEquals("Fake Companion Keyboard Text", tv.text("", clear = false))
        assertEquals("hello", tv.text("hello", clear = true))
        eventually("text set") { ctl("state").optString("rti_text") == "hello" }
        assertEquals("hello world", tv.text(" world", clear = false))
        eventually("text appended") { ctl("state").optString("rti_text") == "hello world" }
        ctl("focus 0")
        eventually("keyboard hidden") { !tv.keyboard }
        assertNull(tv.text("ignored", clear = true))
        ctl("focus 1")
        eventually("keyboard shown") { tv.keyboard }
        assertTrue(changes >= 4)

        tv.close()
        eventually("session stopped") { ctl("state").getInt("sid") == 0 }
        assertNull(closedWith)
    }

    private fun org.json.JSONArray.toList() = List(length()) { getString(it) }
}

package xyz.dvdsgl.tvremote.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.dvdsgl.tvremote.companion.Golden.bytes
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Encrypted frames on a real socket, against frames pyatv's CompanionConnection produced. */
class FrameTest {
    private val g = Golden.json

    @Test fun encryptedFramesMatchPyatv() {
        val keys = g.getJSONObject("pairing")
        val frames = g.getJSONObject("frames")
        val sent = Golden.objects(frames.getJSONArray("sent"))
        val received = Golden.objects(frames.getJSONArray("received"))
        val events = LinkedBlockingQueue<Pair<String, Map<*, *>>>()

        ServerSocket(0).use { server ->
            val conn = Connection(onEvent = { name, c -> events.add(name to c) }, firstXid = 103)
            conn.open("127.0.0.1", server.localPort)
            server.accept().use { tv ->
                val input = DataInputStream(tv.getInputStream())
                fun frame(): ByteArray {
                    val header = ByteArray(4).also(input::readFully)
                    val n = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                    return header + ByteArray(n).also(input::readFully)
                }
                conn.enableEncryption(bytes(keys.getString("outputKey")), bytes(keys.getString("inputKey")))
                for (s in sent) {
                    @Suppress("UNCHECKED_CAST")
                    conn.send(Connection.FRAME_E_OPACK, Golden.value(s.getJSONObject("message")) as Map<String, Any?>)
                    assertEquals(s.getString("wire"), frame().hex())
                }
                // A response nobody waits for is dropped; the event is delivered.
                tv.getOutputStream().write(bytes(received[0].getString("wire")))
                tv.getOutputStream().write(bytes(received[1].getString("wire")))
                val (name, content) = events.poll(5, TimeUnit.SECONDS)!!
                assertEquals("SystemStatus", name)
                assertEquals(1L, content["state"])
                // A request (xid 103) gets the TV's error reply and throws.
                val replier = Thread { frame(); tv.getOutputStream().write(bytes(received[2].getString("wire"))) }.apply { start() }
                val e = assertThrows(CompanionException::class.java) { conn.request("_tiStart", linkedMapOf()) }
                assertEquals("_tiStart failed: No request handler", e.message)
                replier.join()
            }
            conn.close()
        }
    }

    @Test fun requestFailsWhenTheTvHangsUp() {
        ServerSocket(0).use { server ->
            val closed = LinkedBlockingQueue<String>()
            val conn = Connection(onClosed = { closed.add(it?.javaClass?.simpleName ?: "clean") })
            conn.open("127.0.0.1", server.localPort)
            val tv = server.accept()
            val t = Thread { Thread.sleep(200); tv.close() }.apply { start() }
            assertThrows(java.io.IOException::class.java) { conn.request("_hidC", linkedMapOf()) }
            t.join()
            closed.poll(5, TimeUnit.SECONDS)!!
        }
    }
}

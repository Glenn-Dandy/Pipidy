package de.kewl.pipidy.yeelight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class YeelightException(message: String) : IOException(message)

/** Colour temperature and colour are null on lamps that do not have them. */
data class YeelightState(
    val on: Boolean,
    val brightness: Int,
    val colorTemp: Int?,
    val hue: Int?,
    val saturation: Int?,
)

data class FoundYeelight(val host: String, val model: String, val name: String)

/** Yeelight LAN control: one JSON line per command over TCP. Has to be switched on in the Yeelight app. */
class YeelightClient(private val host: String) {
    private val mutex = Mutex()
    private var nextId = 1

    suspend fun state(): YeelightState {
        val values = call("get_prop", listOf("power", "bright", "ct", "rgb", "hue", "sat"))
        fun number(index: Int) = values.optString(index).toIntOrNull()
        val hasColor = number(3) != null
        return YeelightState(
            on = values.optString(0) == "on",
            brightness = (number(1) ?: 1).coerceIn(1, 100),
            colorTemp = number(2)?.takeIf { it > 0 },
            hue = number(4)?.takeIf { hasColor },
            saturation = number(5)?.takeIf { hasColor },
        )
    }

    suspend fun isOn(): Boolean = call("get_prop", listOf("power")).optString(0) == "on"

    suspend fun setPower(on: Boolean) {
        call("set_power", listOf(if (on) "on" else "off", "smooth", FADE_MS))
    }

    suspend fun setBrightness(percent: Int) {
        call("set_bright", listOf(percent, "smooth", FADE_MS))
    }

    suspend fun setColorTemp(kelvin: Int) {
        call("set_ct_abx", listOf(kelvin, "smooth", FADE_MS))
    }

    suspend fun setColor(hue: Int, saturation: Int) {
        call("set_hsv", listOf(hue, saturation, "smooth", FADE_MS))
    }

    private suspend fun call(method: String, params: List<Any>): JSONArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val id = nextId++
                val request = JSONObject().put("id", id).put("method", method).put("params", JSONArray(params))
                socket.getOutputStream().apply {
                    write((request.toString() + "\r\n").toByteArray())
                    flush()
                }
                readReply(socket.getInputStream().bufferedReader(), id)
            }
        }
    }

    /** The lamp also pushes state changes on this connection, so everything that is not our answer is skipped. */
    private fun readReply(reader: BufferedReader, id: Int): JSONArray {
        while (true) {
            val line = reader.readLine() ?: throw YeelightException("connection closed")
            val reply = try {
                JSONObject(line)
            } catch (_: JSONException) {
                continue
            }
            if (reply.optInt("id", -1) != id) continue
            reply.optJSONObject("error")?.let { throw YeelightException(it.optString("message")) }
            return reply.optJSONArray("result") ?: JSONArray()
        }
    }

    companion object {
        private const val PORT = 55443
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 2500
        private const val FADE_MS = 300
        private const val SEARCH_PORT = 1982
        private const val SEARCH_ROUNDS = 3
        private const val SEARCH_WAIT_MS = 900
        private const val SEARCH =
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n"

        /** Asks all Yeelights in the Wi-Fi to report themselves. The answers come back as unicast. */
        suspend fun discover(): List<FoundYeelight> = withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, FoundYeelight>()
            DatagramSocket().use { socket ->
                socket.soTimeout = SEARCH_WAIT_MS
                val request = SEARCH.toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val buffer = ByteArray(2048)
                repeat(SEARCH_ROUNDS) {
                    socket.send(DatagramPacket(request, request.size, group, SEARCH_PORT))
                    try {
                        while (true) {
                            val reply = DatagramPacket(buffer, buffer.size)
                            socket.receive(reply)
                            parse(String(buffer, 0, reply.length))?.let { found[it.host] = it }
                        }
                    } catch (_: SocketTimeoutException) {
                        // next round
                    }
                }
            }
            found.values.sortedBy { it.host }
        }

        private fun parse(answer: String): FoundYeelight? {
            val headers = answer.lines().mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
            }.toMap()
            val host = headers["location"]?.removePrefix("yeelight://")?.substringBefore(':') ?: return null
            if (host.isBlank()) return null
            return FoundYeelight(host, headers["model"].orEmpty(), headers["name"].orEmpty())
        }
    }
}

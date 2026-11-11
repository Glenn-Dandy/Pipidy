package de.kewl.pipidy.miio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MiioException(message: String) : IOException(message)
class MiioTimeoutException : IOException("no answer")
class MiioTokenException : IOException("token rejected")

/** miIO over UDP. The lamp only answers commands that directly follow a fresh hello. */
class MiioClient(private val host: String, tokenHex: String) {
    private val token = tokenHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val aesKey = md5(token)
    private val aesIv = md5(aesKey + token)
    private val mutex = Mutex()
    private var nextId = 1
    private var did = ""

    suspend fun get(siid: Int, piid: Int): Any? {
        val body = call("get_properties") { id ->
            JSONArray().put(JSONObject().put("did", id).put("siid", siid).put("piid", piid))
        }
        val entry = body.getJSONArray("result").getJSONObject(0)
        checkCode(entry)
        return entry.opt("value")
    }

    /** Reads several properties in one request. Entries the lamp refuses come back as null. */
    suspend fun getAll(props: List<Pair<Int, Int>>): List<Any?> {
        val body = call("get_properties") { id ->
            JSONArray().apply {
                props.forEach { (siid, piid) -> put(JSONObject().put("did", id).put("siid", siid).put("piid", piid)) }
            }
        }
        val result = body.getJSONArray("result")
        val entries = (0 until result.length()).map { result.getJSONObject(it) }
        return props.map { (siid, piid) ->
            entries.firstOrNull { it.optInt("siid") == siid && it.optInt("piid") == piid && it.optInt("code", 0) == 0 }
                ?.opt("value")
        }
    }

    suspend fun set(siid: Int, piid: Int, value: Any) {
        val body = call("set_properties") { id ->
            JSONArray().put(JSONObject().put("did", id).put("siid", siid).put("piid", piid).put("value", value))
        }
        checkCode(body.getJSONArray("result").getJSONObject(0))
    }

    suspend fun action(siid: Int, aiid: Int, inputs: List<Pair<Int, Any>>) {
        val body = call("action") { id ->
            val args = JSONArray()
            inputs.forEach { (piid, value) -> args.put(JSONObject().put("piid", piid).put("value", value)) }
            JSONObject().put("did", id).put("siid", siid).put("aiid", aiid).put("in", args)
        }
        checkCode(body.getJSONObject("result"))
    }

    private fun checkCode(entry: JSONObject) {
        val code = entry.optInt("code", 0)
        if (code != 0) throw MiioException("code $code")
    }

    private suspend fun call(method: String, params: (String) -> Any): JSONObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val address = InetAddress.getByName(host)
                val hello = exchange(socket, address, HELLO) { it.takeIf { p -> p.size == HEADER_SIZE } }
                val buffer = ByteBuffer.wrap(hello, 8, 8)
                val deviceId = buffer.int
                val stamp = buffer.int
                did = (deviceId.toLong() and 0xFFFFFFFFL).toString()

                val msgId = nextId++
                val json = JSONObject().put("id", msgId).put("method", method).put("params", params(did))
                val packet = encode(json.toString().toByteArray(), deviceId, stamp + 1)
                val reply = exchange(socket, address, packet) { raw ->
                    if (raw.size <= HEADER_SIZE) null
                    else decode(raw).takeIf { it.optInt("id") == msgId }
                }
                reply.optJSONObject("error")?.let { throw MiioException(it.optString("message")) }
                reply
            }
        }
    }

    private fun <T : Any> exchange(
        socket: DatagramSocket,
        address: InetAddress,
        packet: ByteArray,
        accept: (ByteArray) -> T?,
    ): T {
        val buffer = ByteArray(RECEIVE_SIZE)
        repeat(ATTEMPTS) {
            socket.send(DatagramPacket(packet, packet.size, address, PORT))
            try {
                while (true) {
                    val reply = DatagramPacket(buffer, buffer.size)
                    socket.receive(reply)
                    accept(buffer.copyOf(reply.length))?.let { return it }
                }
            } catch (_: SocketTimeoutException) {
                // next attempt
            }
        }
        throw MiioTimeoutException()
    }

    private fun encode(plain: ByteArray, deviceId: Int, stamp: Int): ByteArray {
        val encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(plain)
        val head = ByteBuffer.allocate(16)
            .putShort(MAGIC)
            .putShort((HEADER_SIZE + encrypted.size).toShort())
            .putInt(0)
            .putInt(deviceId)
            .putInt(stamp)
            .array()
        return head + md5(head + token + encrypted) + encrypted
    }

    private fun decode(raw: ByteArray): JSONObject {
        val plain = try {
            cipher(Cipher.DECRYPT_MODE).doFinal(raw, HEADER_SIZE, raw.size - HEADER_SIZE)
        } catch (_: GeneralSecurityException) {
            throw MiioTokenException()
        }
        return JSONObject(String(plain).trimEnd('\u0000'))
    }

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private companion object {
        const val PORT = 54321
        const val HEADER_SIZE = 32
        const val MAGIC: Short = 0x2131
        const val TIMEOUT_MS = 1200
        const val ATTEMPTS = 4
        const val RECEIVE_SIZE = 16384
        val HELLO = byteArrayOf(0x21, 0x31, 0x00, 0x20) + ByteArray(28) { 0xFF.toByte() }
    }
}

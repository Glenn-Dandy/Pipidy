package de.kewl.pipidy

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.kewl.pipidy.miio.MiioClient
import de.kewl.pipidy.miio.MiioException
import de.kewl.pipidy.miio.MiioTimeoutException
import de.kewl.pipidy.miio.MiioTokenException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

data class Motion(val id: Int, val name: String)

data class LampState(
    val on: Boolean,
    val mode: Int,
    val brightness: Int,
    val colorTemp: Int,
    val mood: Int,
    val personality: Int,
    val gesturesOn: Boolean,
    val followOn: Boolean,
    val lastGesture: Int,
    val timerOn: Boolean,
    val timerMinutes: Int,
    val timerLeft: Int,
    val firmware: String,
    val motions: List<Motion>,
)

data class UiState(
    val configured: Boolean = false,
    val editingConfig: Boolean = false,
    val host: String = DEFAULT_HOST,
    val token: String = "",
    val lamp: LampState? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

const val DEFAULT_HOST = "192.168.12.103"

class LampViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("lamp", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        UiState(
            configured = prefs.contains(KEY_TOKEN),
            host = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST,
            token = prefs.getString(KEY_TOKEN, "") ?: "",
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var client: MiioClient? = _ui.value.let { if (it.configured) MiioClient(it.host, it.token) else null }
    private var refreshJob: Job? = null

    fun saveConfig(host: String, token: String) {
        val cleanHost = host.trim()
        val cleanToken = token.trim().lowercase()
        prefs.edit().putString(KEY_HOST, cleanHost).putString(KEY_TOKEN, cleanToken).apply()
        client = MiioClient(cleanHost, cleanToken)
        _ui.update {
            it.copy(configured = true, editingConfig = false, host = cleanHost, token = cleanToken, lamp = null, error = null)
        }
        refresh()
    }

    fun editConfig(editing: Boolean) = _ui.update { it.copy(editingConfig = editing) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun refresh() {
        val current = client ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val lamp = readAll(current)
                _ui.update { it.copy(lamp = lamp, error = null) }
            } catch (e: IOException) {
                _ui.update { it.copy(error = describe(e)) }
            } catch (e: JSONException) {
                _ui.update { it.copy(error = "Die Antwort der Lampe war unverständlich (${e.message}).") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun setOn(on: Boolean) = send({ copy(on = on) }) { set(2, 1, on) }
    fun setBrightness(value: Int) = send({ copy(brightness = value) }) { set(2, 3, value) }
    fun setColorTemp(value: Int) = send({ copy(colorTemp = value) }) { set(2, 5, value) }
    fun setMode(value: Int) = send({ copy(mode = value) }, reload = true) { set(2, 2, value) }
    fun runMotion(id: Int) = send({ this }) { action(5, 2, listOf(2 to id)) }
    fun setGestures(on: Boolean) = send({ copy(gesturesOn = on) }) { set(7, 1, if (on) 1 else 0) }
    fun setFollow(on: Boolean) = send({ copy(followOn = on) }) { set(7, 3, if (on) 1 else 0) }
    fun setPersonality(value: Int) = send({ copy(personality = value) }) { set(3, 3, value) }
    fun setTimerOn(on: Boolean) = send({ copy(timerOn = on) }, reload = true) { set(6, 4, if (on) 1 else 0) }
    fun setTimerMinutes(value: Int) = send({ copy(timerMinutes = value) }) { set(6, 5, value) }

    private fun send(
        change: LampState.() -> LampState,
        reload: Boolean = false,
        command: suspend MiioClient.() -> Unit,
    ) {
        val current = client ?: return
        _ui.update { it.copy(lamp = it.lamp?.change()) }
        viewModelScope.launch {
            try {
                current.command()
                _ui.update { it.copy(error = null) }
                if (reload) refresh()
            } catch (e: IOException) {
                _ui.update { it.copy(error = describe(e)) }
                refresh()
            } catch (e: JSONException) {
                _ui.update { it.copy(error = "Die Antwort der Lampe war unverständlich (${e.message}).") }
            }
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is MiioTimeoutException -> "Die Lampe antwortet nicht. Bist du im WLAN zu Hause?"
        is MiioTokenException -> "Der Token passt nicht zu dieser Lampe."
        is MiioException -> "Die Lampe hat den Befehl abgelehnt (${e.message})."
        else -> "Keine Verbindung zur Lampe (${e.message})."
    }

    private suspend fun readAll(c: MiioClient): LampState {
        suspend fun read(siid: Int, piid: Int): Any? = c.get(siid, piid).also { delay(READ_GAP_MS) }
        suspend fun int(siid: Int, piid: Int) = (read(siid, piid) as? Number)?.toInt() ?: 0
        suspend fun flag(siid: Int, piid: Int) = when (val v = read(siid, piid)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> false
        }
        return LampState(
            on = flag(2, 1),
            mode = int(2, 2),
            brightness = int(2, 3).coerceIn(1, 100),
            colorTemp = int(2, 5).coerceIn(2700, 5700),
            mood = int(3, 1),
            personality = int(3, 3),
            gesturesOn = flag(7, 1),
            followOn = flag(7, 3),
            lastGesture = int(7, 2),
            timerOn = flag(6, 4),
            timerMinutes = int(6, 5),
            timerLeft = int(6, 6),
            firmware = read(1, 4)?.toString().orEmpty(),
            motions = parseMotions(read(5, 3) as? String),
        )
    }

    private fun parseMotions(raw: String?): List<Motion> =
        raw.orEmpty().split(';').mapNotNull { item ->
            val parts = item.split('-', limit = 3)
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val name = MOTION_NAMES[id] ?: parts.getOrNull(2)?.let { encoded ->
                runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
            } ?: "Bewegung $id"
            Motion(id, name)
        }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_TOKEN = "token"
        const val READ_GAP_MS = 60L
        val MOTION_NAMES = mapOf(
            0 to "Fröhlich",
            1 to "Überrascht",
            2 to "Angewidert",
            3 to "Wütend",
            4 to "Traurig",
            5 to "Ängstlich",
            6 to "Schlafen",
            7 to "Grüßen",
            8 to "Totstellen",
            9 to "Lichttanz 1",
            10 to "Lichttanz 2",
            11 to "Zwinkern",
            12 to "Nicken",
            13 to "Kopf schütteln",
        )
    }
}

package de.kewl.pipidy

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.kewl.pipidy.data.LampConfig
import de.kewl.pipidy.data.LampKind
import de.kewl.pipidy.data.LampStore
import de.kewl.pipidy.miio.MiioClient
import de.kewl.pipidy.miio.MiioException
import de.kewl.pipidy.miio.MiioTimeoutException
import de.kewl.pipidy.miio.MiioTokenException
import de.kewl.pipidy.yeelight.FoundYeelight
import de.kewl.pipidy.yeelight.YeelightClient
import de.kewl.pipidy.yeelight.YeelightException
import de.kewl.pipidy.yeelight.YeelightState
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

enum class Power { UNKNOWN, ON, OFF, OFFLINE }

sealed interface Screen {
    data object Dashboard : Screen
    data object Add : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String) : Screen
    data object About : Screen
}

data class Motion(val id: Int, val name: String)

data class PipiState(
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
    val lamps: List<LampConfig> = emptyList(),
    val power: Map<String, Power> = emptyMap(),
    val screen: Screen = Screen.Dashboard,
    val pipi: PipiState? = null,
    val yeelight: YeelightState? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val found: List<FoundYeelight> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
) {
    fun lamp(id: String): LampConfig? = lamps.firstOrNull { it.id == id }
}

const val DEFAULT_PIPI_HOST = "192.168.12.103"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LampStore(app.getSharedPreferences("lamp", Context.MODE_PRIVATE))
    private val _ui = MutableStateFlow(UiState(lamps = store.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val miioClients = HashMap<String, MiioClient>()
    private val yeelightClients = HashMap<String, YeelightClient>()
    private var refreshJob: Job? = null
    private var refreshRun = 0

    // Navigation

    fun openAdd() = _ui.update { it.copy(screen = Screen.Add, error = null, found = emptyList(), searched = false) }

    fun openLamp(id: String) {
        _ui.update { it.copy(screen = Screen.Detail(id), pipi = null, yeelight = null, error = null) }
        refresh()
    }

    fun openEdit(id: String) = _ui.update { it.copy(screen = Screen.Edit(id), error = null, found = emptyList(), searched = false) }

    fun openAbout() = _ui.update { it.copy(screen = Screen.About, error = null) }

    fun back() {
        when (val screen = _ui.value.screen) {
            Screen.Dashboard -> return
            is Screen.Edit -> _ui.update { it.copy(screen = Screen.Detail(screen.id), error = null) }
            else -> {
                _ui.update { it.copy(screen = Screen.Dashboard, pipi = null, yeelight = null, error = null) }
                refresh()
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    // Lamp list

    fun addLamp(name: String, kind: LampKind, host: String, token: String) {
        val lamp = LampConfig(LampStore.newId(), name.trim(), kind, host.trim(), token.trim().lowercase())
        saveLamps(_ui.value.lamps + lamp)
        _ui.update { it.copy(screen = Screen.Dashboard, error = null) }
        refresh()
    }

    fun updateLamp(id: String, name: String, host: String, token: String) {
        val lamps = _ui.value.lamps.map {
            if (it.id == id) it.copy(name = name.trim(), host = host.trim(), token = token.trim().lowercase()) else it
        }
        miioClients.remove(id)
        yeelightClients.remove(id)
        saveLamps(lamps)
        openLamp(id)
    }

    fun removeLamp(id: String) {
        miioClients.remove(id)
        yeelightClients.remove(id)
        saveLamps(_ui.value.lamps.filterNot { it.id == id })
        _ui.update { it.copy(screen = Screen.Dashboard, power = it.power - id, pipi = null, yeelight = null, error = null) }
        refresh()
    }

    private fun saveLamps(lamps: List<LampConfig>) {
        store.save(lamps)
        _ui.update { it.copy(lamps = lamps) }
    }

    fun searchYeelights() {
        if (_ui.value.searching) return
        viewModelScope.launch {
            _ui.update { it.copy(searching = true) }
            val found = try {
                YeelightClient.discover()
            } catch (_: IOException) {
                emptyList()
            }
            _ui.update { it.copy(searching = false, searched = true, found = found) }
        }
    }

    // Reading

    fun refresh() {
        refreshJob?.cancel()
        val run = ++refreshRun
        refreshJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                when (val screen = _ui.value.screen) {
                    is Screen.Detail -> loadDetail(screen.id)
                    else -> loadPower()
                }
            } finally {
                if (run == refreshRun) _ui.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun loadPower() = coroutineScope {
        _ui.value.lamps.forEach { lamp ->
            launch {
                val power = try {
                    val on = when (lamp.kind) {
                        LampKind.PIPI -> asFlag(miioOf(lamp).get(2, 1))
                        LampKind.YEELIGHT -> yeelightOf(lamp).isOn()
                    }
                    if (on) Power.ON else Power.OFF
                } catch (_: IOException) {
                    Power.OFFLINE
                } catch (_: JSONException) {
                    Power.OFFLINE
                }
                _ui.update { it.copy(power = it.power + (lamp.id to power)) }
            }
        }
    }

    private suspend fun loadDetail(id: String) {
        val lamp = _ui.value.lamp(id) ?: return
        fun shown(state: UiState) = state.screen == Screen.Detail(id)
        try {
            when (lamp.kind) {
                LampKind.PIPI -> {
                    val pipi = readPipi(miioOf(lamp))
                    _ui.update {
                        if (shown(it)) it.copy(pipi = pipi, power = it.power + (id to powerOf(pipi.on)), error = null) else it
                    }
                }
                LampKind.YEELIGHT -> {
                    val state = yeelightOf(lamp).state()
                    _ui.update {
                        if (shown(it)) it.copy(yeelight = state, power = it.power + (id to powerOf(state.on)), error = null) else it
                    }
                }
            }
        } catch (e: IOException) {
            _ui.update { if (shown(it)) it.copy(error = describe(lamp, e), power = it.power + (id to Power.OFFLINE)) else it }
        } catch (e: JSONException) {
            _ui.update { if (shown(it)) it.copy(error = garbled(lamp, e)) else it }
        }
    }

    // Commands for every lamp

    fun togglePower(id: String) = setPower(id, _ui.value.power[id] != Power.ON)

    fun setPower(id: String, on: Boolean) {
        val lamp = _ui.value.lamp(id) ?: return
        _ui.update {
            val shown = it.screen == Screen.Detail(id)
            it.copy(
                power = it.power + (id to powerOf(on)),
                pipi = if (shown) it.pipi?.copy(on = on) else it.pipi,
                yeelight = if (shown) it.yeelight?.copy(on = on) else it.yeelight,
            )
        }
        send(lamp) {
            when (lamp.kind) {
                LampKind.PIPI -> miioOf(lamp).set(2, 1, on)
                LampKind.YEELIGHT -> yeelightOf(lamp).setPower(on)
            }
        }
    }

    // Commands for the Pipi lamp

    fun setBrightness(value: Int) = pipi({ copy(brightness = value) }) { set(2, 3, value) }
    fun setColorTemp(value: Int) = pipi({ copy(colorTemp = value) }) { set(2, 5, value) }
    fun setMode(value: Int) = pipi({ copy(mode = value) }, reload = true) { set(2, 2, value) }
    fun runMotion(id: Int) = pipi({ this }) { action(5, 2, listOf(2 to id)) }
    fun setGestures(on: Boolean) = pipi({ copy(gesturesOn = on) }) { set(7, 1, if (on) 1 else 0) }
    fun setFollow(on: Boolean) = pipi({ copy(followOn = on) }) { set(7, 3, if (on) 1 else 0) }
    fun setPersonality(value: Int) = pipi({ copy(personality = value) }) { set(3, 3, value) }
    fun setTimerOn(on: Boolean) = pipi({ copy(timerOn = on) }, reload = true) { set(6, 4, if (on) 1 else 0) }
    fun setTimerMinutes(value: Int) = pipi({ copy(timerMinutes = value) }) { set(6, 5, value) }

    // Head control as the Mi Home plugin of the lamp does it (plugin 1009694, version 33).

    private var lastCalibration = 0L

    /** "Position calibration" from the plugin settings: hidden service siid 99, action 5. */
    fun calibrate() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCalibration < CALIBRATE_GAP_MS) return
        lastCalibration = now
        pipi({ this }) { action(99, 5, emptyList()) }
    }

    /** Turns the head back to the middle, the reset button of the plugin's remote control. */
    fun centreHead() = pipi({ this }) { action(8, 3, emptyList()) }

    // Remote control is siid 8: a1 start, a2 end, p1 direction as JSON "[h,v]" from -100 to 100,
    // h to the right and v downwards, sent every 500 ms while held and "[0,0]" on release.

    @Volatile private var steerValue = "[0,0]"
    @Volatile private var steerHeld = false
    private var steerJob: Job? = null

    /** [x] and [y] from -1 to 1 with right and up positive, as the steering circle reports them. */
    fun steer(x: Float, y: Float) {
        steerValue = "[${toRemote(x)},${toRemote(-y)}]"
        steerHeld = true
        if (steerJob?.isActive == true) return
        val lamp = shownLamp(LampKind.PIPI) ?: return
        steerJob = viewModelScope.launch { remoteSession(lamp, miioOf(lamp)) }
    }

    fun steerEnd() {
        steerHeld = false
    }

    private suspend fun remoteSession(lamp: LampConfig, client: MiioClient) {
        try {
            client.action(8, 1, emptyList())
            var stopped = true
            var idleSince = 0L
            while (true) {
                val started = SystemClock.elapsedRealtime()
                if (steerHeld) {
                    client.set(8, 1, steerValue)
                    stopped = false
                    idleSince = 0L
                } else {
                    if (!stopped) {
                        client.set(8, 1, "[0,0]")
                        stopped = true
                    }
                    if (idleSince == 0L) idleSince = started
                    if (started - idleSince >= STEER_IDLE_MS) break
                }
                // Wait for the next tick, but answer a release right away.
                val next = started + STEER_INTERVAL_MS
                while (SystemClock.elapsedRealtime() < next && (steerHeld || stopped)) delay(STEER_POLL_MS)
            }
        } catch (e: IOException) {
            _ui.update { it.copy(error = describe(lamp, e)) }
        } catch (e: JSONException) {
            _ui.update { it.copy(error = garbled(lamp, e)) }
        } finally {
            withContext(NonCancellable) {
                runCatching { client.set(8, 1, "[0,0]") }
                runCatching { client.action(8, 2, emptyList()) }
            }
        }
    }

    private fun toRemote(value: Float) = (value.coerceIn(-1f, 1f) * 100).toInt()

    private fun pipi(
        change: PipiState.() -> PipiState,
        reload: Boolean = false,
        command: suspend MiioClient.() -> Unit,
    ) {
        val lamp = shownLamp(LampKind.PIPI) ?: return
        _ui.update { it.copy(pipi = it.pipi?.change()) }
        send(lamp, reload) { miioOf(lamp).command() }
    }

    // Commands for Yeelight lamps

    fun setYeelightBrightness(value: Int) = yeelight({ copy(brightness = value) }) { setBrightness(value) }
    fun setYeelightColorTemp(value: Int) = yeelight({ copy(colorTemp = value) }) { setColorTemp(value) }
    fun setYeelightColor(hue: Int, saturation: Int) =
        yeelight({ copy(hue = hue, saturation = saturation) }) { setColor(hue, saturation) }

    private fun yeelight(change: YeelightState.() -> YeelightState, command: suspend YeelightClient.() -> Unit) {
        val lamp = shownLamp(LampKind.YEELIGHT) ?: return
        _ui.update { it.copy(yeelight = it.yeelight?.change()) }
        send(lamp) { yeelightOf(lamp).command() }
    }

    // Plumbing

    private fun shownLamp(kind: LampKind): LampConfig? {
        val screen = _ui.value.screen as? Screen.Detail ?: return null
        return _ui.value.lamp(screen.id)?.takeIf { it.kind == kind }
    }

    private fun send(lamp: LampConfig, reload: Boolean = false, command: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                command()
                _ui.update { it.copy(error = null) }
                if (reload) refresh()
            } catch (e: IOException) {
                _ui.update { it.copy(error = describe(lamp, e)) }
                refresh()
            } catch (e: JSONException) {
                _ui.update { it.copy(error = garbled(lamp, e)) }
            }
        }
    }

    private fun miioOf(lamp: LampConfig) = miioClients.getOrPut(lamp.id) { MiioClient(lamp.host, lamp.token) }
    private fun yeelightOf(lamp: LampConfig) = yeelightClients.getOrPut(lamp.id) { YeelightClient(lamp.host) }

    private fun powerOf(on: Boolean) = if (on) Power.ON else Power.OFF

    private fun asFlag(value: Any?) = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun text(@StringRes id: Int, vararg args: Any?): String = getApplication<Application>().getString(id, *args)

    private fun garbled(lamp: LampConfig, e: JSONException) = text(R.string.err_garbled, lamp.name, e.message)

    private fun describe(lamp: LampConfig, e: IOException): String = when (e) {
        is MiioTimeoutException, is SocketTimeoutException, is NoRouteToHostException ->
            text(R.string.err_no_answer, lamp.name)
        is MiioTokenException -> text(R.string.err_token, lamp.name)
        is ConnectException ->
            if (lamp.kind == LampKind.YEELIGHT) text(R.string.err_refused_yeelight, lamp.name)
            else text(R.string.err_no_answer, lamp.name)
        is MiioException, is YeelightException -> text(R.string.err_rejected, lamp.name, e.message)
        else -> text(R.string.err_no_connection, lamp.name, e.message)
    }

    private suspend fun readPipi(c: MiioClient): PipiState {
        suspend fun read(siid: Int, piid: Int): Any? = c.get(siid, piid).also { delay(READ_GAP_MS) }
        suspend fun int(siid: Int, piid: Int) = (read(siid, piid) as? Number)?.toInt() ?: 0
        suspend fun flag(siid: Int, piid: Int) = asFlag(read(siid, piid))
        return PipiState(
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
            val name = MOTION_NAMES[id]?.let { text(it) } ?: parts.getOrNull(2)?.let { encoded ->
                runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull()
            } ?: text(R.string.motion_n, id)
            Motion(id, name)
        }

    private companion object {
        const val READ_GAP_MS = 60L
        const val CALIBRATE_GAP_MS = 1000L
        const val STEER_INTERVAL_MS = 500L
        const val STEER_POLL_MS = 40L
        const val STEER_IDLE_MS = 3000L
        val MOTION_NAMES = mapOf(
            0 to R.string.motion_0,
            1 to R.string.motion_1,
            2 to R.string.motion_2,
            3 to R.string.motion_3,
            4 to R.string.motion_4,
            5 to R.string.motion_5,
            6 to R.string.motion_6,
            7 to R.string.motion_7,
            8 to R.string.motion_8,
            9 to R.string.motion_9,
            10 to R.string.motion_10,
            11 to R.string.motion_11,
            12 to R.string.motion_12,
            13 to R.string.motion_13,
        )
    }
}

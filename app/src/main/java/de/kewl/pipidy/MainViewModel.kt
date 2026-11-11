package de.kewl.pipidy

import android.app.Application
import android.content.Context
import android.os.SystemClock
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
import de.kewl.pipidy.pipi.LampMotion
import de.kewl.pipidy.pipi.LightMode
import de.kewl.pipidy.pipi.PathPoint
import de.kewl.pipidy.pipi.Schedule
import de.kewl.pipidy.pipi.parseFaults
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
    /** A page below the detail screen of a Pipi lamp. */
    data class Sub(val id: String, val page: PipiPage) : Screen
}

sealed interface PipiPage {
    data object Modes : PipiPage
    data class ModeEdit(val modeId: Int?) : PipiPage
    data object Schedules : PipiPage
    data class ScheduleEdit(val scheduleId: Int?) : PipiPage
    data object Motions : PipiPage
    data object Record : PipiPage
    data object Guide : PipiPage
}

/** Lamp whose controls the screen shows, if any. */
val Screen.lampId: String?
    get() = when (this) {
        is Screen.Detail -> id
        is Screen.Sub -> id
        else -> null
    }

data class Motion(val id: Int, val name: String, val custom: Boolean)

data class PipiState(
    /** siid 2 piid 1, the lamp is awake. */
    val on: Boolean,
    /** siid 9 piid 2, the light itself. */
    val lightOn: Boolean,
    val brightness: Int,
    val colorTemp: Int,
    val modes: List<LightMode>,
    val mood: Int,
    val birthday: String,
    val personality: Int,
    val interactionOn: Boolean,
    val followOn: Boolean,
    val lastGesture: Int,
    val timerOn: Boolean,
    val timerMinutes: Int,
    val timerLeft: Int,
    val schedules: List<Schedule>,
    val faults: List<Int>,
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

    /** Opens a page of the Pipi lamp that is shown right now. */
    fun openPage(page: PipiPage) {
        val id = _ui.value.screen.lampId ?: return
        _ui.update { it.copy(screen = Screen.Sub(id, page), error = null) }
    }

    fun back() {
        when (val screen = _ui.value.screen) {
            Screen.Dashboard -> return
            is Screen.Edit -> _ui.update { it.copy(screen = Screen.Detail(screen.id), error = null) }
            is Screen.Sub -> {
                val parent = when (screen.page) {
                    is PipiPage.ModeEdit -> Screen.Sub(screen.id, PipiPage.Modes)
                    is PipiPage.ScheduleEdit -> Screen.Sub(screen.id, PipiPage.Schedules)
                    PipiPage.Record -> Screen.Sub(screen.id, PipiPage.Motions)
                    else -> Screen.Detail(screen.id)
                }
                _ui.update { it.copy(screen = parent, error = null) }
            }
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
                when (val id = _ui.value.screen.lampId) {
                    null -> loadPower()
                    else -> loadDetail(id)
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
        fun shown(state: UiState) = state.screen.lampId == id
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
        val shown = _ui.value.screen.lampId == id
        _ui.update {
            it.copy(
                power = it.power + (id to powerOf(on)),
                pipi = if (shown) it.pipi?.copy(on = on, lightOn = on) else it.pipi,
                yeelight = if (shown) it.yeelight?.copy(on = on) else it.yeelight,
            )
        }
        // The Pipi lamp changes mood and light along with it, so read it again.
        send(lamp, reload = shown && lamp.kind == LampKind.PIPI) {
            when (lamp.kind) {
                LampKind.PIPI -> miioOf(lamp).set(2, 1, on)
                LampKind.YEELIGHT -> yeelightOf(lamp).setPower(on)
            }
        }
    }

    // Commands for the Pipi lamp

    fun setBrightness(value: Int) = pipi({ copy(brightness = value) }) { set(2, 3, value) }
    fun setColorTemp(value: Int) = pipi({ copy(colorTemp = value) }) { set(2, 5, value) }
    fun setLight(on: Boolean) = pipi({ copy(lightOn = on) }, reload = true) { set(9, 2, if (on) 1 else 0) }
    fun runMotion(id: Int) = pipi({ this }) { action(5, 2, listOf(2 to id)) }
    fun setInteraction(on: Boolean) = pipi({ copy(interactionOn = on) }) { set(7, 1, if (on) 1 else 0) }
    fun setFollow(on: Boolean) = pipi({ copy(followOn = on) }) { set(7, 3, if (on) 1 else 0) }
    fun setPersonality(value: Int) = pipi({ copy(personality = value) }) { set(3, 3, value) }
    fun setBirthday(date: String) = pipi({ copy(birthday = date) }) { set(3, 2, date) }
    fun setTimerOn(on: Boolean) = pipi({ copy(timerOn = on) }, reload = true) { set(6, 4, if (on) 1 else 0) }
    fun setTimerMinutes(value: Int) = pipi({ copy(timerMinutes = value) }) { set(6, 5, value) }

    // Light modes (siid 4): the list in piid 1, a written entry adds or replaces the mode with its id.

    fun applyMode(id: Int) = pipi({ copy(modes = modes.map { it.copy(active = it.id == id) }) }, reload = true) {
        action(4, 3, listOf(2 to id))
    }

    /** Saves [mode], or a new mode when its id is 0. Goes back to the list. */
    fun saveMode(mode: LightMode) {
        val modes = _ui.value.pipi?.modes ?: return
        val entry = if (mode.id != 0) mode else mode.copy(
            id = LightMode.freeId(modes) ?: return,
            sort = (modes.maxOfOrNull { it.sort } ?: 0) + 1,
            active = false,
            builtIn = false,
        )
        back()
        pipi({ copy(modes = modes.filterNot { it.id == entry.id } + entry) }, reload = true) { set(4, 1, entry.encode()) }
    }

    fun deleteMode(id: Int) = pipi({ copy(modes = modes.filterNot { it.id == id }) }, reload = true) {
        action(4, 1, listOf(2 to id.toString()))
    }

    // Schedules (siid 6 piid 2), same pattern. The lamp only runs them when it knows the time.

    fun saveSchedule(schedule: Schedule) {
        val schedules = _ui.value.pipi?.schedules ?: return
        val entry = if (schedule.id != 0) schedule else schedule.copy(id = Schedule.nextId(schedules))
        if (_ui.value.screen.let { it is Screen.Sub && it.page is PipiPage.ScheduleEdit }) back()
        pipi({ copy(schedules = schedules.filterNot { it.id == entry.id } + entry) }, reload = true) {
            set(6, 2, entry.encode())
        }
    }

    fun deleteSchedule(id: Int) = pipi({ copy(schedules = schedules.filterNot { it.id == id }) }, reload = true) {
        action(6, 1, listOf(3 to id.toString()))
    }

    // Own motions (siid 5): recorded head paths, stored on the lamp.

    fun saveMotion(name: String, path: List<PathPoint>) {
        val motions = _ui.value.pipi?.motions ?: return
        val id = (motions.maxOfOrNull { it.id } ?: 0) + 1
        back()
        pipi({ copy(motions = motions + Motion(id, name, custom = true)) }, reload = true) {
            set(5, 1, LampMotion.encode(id, name, path))
        }
    }

    fun renameMotion(id: Int, name: String) =
        pipi({ copy(motions = motions.map { if (it.id == id) it.copy(name = name) else it }) }, reload = true) {
            set(5, 1, LampMotion.encode(id, name, null))
        }

    fun deleteMotion(id: Int) = pipi({ copy(motions = motions.filterNot { it.id == id }) }, reload = true) {
        action(5, 1, listOf(2 to id.toString()))
    }

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
        val id = _ui.value.screen.lampId ?: return null
        return _ui.value.lamp(id)?.takeIf { it.kind == kind }
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
        // Small batches keep every answer well inside one UDP packet.
        val values = HashMap<Pair<Int, Int>, Any?>()
        PIPI_PROPS.chunked(READ_BATCH).forEach { batch ->
            batch.zip(c.getAll(batch)).forEach { (prop, value) -> values[prop] = value }
            delay(READ_GAP_MS)
        }
        fun read(siid: Int, piid: Int) = values[siid to piid]
        fun int(siid: Int, piid: Int) = (read(siid, piid) as? Number)?.toInt() ?: 0
        fun flag(siid: Int, piid: Int) = asFlag(read(siid, piid))
        fun string(siid: Int, piid: Int) = (read(siid, piid) as? String).orEmpty()
        return PipiState(
            on = flag(2, 1),
            lightOn = flag(9, 2),
            brightness = int(2, 3).coerceIn(1, 100),
            colorTemp = int(2, 5).coerceIn(2700, 5700),
            modes = LightMode.parseList(string(4, 1)),
            mood = (read(3, 1) as? Number)?.toInt() ?: -1,
            birthday = string(3, 2),
            personality = int(3, 3),
            interactionOn = flag(7, 1),
            followOn = flag(7, 3),
            lastGesture = int(7, 2),
            timerOn = flag(6, 4),
            timerMinutes = int(6, 5),
            timerLeft = int(6, 6),
            schedules = Schedule.parseList(string(6, 2)),
            faults = parseFaults(string(9, 1)),
            firmware = string(1, 4),
            motions = LampMotion.parseList(string(5, 3)).map { motion ->
                val fixed = MOTION_NAMES[motion.id]?.takeIf { motion.builtIn }
                Motion(motion.id, fixed?.let { text(it) } ?: motion.name, custom = !motion.builtIn)
            },
        )
    }

    private companion object {
        const val READ_GAP_MS = 60L
        const val READ_BATCH = 8
        val PIPI_PROPS = listOf(
            2 to 1, 2 to 3, 2 to 5, 9 to 2, 9 to 1, 4 to 1, 5 to 3,
            3 to 1, 3 to 2, 3 to 3, 7 to 1, 7 to 2, 7 to 3,
            6 to 2, 6 to 4, 6 to 5, 6 to 6, 1 to 4,
        )
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

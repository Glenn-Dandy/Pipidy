package de.kewl.pipidy.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.PipiPage
import de.kewl.pipidy.PipiState
import de.kewl.pipidy.R
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig
import de.kewl.pipidy.pipi.LightMode
import de.kewl.pipidy.pipi.MAX_NAME_UNITS
import de.kewl.pipidy.pipi.PATH_MAX_STEPS
import de.kewl.pipidy.pipi.PATH_STEP_MS
import de.kewl.pipidy.pipi.PathPoint
import de.kewl.pipidy.pipi.Schedule
import de.kewl.pipidy.pipi.nameUnits
import kotlinx.coroutines.delay
import java.util.Locale

private val BLINK_STYLES = listOf(
    R.string.blink_style_1, R.string.blink_style_2, R.string.blink_style_3, R.string.blink_style_4, R.string.blink_style_5,
)
private val DAYS_SHORT = listOf(
    R.string.day_short_1, R.string.day_short_2, R.string.day_short_3, R.string.day_short_4,
    R.string.day_short_5, R.string.day_short_6, R.string.day_short_7,
)
/** Monday first for showing, the lamp counts from Sunday. */
private val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val REPEATS = listOf(
    Schedule.ONCE to R.string.repeat_once,
    Schedule.DAILY to R.string.repeat_daily,
    Schedule.WORKDAYS to R.string.repeat_workdays,
    Schedule.WEEKEND to R.string.repeat_weekend,
)

/** Pages below the Pipi lamp screen. */
@Composable
fun PipiPageScreen(ui: UiState, vm: MainViewModel, lamp: LampConfig, page: PipiPage) {
    val title = when (page) {
        PipiPage.Modes -> R.string.modes
        is PipiPage.ModeEdit -> if (page.modeId == null) R.string.mode_new else R.string.mode_edit
        PipiPage.Schedules -> R.string.schedules
        is PipiPage.ScheduleEdit -> if (page.scheduleId == null) R.string.schedule_new else R.string.schedule_edit
        PipiPage.Motions -> R.string.own_motions
        PipiPage.Record -> R.string.record_title
        PipiPage.Guide -> R.string.guide_title
    }
    Page(
        busy = ui.busy,
        error = ui.error,
        onDismissError = vm::dismissError,
        header = { TitleBar(stringResource(title), lamp.name, vm::back) },
    ) {
        val state = ui.pipi
        when {
            page == PipiPage.Guide -> GuidePage()
            state == null ->
                if (ui.error == null) Txt(stringResource(R.string.looking_for_lamp), Type.Small, Modifier.padding(4.dp))
            else -> when (page) {
                PipiPage.Modes -> ModesPage(state, vm)
                is PipiPage.ModeEdit -> ModeEditPage(state, vm, state.modes.firstOrNull { it.id == page.modeId })
                PipiPage.Schedules -> SchedulesPage(state, vm)
                is PipiPage.ScheduleEdit ->
                    ScheduleEditPage(vm, state.schedules.firstOrNull { it.id == page.scheduleId })
                PipiPage.Motions -> MotionsPage(state, vm)
                PipiPage.Record -> RecordPage(state, vm)
                PipiPage.Guide -> Unit
            }
        }
    }
}

// Light modes

@Composable
private fun ModesPage(state: PipiState, vm: MainViewModel) {
    state.modes.forEach { mode ->
        Panel(tint = if (mode.active) Ink.Amber else Color.White) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(modeName(mode), Type.Title, Modifier.weight(1f), maxLines = 1)
                if (mode.active) Label(stringResource(R.string.mode_active), color = Ink.Amber)
            }
            Txt(modeSummary(mode), Type.Small)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(stringResource(R.string.mode_apply), { vm.applyMode(mode.id) }, Modifier.weight(1f), enabled = !mode.active)
                GlassButton(stringResource(R.string.edit), { vm.openPage(PipiPage.ModeEdit(mode.id)) }, Modifier.weight(1f))
            }
        }
    }
    if (LightMode.freeId(state.modes) != null) {
        GlassButton(stringResource(R.string.mode_new), { vm.openPage(PipiPage.ModeEdit(null)) }, Modifier.fillMaxWidth(), primary = true)
    }
}

@Composable
private fun modeSummary(mode: LightMode): String {
    val base = stringResource(R.string.mode_summary, mode.brightness, mode.colorTemp)
    if (mode.blinkStyle == 0) return base
    return "$base · ${stringResource(BLINK_STYLES[mode.blinkStyle - 1])} · ${blinkCount(mode.blinkFreq)}"
}

@Composable
private fun blinkCount(index: Int): String = when (val count = LightMode.BLINK_COUNTS[index]) {
    -1 -> stringResource(R.string.blink_count_endless)
    1 -> stringResource(R.string.blink_count_1)
    2 -> stringResource(R.string.blink_count_2)
    else -> stringResource(R.string.blink_count_n, count)
}

@Composable
private fun ModeEditPage(state: PipiState, vm: MainViewModel, mode: LightMode?) {
    var name by rememberSaveable { mutableStateOf(mode?.name.orEmpty()) }
    var brightness by rememberSaveable { mutableIntStateOf(mode?.brightness ?: 50) }
    var colorTemp by rememberSaveable { mutableIntStateOf(mode?.colorTemp ?: 4000) }
    var blink by rememberSaveable { mutableStateOf((mode?.blinkStyle ?: 0) > 0) }
    var style by rememberSaveable { mutableIntStateOf((mode?.blinkStyle ?: 0).coerceAtLeast(1)) }
    var count by rememberSaveable { mutableIntStateOf(mode?.blinkFreq ?: LightMode.BLINK_COUNTS.size - 1) }

    val fixed = mode?.hasFixedName == true
    val others = state.modes.filter { it.id != mode?.id }.map { modeName(it) }
    val problem = when {
        fixed -> null
        nameUnits(name.trim()) > MAX_NAME_UNITS -> R.string.name_too_long
        name.trim() in others -> R.string.name_taken
        else -> null
    }

    Panel {
        if (fixed) {
            Label(stringResource(R.string.mode_name))
            Txt(modeName(mode!!), Type.Title)
            Txt(stringResource(R.string.mode_fixed_name), Type.Small)
        } else {
            Field(
                stringResource(R.string.mode_name),
                name,
                { name = it },
                placeholder = stringResource(R.string.mode_name_hint),
                error = problem != null,
            )
            problem?.let { Txt(stringResource(it), Type.Small, color = Ink.Red) }
        }
    }
    Panel(stringResource(R.string.light)) {
        ValueSlider(stringResource(R.string.brightness), brightness, 1, 100, " %", { brightness = it })
        ValueSlider(stringResource(R.string.colour_temp), colorTemp, 2700, 5700, " K", { colorTemp = it }, track = WARM_TO_COOL)
    }
    Panel {
        SwitchRow(stringResource(R.string.blink), blink, { blink = it })
        if (blink) {
            Label(stringResource(R.string.blink_style))
            Chips {
                BLINK_STYLES.forEachIndexed { i, label -> Chip(stringResource(label), style == i + 1, { style = i + 1 }) }
            }
            Label(stringResource(R.string.blink_count))
            Chips {
                LightMode.BLINK_COUNTS.indices.forEach { i -> Chip(blinkCount(i), count == i, { count = i }) }
            }
        }
    }
    GlassButton(
        stringResource(R.string.save),
        {
            vm.saveMode(
                LightMode(
                    id = mode?.id ?: 0,
                    sort = mode?.sort ?: 0,
                    active = mode?.active ?: false,
                    builtIn = mode?.builtIn ?: false,
                    name = if (fixed) mode!!.name else name.trim(),
                    brightness = brightness,
                    colorTemp = colorTemp,
                    blinkStyle = if (blink) style else 0,
                    blinkFreq = if (blink) count else 0,
                )
            )
        },
        Modifier.fillMaxWidth(),
        primary = true,
        enabled = problem == null && (fixed || name.isNotBlank()),
    )
    // The lamp keeps the modes it came with.
    if (mode != null && !mode.builtIn) {
        ConfirmButton(stringResource(R.string.mode_delete), Modifier.fillMaxWidth()) {
            vm.deleteMode(mode.id)
            vm.back()
        }
    }
}

// Schedules

@Composable
private fun SchedulesPage(state: PipiState, vm: MainViewModel) {
    Txt(stringResource(R.string.schedules_clock), Type.Small, Modifier.padding(horizontal = 4.dp))
    if (state.schedules.isEmpty()) Txt(stringResource(R.string.schedules_none), Type.Body, Modifier.padding(4.dp))
    state.schedules.forEach { schedule ->
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .glass(strength = if (pressed) 1.8f else 1f)
                .clickable(source, null, role = Role.Button) { vm.openPage(PipiPage.ScheduleEdit(schedule.id)) }
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(clock(schedule.hour, schedule.minute), Type.Title, color = if (schedule.enabled) Ink.Text else Ink.Faint)
                Txt(
                    stringResource(if (schedule.turnOn) R.string.schedule_turn_on else R.string.schedule_turn_off) +
                        " · " + repeatText(schedule.days),
                    Type.Small,
                )
            }
            Toggle(schedule.enabled, { vm.saveSchedule(schedule.copy(enabled = it)) })
        }
    }
    GlassButton(stringResource(R.string.schedule_new), { vm.openPage(PipiPage.ScheduleEdit(null)) }, Modifier.fillMaxWidth(), primary = true)
}

@Composable
private fun repeatText(days: String): String {
    REPEATS.firstOrNull { it.first == days }?.let { return stringResource(it.second) }
    return WEEK_ORDER.filter { days[it] == '1' }.map { stringResource(DAYS_SHORT[it]) }.joinToString(", ")
}

private fun clock(hour: Int, minute: Int) = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

@Composable
private fun ScheduleEditPage(vm: MainViewModel, schedule: Schedule?) {
    val soon = remember { java.time.LocalTime.now().plusMinutes(1) }
    var hour by rememberSaveable { mutableIntStateOf(schedule?.hour ?: soon.hour) }
    var minute by rememberSaveable { mutableIntStateOf(schedule?.minute ?: soon.minute) }
    var turnOn by rememberSaveable { mutableStateOf(schedule?.turnOn ?: true) }
    var days by rememberSaveable { mutableStateOf(schedule?.days ?: Schedule.ONCE) }
    var custom by rememberSaveable { mutableStateOf(schedule != null && REPEATS.none { it.first == schedule.days }) }
    val context = LocalContext.current

    Panel(stringResource(R.string.schedule_time)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(clock(hour, minute), Type.Display, Modifier.weight(1f))
            GlassButton(stringResource(R.string.change), {
                TimePickerDialog(context, { _, h, m ->
                    hour = h
                    minute = m
                }, hour, minute, DateFormat.is24HourFormat(context)).show()
            })
        }
    }
    Panel(stringResource(R.string.schedule_action)) {
        Chips {
            Chip(stringResource(R.string.schedule_turn_on), turnOn, { turnOn = true })
            Chip(stringResource(R.string.schedule_turn_off), !turnOn, { turnOn = false })
        }
    }
    Panel(stringResource(R.string.schedule_repeat)) {
        Chips {
            REPEATS.forEach { (value, label) ->
                Chip(stringResource(label), !custom && days == value, {
                    custom = false
                    days = value
                })
            }
            Chip(stringResource(R.string.repeat_custom), custom, { custom = true })
        }
        if (custom) {
            Chips {
                WEEK_ORDER.forEach { day ->
                    Chip(stringResource(DAYS_SHORT[day]), days[day] == '1', {
                        days = days.toCharArray().also { it[day] = if (it[day] == '1') '0' else '1' }.concatToString()
                    })
                }
            }
        }
    }
    GlassButton(
        stringResource(R.string.save),
        {
            vm.saveSchedule(
                // Saving also switches a schedule back on.
                Schedule(schedule?.id ?: 0, true, hour, minute, days, turnOn)
            )
        },
        Modifier.fillMaxWidth(),
        primary = true,
        // Custom repeat needs at least one day, otherwise it would silently become "once".
        enabled = !custom || '1' in days,
    )
    if (schedule != null) {
        ConfirmButton(stringResource(R.string.mode_delete), Modifier.fillMaxWidth()) {
            vm.deleteSchedule(schedule.id)
            vm.back()
        }
    }
}

// Own motions

@Composable
private fun MotionsPage(state: PipiState, vm: MainViewModel) {
    val own = state.motions.filter { it.custom }
    if (own.isEmpty()) Txt(stringResource(R.string.own_motions_none), Type.Body, Modifier.padding(4.dp))
    own.forEach { motion ->
        var renaming by remember { mutableStateOf(false) }
        var name by remember(motion.name) { mutableStateOf(motion.name) }
        Panel {
            if (renaming) {
                val problem = nameProblem(name, state.motions.filter { it.id != motion.id }.map { it.name })
                Field(stringResource(R.string.mode_name), name, { name = it }, error = problem != null)
                problem?.let { Txt(stringResource(it), Type.Small, color = Ink.Red) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        stringResource(R.string.save),
                        {
                            vm.renameMotion(motion.id, name.trim())
                            renaming = false
                        },
                        Modifier.weight(1f),
                        primary = true,
                        enabled = problem == null && name.isNotBlank(),
                    )
                    GlassButton(stringResource(R.string.form_cancel), {
                        name = motion.name
                        renaming = false
                    }, Modifier.weight(1f))
                }
                ConfirmButton(stringResource(R.string.mode_delete), Modifier.fillMaxWidth()) { vm.deleteMotion(motion.id) }
            } else {
                Txt(motion.name, Type.Title, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(stringResource(R.string.motion_play), { vm.runMotion(motion.id) }, Modifier.weight(1f))
                    GlassButton(stringResource(R.string.edit), { renaming = true }, Modifier.weight(1f))
                }
            }
        }
    }
    GlassButton(stringResource(R.string.motion_record), { vm.openPage(PipiPage.Record) }, Modifier.fillMaxWidth(), primary = true)
}

private fun nameProblem(name: String, taken: List<String>): Int? = when {
    nameUnits(name.trim()) > MAX_NAME_UNITS -> R.string.name_too_long
    name.trim() in taken -> R.string.name_taken
    else -> null
}

/**
 * Records a head path like the plugin does: one step every 50 ms, at most 30 seconds.
 * The dot in the circle is where the head looks; a light chip switches the light in that step.
 */
@Composable
private fun RecordPage(state: PipiState, vm: MainViewModel) {
    val path = remember { ArrayList<PathPoint>() }
    var steps by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Offset.Zero) }
    var light by remember { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (path.size < PATH_MAX_STEPS) {
            path.add(PathPoint((position.x * 100).toInt(), (-position.y * 100).toInt(), light))
            light = 0
            steps = path.size
            delay(PATH_STEP_MS)
        }
        recording = false
    }

    Panel {
        Txt(stringResource(R.string.record_hint), Type.Small)
        ControlPad(onMove = { x, y -> position = Offset(x, y) }, onRelease = {}, springBack = false)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val seconds = steps * PATH_STEP_MS / 1000
            Txt(
                stringResource(R.string.record_time, String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)),
                Type.Mono,
                Modifier.weight(1f),
                color = if (recording) Ink.Red else Ink.Dim,
            )
            when {
                recording -> GlassButton(stringResource(R.string.record_stop), { recording = false }, tint = Ink.Red)
                path.isEmpty() -> GlassButton(stringResource(R.string.record_start), { recording = true }, primary = true)
                else -> GlassButton(stringResource(R.string.record_again), {
                    path.clear()
                    steps = 0
                    recording = true
                })
            }
        }
    }
    Panel(stringResource(R.string.record_light)) {
        Chips {
            Chip(stringResource(R.string.record_light_off), false, { light = -1 }, enabled = recording)
            state.modes.forEach { mode -> Chip(modeName(mode), false, { light = mode.id }, enabled = recording) }
        }
    }
    Panel {
        val problem = nameProblem(name, state.motions.map { it.name })
        Field(stringResource(R.string.mode_name), name, { name = it }, error = problem != null)
        problem?.let { Txt(stringResource(it), Type.Small, color = Ink.Red) }
        GlassButton(
            stringResource(R.string.save),
            { vm.saveMotion(name.trim(), path.toList()) },
            Modifier.fillMaxWidth(),
            primary = true,
            // At least one second, as in the plugin.
            enabled = !recording && steps * PATH_STEP_MS >= 1000 && problem == null && name.isNotBlank(),
        )
    }
}

// Gestures and games

@Composable
private fun GuidePage() {
    Panel(stringResource(R.string.guide_gestures)) {
        Txt(stringResource(R.string.guide_hint), Type.Small)
        listOf(
            R.string.guide_gesture_1 to R.string.guide_gesture_1_text,
            R.string.guide_gesture_2 to R.string.guide_gesture_2_text,
            R.string.guide_gesture_3 to R.string.guide_gesture_3_text,
            R.string.guide_gesture_4 to R.string.guide_gesture_4_text,
        ).forEach { (title, text) -> GuideEntry(title, text) }
    }
    Panel(stringResource(R.string.guide_games)) {
        listOf(
            R.string.game_1 to R.string.game_1_text,
            R.string.game_2 to R.string.game_2_text,
            R.string.game_3 to R.string.game_3_text,
            R.string.game_4 to R.string.game_4_text,
            R.string.game_5 to R.string.game_5_text,
            R.string.game_6 to R.string.game_6_text,
        ).forEach { (title, text) -> GuideEntry(title, text) }
    }
}

@Composable
private fun GuideEntry(title: Int, text: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(stringResource(title), Type.Body, color = Ink.Amber)
        Txt(stringResource(text), Type.Small)
    }
}

/** Asks once more before it acts. */
@Composable
private fun ConfirmButton(text: String, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) {
            delay(4000)
            asking = false
        }
    }
    GlassButton(
        if (asking) stringResource(R.string.mode_delete_confirm) else text,
        { if (asking) onConfirm() else asking = true },
        modifier,
        tint = Ink.Red,
    )
}

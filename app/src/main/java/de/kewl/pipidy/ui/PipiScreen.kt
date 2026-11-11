package de.kewl.pipidy.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.PipiPage
import de.kewl.pipidy.PipiState
import de.kewl.pipidy.R
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig
import de.kewl.pipidy.pipi.LightMode
import de.kewl.pipidy.pipi.daysSince
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

private val PERSONALITIES = listOf(1 to R.string.personality_clingy, 2 to R.string.personality_noble)
private val TIMER_MINUTES = listOf(15, 30, 60, 120)
private val GESTURES = mapOf(
    1 to R.string.gesture_five, 2 to R.string.gesture_four, 3 to R.string.gesture_three,
    4 to R.string.gesture_love, 5 to R.string.gesture_rock, 6 to R.string.gesture_peace,
    7 to R.string.gesture_gun, 8 to R.string.gesture_six, 9 to R.string.gesture_one,
    10 to R.string.gesture_pinky, 11 to R.string.gesture_thumbs_up, 12 to R.string.gesture_ok,
    13 to R.string.gesture_fist, 14 to R.string.gesture_heart, 16 to R.string.gesture_shoot,
    21 to R.string.gesture_open_hand, 22 to R.string.gesture_make_fist, 23 to R.string.gesture_pinch,
)
private val MOODS = mapOf(
    0 to R.string.mood_0, 1 to R.string.mood_1, 2 to R.string.mood_2, 3 to R.string.mood_3,
    5 to R.string.mood_5, 6 to R.string.mood_6, 7 to R.string.mood_7,
)
private val FIXED_MODE_NAMES = listOf(
    R.string.mode_1, R.string.mode_2, R.string.mode_3, R.string.mode_4, R.string.mode_5, R.string.mode_6, R.string.mode_7,
)
private val FAULTS = mapOf(
    1 to (R.string.fault_1_title to R.string.fault_1_text),
    2 to (R.string.fault_2_title to R.string.fault_2_text),
    3 to (R.string.fault_3_title to R.string.fault_3_text),
)

/** Name of a light mode, translated for the seven modes the lamp comes with. */
@Composable
fun modeName(mode: LightMode): String =
    if (mode.hasFixedName) stringResource(FIXED_MODE_NAMES[mode.id - 1]) else mode.name

@Composable
fun PipiScreen(ui: UiState, vm: MainViewModel, lamp: LampConfig) {
    Page(
        busy = ui.busy,
        error = ui.error,
        onDismissError = vm::dismissError,
        header = {
            TitleBar(lamp.name, stringResource(lamp.kind.label), vm::back) {
                IconSquare(Glyph.Refresh, stringResource(R.string.refresh), vm::refresh)
                IconSquare(Glyph.Tune, stringResource(R.string.edit), { vm.openEdit(lamp.id) })
            }
        },
    ) {
        val state = ui.pipi
        if (state == null) {
            if (ui.error == null) Txt(stringResource(R.string.looking_for_lamp), Type.Small, Modifier.padding(4.dp))
        } else {
            state.faults.forEach { FaultPanel(it) }
            LightPanel(state, vm, lamp)
            RemotePanel(vm)
            MotionPanel(state, vm)
            CharacterPanel(state, vm)
            InteractionPanel(state, vm)
            TimerPanel(state, vm)
            Label(stringResource(R.string.firmware, state.firmware), Modifier.padding(4.dp), color = Ink.Faint)
        }
    }
}

@Composable
private fun FaultPanel(code: Int) = Panel(tint = Ink.Red) {
    val texts = FAULTS[code]
    Txt(stringResource(texts?.first ?: R.string.fault_other_title), Type.Title)
    Txt(texts?.let { stringResource(it.second) } ?: stringResource(R.string.fault_other_text, code), Type.Small)
}

@Composable
private fun LightPanel(state: PipiState, vm: MainViewModel, lamp: LampConfig) = Panel(stringResource(R.string.light)) {
    SwitchRow(stringResource(R.string.lamp_awake), state.on, { vm.setPower(lamp.id, it) })
    SwitchRow(stringResource(R.string.light_on), state.lightOn, vm::setLight, enabled = state.on)
    ValueSlider(stringResource(R.string.brightness), state.brightness, 1, 100, " %", vm::setBrightness)
    ValueSlider(
        stringResource(R.string.colour_temp),
        state.colorTemp,
        2700,
        5700,
        " K",
        vm::setColorTemp,
        track = WARM_TO_COOL,
    )
    if (state.modes.isNotEmpty()) {
        Label(stringResource(R.string.modes), Modifier.padding(top = 4.dp))
        Chips {
            state.modes.forEach { mode -> Chip(modeName(mode), mode.active, { vm.applyMode(mode.id) }) }
        }
    }
    GlassButton(stringResource(R.string.modes_manage), { vm.openPage(PipiPage.Modes) }, Modifier.fillMaxWidth())
}

/** Closed by default, so the page scrolls freely past the steering circle. */
@Composable
private fun RemotePanel(vm: MainViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    FoldPanel(stringResource(R.string.remote), open, { open = !open }) {
        // A drag that is still running when the panel closes must not keep the head turning.
        DisposableEffect(Unit) { onDispose { vm.steerEnd() } }
        ControlPad(onMove = vm::steer, onRelease = vm::steerEnd)
        Txt(stringResource(R.string.remote_hint), Type.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(stringResource(R.string.centre_head), vm::centreHead, Modifier.weight(1f))
            GlassButton(stringResource(R.string.calibrate), vm::calibrate, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MotionPanel(state: PipiState, vm: MainViewModel) = Panel(stringResource(R.string.motions)) {
    if (state.motions.isEmpty()) {
        Txt(stringResource(R.string.no_motions), Type.Small)
    } else {
        Chips {
            state.motions.forEach { motion -> Chip(motion.name, false, { vm.runMotion(motion.id) }) }
        }
    }
    GlassButton(stringResource(R.string.own_motions), { vm.openPage(PipiPage.Motions) }, Modifier.fillMaxWidth())
}

@Composable
private fun CharacterPanel(state: PipiState, vm: MainViewModel) = Panel(stringResource(R.string.character)) {
    val mood = if (!state.on) R.string.mood_sleep else MOODS[state.mood]
    mood?.let { Txt(stringResource(R.string.mood_now, stringResource(it)), Type.Body) }

    Label(stringResource(R.string.personality), Modifier.padding(top = 4.dp))
    Chips {
        PERSONALITIES.forEach { (value, label) ->
            Chip(stringResource(label), state.personality == value, { vm.setPersonality(value) })
        }
    }
    Txt(stringResource(R.string.personality_hint), Type.Small)

    val context = LocalContext.current
    val birthday = parseDate(state.birthday)
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(stringResource(R.string.birthday))
            Txt(
                birthday?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                    ?: stringResource(R.string.birthday_none),
                Type.Body,
            )
            daysSince(state.birthday)?.let { Txt(stringResource(R.string.together_days, it.toInt()), Type.Small) }
        }
        GlassButton(stringResource(R.string.change), {
            val start = birthday ?: LocalDate.now()
            DatePickerDialog(context, { _, year, month, day ->
                vm.setBirthday(LocalDate.of(year, month + 1, day).toString())
            }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
                // The lamp cannot be born in the future.
                datePicker.maxDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() +
                    DAY_MS - 1
                show()
            }
        })
    }
}

@Composable
private fun InteractionPanel(state: PipiState, vm: MainViewModel) = Panel(stringResource(R.string.interaction)) {
    SwitchRow(stringResource(R.string.interaction_on), state.interactionOn, vm::setInteraction)
    Txt(stringResource(R.string.interaction_hint), Type.Small)
    SwitchRow(stringResource(R.string.follow), state.followOn, vm::setFollow, enabled = state.interactionOn)
    Txt(stringResource(R.string.follow_hint), Type.Small)
    GESTURES[state.lastGesture]?.let { Txt(stringResource(R.string.last_gesture, stringResource(it)), Type.Small) }
    GlassButton(stringResource(R.string.guide_open), { vm.openPage(PipiPage.Guide) }, Modifier.fillMaxWidth())
}

@Composable
private fun TimerPanel(state: PipiState, vm: MainViewModel) = Panel(stringResource(R.string.timer)) {
    SwitchRow(
        if (state.timerOn) stringResource(R.string.timer_active, state.timerLeft) else stringResource(R.string.timer_off),
        state.timerOn,
        vm::setTimerOn,
    )
    Chips {
        TIMER_MINUTES.forEach { minutes ->
            Chip(stringResource(R.string.minutes, minutes), state.timerMinutes == minutes, { vm.setTimerMinutes(minutes) })
        }
    }
    GlassButton(
        if (state.schedules.isEmpty()) stringResource(R.string.schedules)
        else stringResource(R.string.schedules_count, state.schedules.size),
        { vm.openPage(PipiPage.Schedules) },
        Modifier.fillMaxWidth(),
    )
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim())
} catch (_: DateTimeParseException) {
    null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

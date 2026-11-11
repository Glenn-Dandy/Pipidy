package de.kewl.pipidy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.PipiState
import de.kewl.pipidy.R
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig

private val MODES = listOf(
    0 to R.string.mode_normal,
    4 to R.string.mode_night,
    3 to R.string.mode_warm,
    1 to R.string.mode_computer,
    2 to R.string.mode_office,
    255 to R.string.mode_entertainment,
)
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
            LightPanel(state, vm, lamp)
            RemotePanel(vm)
            MotionPanel(state, vm)
            BehaviourPanel(state, vm)
            TimerPanel(state, vm)
            Label(stringResource(R.string.firmware, state.firmware), Modifier.padding(4.dp), color = Ink.Faint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightPanel(state: PipiState, vm: MainViewModel, lamp: LampConfig) = Panel(stringResource(R.string.light)) {
    SwitchRow(stringResource(R.string.switched_on), state.on, { vm.setPower(lamp.id, it) })
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
    Chips {
        MODES.forEach { (value, label) -> Chip(stringResource(label), state.mode == value, { vm.setMode(value) }) }
    }
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
}

@Composable
private fun BehaviourPanel(state: PipiState, vm: MainViewModel) = Panel(stringResource(R.string.behaviour)) {
    SwitchRow(stringResource(R.string.gestures), state.gesturesOn, vm::setGestures)
    SwitchRow(stringResource(R.string.follow), state.followOn, vm::setFollow)
    Label(stringResource(R.string.personality), Modifier.padding(top = 4.dp))
    Chips {
        PERSONALITIES.forEach { (value, label) ->
            Chip(stringResource(label), state.personality == value, { vm.setPersonality(value) })
        }
    }
    Txt(stringResource(R.string.mood, state.mood), Type.Small)
    GESTURES[state.lastGesture]?.let { Txt(stringResource(R.string.last_gesture, stringResource(it)), Type.Small) }
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

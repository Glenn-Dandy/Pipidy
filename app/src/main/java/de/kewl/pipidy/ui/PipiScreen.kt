package de.kewl.pipidy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.PipiState
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig

private val MODES = listOf(
    0 to "Normal",
    4 to "Nachtlicht",
    3 to "Warm",
    1 to "Computer",
    2 to "Büro",
    255 to "Unterhaltung",
)
private val PERSONALITIES = listOf(1 to "Anhänglich", 2 to "Nobel")
private val TIMER_MINUTES = listOf(15, 30, 60, 120)
private val GESTURES = mapOf(
    1 to "Fünf", 2 to "Vier", 3 to "Drei", 4 to "Love", 5 to "Rock", 6 to "Peace", 7 to "Pistole",
    8 to "Sechs", 9 to "Eins", 10 to "Kleiner Finger", 11 to "Daumen hoch", 12 to "OK", 13 to "Faust",
    14 to "Herz", 16 to "Schuss", 21 to "Hand strecken", 22 to "Faust ballen", 23 to "Finger kneifen",
)

@Composable
fun PipiScreen(ui: UiState, vm: MainViewModel, lamp: LampConfig) {
    Page(
        busy = ui.busy,
        error = ui.error,
        onDismissError = vm::dismissError,
        header = {
            TitleBar(lamp.name, lamp.kind.label, vm::back) {
                IconSquare(Glyph.Refresh, "Aktualisieren", vm::refresh)
                IconSquare(Glyph.Tune, "Bearbeiten", { vm.openEdit(lamp.id) })
            }
        },
    ) {
        val state = ui.pipi
        if (state == null) {
            if (ui.error == null) Txt("Lampe wird gesucht …", Type.Small, Modifier.padding(4.dp))
        } else {
            LightPanel(state, vm, lamp)
            SteeringPanel(vm)
            MotionPanel(state, vm)
            BehaviourPanel(state, vm)
            TimerPanel(state, vm)
            Label("Firmware ${state.firmware}", Modifier.padding(4.dp), color = Ink.Faint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightPanel(state: PipiState, vm: MainViewModel, lamp: LampConfig) = Panel("Licht") {
    SwitchRow("Eingeschaltet", state.on, { vm.setPower(lamp.id, it) })
    ValueSlider("Helligkeit", state.brightness, 1, 100, " %", vm::setBrightness)
    ValueSlider("Farbtemperatur", state.colorTemp, 2700, 5700, " K", vm::setColorTemp, track = WARM_TO_COOL)
    Chips {
        MODES.forEach { (value, label) -> Chip(label, state.mode == value, { vm.setMode(value) }) }
    }
}

@Composable
private fun SteeringPanel(vm: MainViewModel) = Panel("Steuerung") {
    ControlPad(onMove = vm::steer, onRelease = vm::steerEnd)
    Txt("Die Richtungsdaten der Lampe sind noch nicht entschlüsselt. Der Kreis sendet deshalb noch nichts.", Type.Small)
    GlassButton("Kalibrieren", vm::calibrate, Modifier.fillMaxWidth())
}

@Composable
private fun MotionPanel(state: PipiState, vm: MainViewModel) = Panel("Bewegungen") {
    if (state.motions.isEmpty()) {
        Txt("Die Lampe meldet keine Bewegungen.", Type.Small)
    } else {
        Chips {
            state.motions.forEach { motion -> Chip(motion.name, false, { vm.runMotion(motion.id) }) }
        }
    }
}

@Composable
private fun BehaviourPanel(state: PipiState, vm: MainViewModel) = Panel("Verhalten") {
    SwitchRow("Gesten erkennen", state.gesturesOn, vm::setGestures)
    SwitchRow("Mitbewegen", state.followOn, vm::setFollow)
    Label("Persönlichkeit", Modifier.padding(top = 4.dp))
    Chips {
        PERSONALITIES.forEach { (value, label) -> Chip(label, state.personality == value, { vm.setPersonality(value) }) }
    }
    Txt("Laune: ${state.mood}", Type.Small)
    GESTURES[state.lastGesture]?.let { Txt("Letzte Geste: $it", Type.Small) }
}

@Composable
private fun TimerPanel(state: PipiState, vm: MainViewModel) = Panel("Ausschalt-Timer") {
    SwitchRow(if (state.timerOn) "Aktiv, noch ${state.timerLeft} Min." else "Aus", state.timerOn, vm::setTimerOn)
    Chips {
        TIMER_MINUTES.forEach { minutes ->
            Chip("$minutes Min.", state.timerMinutes == minutes, { vm.setTimerMinutes(minutes) })
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

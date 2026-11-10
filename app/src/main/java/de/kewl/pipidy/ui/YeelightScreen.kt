package de.kewl.pipidy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig

val WARM_TO_COOL = Brush.horizontalGradient(listOf(Color(0xFFFF9A3C), Color(0xFFFFE2B8), Color(0xFFCFE4FF)))
private val RAINBOW = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })

private const val YEELIGHT_MIN_KELVIN = 1700
private const val YEELIGHT_MAX_KELVIN = 6500

@Composable
fun YeelightScreen(ui: UiState, vm: MainViewModel, lamp: LampConfig) {
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
        val state = ui.yeelight
        if (state == null) {
            if (ui.error == null) Txt("Lampe wird gesucht …", Type.Small, Modifier.padding(4.dp))
            return@Page
        }
        // The lamp refuses brightness and colour while it is switched off.
        Panel("Licht") {
            SwitchRow("Eingeschaltet", state.on, { vm.setPower(lamp.id, it) })
            ValueSlider("Helligkeit", state.brightness, 1, 100, " %", vm::setYeelightBrightness, enabled = state.on)
            state.colorTemp?.let { kelvin ->
                ValueSlider(
                    "Farbtemperatur",
                    kelvin.coerceIn(YEELIGHT_MIN_KELVIN, YEELIGHT_MAX_KELVIN),
                    YEELIGHT_MIN_KELVIN,
                    YEELIGHT_MAX_KELVIN,
                    " K",
                    vm::setYeelightColorTemp,
                    enabled = state.on,
                    track = WARM_TO_COOL,
                )
            }
        }
        val hue = state.hue
        val saturation = state.saturation
        if (hue != null && saturation != null) {
            Panel("Farbe") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Color.hsv(hue.coerceIn(0, 359).toFloat(), saturation.coerceIn(0, 100) / 100f, 1f))
                        .border(1.dp, Color.White.copy(alpha = 0.4f))
                )
                ValueSlider(
                    "Farbton",
                    hue.coerceIn(0, 359),
                    0,
                    359,
                    "°",
                    { vm.setYeelightColor(it, saturation) },
                    enabled = state.on,
                    track = RAINBOW,
                )
                ValueSlider(
                    "Sättigung",
                    saturation.coerceIn(0, 100),
                    0,
                    100,
                    " %",
                    { vm.setYeelightColor(hue, it) },
                    enabled = state.on,
                )
            }
        }
    }
}

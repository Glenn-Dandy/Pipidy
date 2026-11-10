package de.kewl.pipidy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.Power
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig

private val TILE_HEIGHT = 184.dp

@Composable
fun DashboardScreen(ui: UiState, vm: MainViewModel) {
    Page(
        busy = ui.busy,
        error = ui.error,
        onDismissError = vm::dismissError,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("PIPIDY", Type.Wordmark)
                    Txt("Lampen", Type.Display)
                }
                IconSquare(Glyph.Refresh, "Aktualisieren", vm::refresh)
            }
        },
    ) {
        if (ui.lamps.isNotEmpty()) {
            val on = ui.lamps.count { ui.power[it.id] == Power.ON }
            Label("$on von ${ui.lamps.size} an", Modifier.padding(bottom = 4.dp))
        }
        val tiles: List<LampConfig?> = ui.lamps + null
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { lamp ->
                    if (lamp == null) {
                        AddTile(first = ui.lamps.isEmpty(), onClick = vm::openAdd, modifier = Modifier.weight(1f))
                    } else {
                        LampTile(
                            lamp = lamp,
                            power = ui.power[lamp.id] ?: Power.UNKNOWN,
                            onOpen = { vm.openLamp(lamp.id) },
                            onToggle = { vm.togglePower(lamp.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LampTile(lamp: LampConfig, power: Power, onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier) {
    val on = power == Power.ON
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier
            .height(TILE_HEIGHT)
            .halo(Ink.Amber, on)
            .glass(if (on) Ink.Amber else Color.White, if (pressed) 1.8f else 1f)
            .clickable(source, null, onClickLabel = "Öffnen", onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(lamp.kind.label, Modifier.weight(1f))
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when (power) {
                            Power.ON -> Ink.Amber
                            Power.OFF -> Ink.Faint
                            Power.OFFLINE -> Ink.Red
                            Power.UNKNOWN -> Color.Transparent
                        }
                    )
            )
        }
        Spacer(Modifier.weight(1f))
        Txt(lamp.name, Type.Title, maxLines = 2)
        Txt(
            when (power) {
                Power.ON -> "An"
                Power.OFF -> "Aus"
                Power.OFFLINE -> "Nicht erreichbar"
                Power.UNKNOWN -> "…"
            },
            Type.Small,
            Modifier.padding(top = 2.dp, bottom = 12.dp),
            color = if (power == Power.OFFLINE) Ink.Red else Ink.Dim,
        )
        PowerButton(on, lamp.name, onToggle)
    }
}

@Composable
private fun PowerButton(on: Boolean, name: String, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(
                if (on) Modifier.background(Ink.Amber.copy(alpha = if (pressed) 0.75f else 1f))
                else Modifier.glass(strength = if (pressed) 2.2f else 1f)
            )
            .clickable(source, null, role = Role.Switch, onClick = onToggle)
            .semantics { contentDescription = if (on) "$name ausschalten" else "$name einschalten" },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Power, if (on) Ink.OnAmber else Ink.Text)
    }
}

@Composable
private fun AddTile(first: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .height(TILE_HEIGHT)
            .glass(strength = if (pressed) 1.6f else 0.45f)
            .clickable(source, null, role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphIcon(Glyph.Plus, Ink.Text, Modifier.size(34.dp))
            Label(if (first) "Erste Lampe" else "Neue Lampe")
        }
    }
}

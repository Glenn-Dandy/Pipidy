package de.kewl.pipidy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.DEFAULT_PIPI_HOST
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.UiState
import de.kewl.pipidy.data.LampConfig
import de.kewl.pipidy.data.LampKind

private val TOKEN = Regex("[0-9a-fA-F]{32}")

/** Adds a lamp, or edits [editing] when given. */
@Composable
fun LampFormScreen(ui: UiState, vm: MainViewModel, editing: LampConfig?) {
    var kind by remember { mutableStateOf(editing?.kind) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var host by remember { mutableStateOf(editing?.host.orEmpty()) }
    var token by remember { mutableStateOf(editing?.token.orEmpty()) }
    var confirmRemove by remember { mutableStateOf(false) }

    val tokenValid = token.trim().matches(TOKEN)
    val valid = name.isNotBlank() && host.isNotBlank() && (kind != LampKind.PIPI || tokenValid)

    Page(
        busy = ui.searching,
        error = ui.error,
        onDismissError = vm::dismissError,
        header = {
            TitleBar(
                title = editing?.name ?: "Neue Lampe",
                kicker = if (editing == null) "Hinzufügen" else "Bearbeiten",
                onBack = vm::back,
            )
        },
    ) {
        if (editing == null) {
            Label("Welche Lampe?", Modifier.padding(bottom = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindTile("Pipi-Lampe", "Mijia, mit Token", kind == LampKind.PIPI, Modifier.weight(1f)) {
                    kind = LampKind.PIPI
                    if (host.isBlank()) host = DEFAULT_PIPI_HOST
                }
                KindTile("Yeelight", "LAN-Steuerung", kind == LampKind.YEELIGHT, Modifier.weight(1f)) {
                    if (kind == LampKind.PIPI && host == DEFAULT_PIPI_HOST) host = ""
                    kind = LampKind.YEELIGHT
                }
            }
        }

        val chosen = kind ?: return@Page
        Panel {
            Field("Name", name, { name = it }, placeholder = "z. B. Schreibtisch")
            Field("IP-Adresse", host, { host = it }, placeholder = "192.168.…", mono = true, keyboard = KeyboardType.Uri)
            if (chosen == LampKind.PIPI) {
                Field(
                    "Token (32 Zeichen)",
                    token,
                    { token = it },
                    mono = true,
                    error = token.isNotEmpty() && !tokenValid,
                    keyboard = KeyboardType.Ascii,
                )
                Txt("Pipidy spricht direkt mit der Lampe in deinem Netz. Es geht nichts ins Internet.", Type.Small)
            } else {
                Txt("In der Yeelight-App muss bei der Lampe die LAN-Steuerung eingeschaltet sein.", Type.Small)
            }
        }

        if (chosen == LampKind.YEELIGHT) {
            Panel("Im WLAN gefunden") {
                ui.found.forEach { found ->
                    FoundRow(found.host, listOf(found.name, found.model).filter { it.isNotBlank() }.joinToString(" · "), host == found.host) {
                        host = found.host
                        if (name.isBlank() && found.name.isNotBlank()) name = found.name
                    }
                }
                if (ui.searched && ui.found.isEmpty() && !ui.searching) {
                    Txt("Keine Yeelight gefunden. Du kannst die IP-Adresse auch von Hand eintragen.", Type.Small)
                }
                GlassButton(if (ui.searching) "Suche läuft" else "Im WLAN suchen", vm::searchYeelights, enabled = !ui.searching)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(
                if (editing == null) "Hinzufügen" else "Speichern",
                onClick = {
                    if (editing == null) vm.addLamp(name, chosen, host, token) else vm.updateLamp(editing.id, name, host, token)
                },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = valid,
            )
            GlassButton("Abbrechen", vm::back, Modifier.weight(1f))
        }

        if (editing != null) {
            GlassButton(
                if (confirmRemove) "Wirklich entfernen?" else "Lampe entfernen",
                onClick = { if (confirmRemove) vm.removeLamp(editing.id) else confirmRemove = true },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                tint = Ink.Red,
            )
        }
    }
}

@Composable
private fun KindTile(title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier
            .height(104.dp)
            .halo(Ink.Amber, selected)
            .glass(if (selected) Ink.Amber else Color.White, if (pressed || selected) 1.8f else 1f)
            .clickable(source, null, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Txt(title, Type.Title, maxLines = 1)
        Txt(hint, Type.Small, maxLines = 1)
    }
}

@Composable
private fun FoundRow(host: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .glass(if (selected) Ink.Amber else Color.White, if (pressed || selected) 1.8f else 1f)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(host, Type.Mono, Modifier.weight(1f), maxLines = 1)
        Txt(detail, Type.Small, maxLines = 1)
    }
}

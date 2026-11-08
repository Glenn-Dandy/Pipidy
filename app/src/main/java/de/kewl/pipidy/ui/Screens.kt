package de.kewl.pipidy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.DEFAULT_HOST
import de.kewl.pipidy.LampState
import de.kewl.pipidy.LampViewModel
import de.kewl.pipidy.UiState
import kotlin.math.roundToInt

private val Amber = Color(0xFFFFB238)
private val AmberDark = Color(0xFF8A5A00)
private val Cream = Color(0xFFFFF8EC)
private val Night = Color(0xFF1E1812)
private val NightCard = Color(0xFF2B2118)

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
fun PipidyTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Amber, onPrimary = Night, background = Night, surface = Night, surfaceVariant = NightCard)
    } else {
        lightColorScheme(primary = AmberDark, background = Cream, surface = Cream)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SetupScreen(ui: UiState, vm: LampViewModel) {
    var host by remember { mutableStateOf(ui.host.ifBlank { DEFAULT_HOST }) }
    var token by remember { mutableStateOf(ui.token) }
    val tokenValid = token.trim().matches(Regex("[0-9a-fA-F]{32}"))

    if (ui.editingConfig) BackHandler { vm.editConfig(false) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Pipidy einrichten", style = MaterialTheme.typography.headlineMedium)
            Text("Pipidy spricht direkt mit deiner Lampe in deinem Netz. Es geht nichts ins Internet.")
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP-Adresse der Lampe") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token (32 Zeichen)") },
                singleLine = true,
                isError = token.isNotEmpty() && !tokenValid,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveConfig(host, token) }, enabled = tokenValid && host.isNotBlank()) {
                    Text("Verbinden")
                }
                if (ui.editingConfig) {
                    TextButton(onClick = { vm.editConfig(false) }) { Text("Abbrechen") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LampScreen(ui: UiState, vm: LampViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pipidy") },
                actions = {
                    if (ui.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Aktualisieren") }
                    }
                    IconButton(onClick = { vm.editConfig(true) }) { Icon(Icons.Filled.Settings, "Einrichtung") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(onClick = vm::dismissError) { Text("OK") }
                    }
                }
            }
            val lamp = ui.lamp
            if (lamp == null) {
                if (ui.error == null) Text("Lampe wird gesucht …", Modifier.padding(8.dp))
            } else {
                LightCard(lamp, vm)
                MotionCard(lamp, vm)
                BehaviourCard(lamp, vm)
                TimerCard(lamp, vm)
                Text(
                    "Firmware ${lamp.firmware}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightCard(lamp: LampState, vm: LampViewModel) = Section("Licht") {
    SwitchRow("Eingeschaltet", lamp.on, vm::setOn)
    ValueSlider("Helligkeit", lamp.brightness, 1, 100, "%", vm::setBrightness)
    ValueSlider("Farbtemperatur", lamp.colorTemp, 2700, 5700, " K", vm::setColorTemp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MODES.forEach { (value, label) ->
            FilterChip(selected = lamp.mode == value, onClick = { vm.setMode(value) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotionCard(lamp: LampState, vm: LampViewModel) = Section("Bewegungen") {
    if (lamp.motions.isEmpty()) {
        Text("Die Lampe meldet keine Bewegungen.")
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lamp.motions.forEach { motion ->
                FilledTonalButton(onClick = { vm.runMotion(motion.id) }) { Text(motion.name) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BehaviourCard(lamp: LampState, vm: LampViewModel) = Section("Verhalten") {
    SwitchRow("Gesten erkennen", lamp.gesturesOn, vm::setGestures)
    SwitchRow("Mitbewegen", lamp.followOn, vm::setFollow)
    Text("Persönlichkeit", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PERSONALITIES.forEach { (value, label) ->
            FilterChip(selected = lamp.personality == value, onClick = { vm.setPersonality(value) }, label = { Text(label) })
        }
    }
    Text("Laune: ${lamp.mood}")
    GESTURES[lamp.lastGesture]?.let { Text("Letzte Geste: $it") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerCard(lamp: LampState, vm: LampViewModel) = Section("Ausschalt-Timer") {
    SwitchRow(if (lamp.timerOn) "Aktiv, noch ${lamp.timerLeft} Min." else "Aus", lamp.timerOn, vm::setTimerOn)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TIMER_MINUTES.forEach { minutes ->
            FilterChip(
                selected = lamp.timerMinutes == minutes,
                onClick = { vm.setTimerMinutes(minutes) },
                label = { Text("$minutes Min.") },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueSlider(label: String, value: Int, min: Int, max: Int, unit: String, onDone: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text("${position.roundToInt()}$unit")
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            valueRange = min.toFloat()..max.toFloat(),
            onValueChangeFinished = { onDone(position.roundToInt()) },
        )
    }
}

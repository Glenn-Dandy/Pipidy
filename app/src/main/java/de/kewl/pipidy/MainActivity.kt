package de.kewl.pipidy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import de.kewl.pipidy.data.LampKind
import de.kewl.pipidy.ui.AboutScreen
import de.kewl.pipidy.ui.Backdrop
import de.kewl.pipidy.ui.DashboardScreen
import de.kewl.pipidy.ui.LampFormScreen
import de.kewl.pipidy.ui.PipiPageScreen
import de.kewl.pipidy.ui.PipiScreen
import de.kewl.pipidy.ui.YeelightScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val ui by vm.ui.collectAsState()
            // Every screen keeps its own scroll position and fields, also when coming back to it.
            val saved = rememberSaveableStateHolder()
            var shown by remember { mutableStateOf(ui.screen) }
            if (shown != ui.screen) {
                // A screen left backwards starts fresh next time, so forms open empty again.
                if (depth(ui.screen) < depth(shown)) saved.removeState(shown.toString())
                shown = ui.screen
            }
            BackHandler(enabled = ui.screen != Screen.Dashboard) { vm.back() }
            Backdrop {
                saved.SaveableStateProvider(ui.screen.toString()) {
                    when (val screen = ui.screen) {
                        Screen.Dashboard -> DashboardScreen(ui, vm)
                        Screen.Add -> LampFormScreen(ui, vm, editing = null)
                        Screen.About -> AboutScreen(vm)
                        is Screen.Edit -> ui.lamp(screen.id)?.let { LampFormScreen(ui, vm, editing = it) }
                        is Screen.Detail -> ui.lamp(screen.id)?.let { lamp ->
                            when (lamp.kind) {
                                LampKind.PIPI -> PipiScreen(ui, vm, lamp)
                                LampKind.YEELIGHT -> YeelightScreen(ui, vm, lamp)
                            }
                        }
                        is Screen.Sub -> ui.lamp(screen.id)?.let { PipiPageScreen(ui, vm, it, screen.page) }
                    }
                }
            }
        }
    }

    private fun depth(screen: Screen): Int = when (screen) {
        Screen.Dashboard -> 0
        Screen.Add, Screen.About, is Screen.Detail -> 1
        is Screen.Edit -> 2
        is Screen.Sub -> when (screen.page) {
            is PipiPage.ModeEdit, is PipiPage.ScheduleEdit, PipiPage.Record -> 3
            else -> 2
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

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
import androidx.compose.runtime.key
import de.kewl.pipidy.data.LampKind
import de.kewl.pipidy.ui.AboutScreen
import de.kewl.pipidy.ui.Backdrop
import de.kewl.pipidy.ui.DashboardScreen
import de.kewl.pipidy.ui.LampFormScreen
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
            BackHandler(enabled = ui.screen != Screen.Dashboard) { vm.back() }
            Backdrop {
                // key() gives every screen its own scroll position and form fields.
                key(ui.screen) {
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
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

package de.kewl.pipidy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.kewl.pipidy.ui.LampScreen
import de.kewl.pipidy.ui.PipidyTheme
import de.kewl.pipidy.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: LampViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PipidyTheme {
                val ui by vm.ui.collectAsState()
                if (!ui.configured || ui.editingConfig) {
                    SetupScreen(ui, vm)
                } else {
                    LampScreen(ui, vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

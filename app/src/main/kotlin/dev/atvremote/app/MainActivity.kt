package dev.atvremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF00203F),
    surface = Color(0xFF16161B),
    onSurface = Color(0xFFE6E6EA),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF23232B),
    onSurfaceVariant = Color(0xFFB9B9C4),
    error = Color(0xFFFF6B6B),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: RemoteViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val screen = state.screen) {
            is Screen.DeviceList -> DeviceListScreen(state, vm)
            is Screen.PinEntry -> PinEntryScreen(screen.device, state, vm)
            is Screen.Remote -> RemoteScreen(screen.device, state, vm)
        }
    }
}

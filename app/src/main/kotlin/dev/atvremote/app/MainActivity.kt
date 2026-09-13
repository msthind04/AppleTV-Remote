package dev.atvremote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

private val DarkColors = darkColorScheme(
    primaryContainer = Color(0xFF2C2C2E),
    onPrimaryContainer = Color(0xFFFFFFFF),
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

private val LightColors = lightColorScheme(
    // Apple-native button look: charcoal keys with white foreground on the
    // pale remote body.
    primaryContainer = Color(0xFF1C1C1E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    primary = Color(0xFF0A5AC8),
    onPrimary = Color.White,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1C),
    background = Color(0xFFF2F2F6),
    onBackground = Color(0xFF1A1A1C),
    surfaceVariant = Color(0xFFE4E4EB),
    onSurfaceVariant = Color(0xFF54545E),
    error = Color(0xFFB3261E),
)

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    // Registered unconditionally: the contract has to be in place before the
    // activity resumes, whether or not this build will ever ask.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotifications()
        setContent {
            // The remote follows the system just like the TV does: charcoal
            // surfaces in the dark, a pale aluminium look in the light.
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }

    /**
     * While the remote is on screen and the TV can route volume, the phone's
     * volume keys drive the TV's volume instead of the phone's. Key repeats
     * arrive as further ACTION_DOWNs, so holding a key steps continuously.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            if (vm.handlesVolumeKeys()) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    vm.onVolumeKey(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Without this the now-playing notification is posted and silently
     * dropped. Asked for up front rather than at the moment of connecting, so
     * the prompt does not land on top of a pairing code.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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

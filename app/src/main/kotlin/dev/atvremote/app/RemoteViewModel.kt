package dev.atvremote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.companion.AppInfo
import dev.atvremote.protocol.companion.AppleTvRemote
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.CompanionClient
import dev.atvremote.protocol.companion.MediaCapabilities
import dev.atvremote.protocol.airplay.Ap2Session
import dev.atvremote.protocol.airplay.AirPlayAuth
import dev.atvremote.protocol.airplay.AirPlayConnection
import dev.atvremote.protocol.mrp.NowPlaying
import dev.atvremote.protocol.discovery.AppleTvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How the central touch surface behaves.
 *
 * Tap-to-navigate and drag-to-scroll compete for the same gestures, so rather
 * than have them interfere the surface commits to one at a time.
 */
enum class PadMode { DPAD, SWIPE }

/** Where the user currently is in the connect/pair/control flow. */
sealed interface Screen {
    data object DeviceList : Screen
    data class PinEntry(val device: AppleTvDevice, val forAirPlay: Boolean = false) : Screen
    data class Remote(val device: AppleTvDevice) : Screen
}

data class UiState(
    val screen: Screen = Screen.DeviceList,
    val devices: List<AppleTvDevice> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val volume: Double? = null,
    /** Null until the device reports what it can actually control. */
    val capabilities: MediaCapabilities? = null,
    val keyboardOpen: Boolean = false,
    /** Text currently in the focused field on the TV, null if none is focused. */
    val fieldText: String? = null,
    val checkingField: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val airplayPaired: Boolean = false,
    val nowPlayingError: String? = null,
    val padMode: PadMode = PadMode.DPAD,
    val error: String? = null,
    val pairedKeys: Set<String> = emptySet(),
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = NsdDiscovery(app)
    private val store = CredentialStore(app)

    /** Network work outlives individual composables, so it gets its own scope. */
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var remote: AppleTvRemote? = null
    private var ap2: Ap2Session? = null
    private var airplayConnection: AirPlayConnection? = null
    private var airplayPairing: AirPlayAuth.AirPlayPairing? = null
    private var pairingClient: CompanionClient? = null
    private var pairingSession: CompanionClient.PairingSession? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        dev.atvremote.protocol.Log.enabled = BuildConfig.WIRE_LOGGING
        scan()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun scan() {
        _state.update { it.copy(scanning = true, error = null) }
        viewModelScope.launch {
            val found = runCatching { discovery.scan(5000) }.getOrDefault(emptyList())
            _state.update { s ->
                s.copy(
                    scanning = false,
                    devices = found,
                    pairedKeys = found.filter { store.isPaired(it.credentialKey) }
                        .map { it.credentialKey }.toSet(),
                )
            }
        }
    }

    /** Connect if already paired, otherwise begin pair-setup. */
    fun select(device: AppleTvDevice) {
        val credentials = store.load(device.credentialKey)
        if (credentials != null) connect(device) else startPairing(device)
    }

    private fun startPairing(device: AppleTvDevice) {
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val client = CompanionClient(device.address, device.port, netScope)
                client.connect()
                pairingSession = client.startPairing()
                pairingClient = client
                _state.update { it.copy(busy = false, screen = Screen.PinEntry(device)) }
            } catch (e: Exception) {
                closePairing()
                _state.update { it.copy(busy = false, error = "Could not start pairing: ${e.message}") }
            }
        }
    }

    fun submitPin(device: AppleTvDevice, pin: String) {
        if ((_state.value.screen as? Screen.PinEntry)?.forAirPlay == true) {
            submitAirPlayPin(device, pin)
            return
        }
        val session = pairingSession ?: return
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val credentials = session.complete(pin)
                store.save(device.credentialKey, credentials)
                closePairing()
                connect(device)
            } catch (e: Exception) {
                closePairing()
                _state.update {
                    it.copy(busy = false, screen = Screen.DeviceList, error = e.message ?: "Pairing failed")
                }
            }
        }
    }

    private fun submitAirPlayPin(device: AppleTvDevice, pin: String) {
        val pairing = airplayPairing ?: return
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val credentials = pairing.complete(pin)
                store.saveAirPlay(device.credentialKey, credentials)
                closeAirPlayPairing()
                _state.update {
                    it.copy(busy = false, screen = Screen.Remote(device), airplayPaired = true)
                }
                startNowPlaying(device)
            } catch (e: Exception) {
                closeAirPlayPairing()
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.Remote(device),
                        error = e.message ?: "AirPlay pairing failed",
                    )
                }
            }
        }
    }

    fun cancelPairing() {
        closePairing()
        closeAirPlayPairing()
        val screen = _state.value.screen
        val back = if (screen is Screen.PinEntry && screen.forAirPlay) {
            Screen.Remote(screen.device)
        } else {
            Screen.DeviceList
        }
        _state.update { it.copy(screen = back, busy = false) }
    }

    private fun closePairing() {
        runCatching { pairingClient?.close() }
        pairingClient = null
        pairingSession = null
    }

    private fun connect(device: AppleTvDevice) {
        val credentials = store.load(device.credentialKey) ?: run {
            startPairing(device)
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                remote?.close()
                val r = AppleTvRemote(device.address, device.port, credentials, netScope)
                r.onDisconnect = { _state.update { s -> s.copy(screen = Screen.DeviceList) } }
                // Mirror the first-party remote: when the Apple TV focuses a
                // text field, present the keyboard automatically; dismiss it
                // when focus goes away.
                r.onTextFocus = { session ->
                    _state.update { st ->
                        if (session != null) st.copy(
                            keyboardOpen = true,
                            fieldText = session.textBeforeCursor,
                            checkingField = false,
                        ) else st.copy(
                            keyboardOpen = false,
                            fieldText = null,
                            checkingField = false,
                        )
                    }
                }
                r.onCapabilities = { caps ->
                    _state.update { s -> s.copy(capabilities = caps) }
                    if (caps.volume) refreshVolume()
                }
                r.connect()
                remote = r
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.Remote(device),
                        apps = emptyList(),
                        capabilities = null,
                        volume = null,
                        nowPlaying = null,
                        airplayPaired = store.isAirPlayPaired(device.credentialKey),
                    )
                }
                // Now-playing must come up on every connect. Previously this
                // only ran straight after AirPlay pairing, so it silently
                // stopped working on the next app start.
                startNowPlaying(device)
            } catch (e: Exception) {
                remote = null
                // Stale credentials are the common cause; drop them so the next
                // attempt re-pairs instead of failing forever.
                val stale = e.message?.contains("credentials", ignoreCase = true) == true ||
                    e.message?.contains("identity mismatch", ignoreCase = true) == true
                if (stale) store.forget(device.credentialKey)
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.DeviceList,
                        error = if (stale) "Pairing was removed on the Apple TV - pair again."
                        else "Could not connect: ${e.message}",
                    )
                }
                scan()
            }
        }
    }

    // ------------------------------------------------------- now playing

    /** Bring up the MRP tunnel if this device has AirPlay credentials. */
    private fun startNowPlaying(device: AppleTvDevice) {
        val credentials = store.loadAirPlay(device.credentialKey) ?: return
        netScope.launch {
            try {
                ap2?.close()
                _state.update { it.copy(nowPlayingError = null) }
                val session = Ap2Session(device.address, credentials, netScope)
                session.onNowPlaying = { np ->
                    _state.update { it.copy(nowPlaying = np) }
                }
                session.onDisconnect = { _state.update { it.copy(nowPlaying = null) } }
                session.connect()
                ap2 = session
            } catch (e: Exception) {
                ap2 = null
                // Now-playing is optional; a failure here must not break the
                // remote, but it must still be visible rather than silent.
                android.util.Log.w("atv", "now-playing tunnel failed", e)
                _state.update { it.copy(nowPlaying = null, nowPlayingError = e.message) }
            }
        }
    }

    /** Begin the separate AirPlay pairing that now-playing requires. */
    fun startAirPlayPairing(device: AppleTvDevice) {
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val connection = AirPlayConnection(device.address, 7000)
                connection.connect()
                airplayPairing = AirPlayAuth.startPairing(connection)
                airplayConnection = connection
                _state.update {
                    it.copy(busy = false, screen = Screen.PinEntry(device, forAirPlay = true))
                }
            } catch (e: Exception) {
                closeAirPlayPairing()
                _state.update {
                    it.copy(busy = false, error = "Could not start AirPlay pairing: ${e.message}")
                }
            }
        }
    }

    private fun closeAirPlayPairing() {
        runCatching { airplayConnection?.close() }
        airplayConnection = null
        airplayPairing = null
    }

    fun disconnect() {
        netScope.launch {
            runCatching { remote?.close() }
            runCatching { ap2?.close() }
            remote = null
            ap2 = null
            _state.update {
                it.copy(
                    screen = Screen.DeviceList,
                    apps = emptyList(),
                    volume = null,
                    nowPlaying = null,
                )
            }
        }
    }

    // ------------------------------------------------------------- commands

    /** Fire-and-forget command; surfaces failures without blocking the UI. */
    private fun command(block: suspend AppleTvRemote.() -> Unit) {
        val r = remote ?: return
        netScope.launch {
            try {
                withContext(Dispatchers.IO) { r.block() }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun press(button: Button) = command { press(button) }
    fun holdHome() = command { holdHome() }
    fun swipe(sx: Int, sy: Int, ex: Int, ey: Int, ms: Long) = command { swipe(sx, sy, ex, ey, ms) }

    fun volumeUp() = command {
        press(Button.VOLUME_UP)
        refreshVolume()
    }

    fun volumeDown() = command {
        press(Button.VOLUME_DOWN)
        refreshVolume()
    }

    private fun refreshVolume() {
        val r = remote ?: return
        netScope.launch {
            val v = runCatching { r.getVolume() }.getOrNull()
            _state.update { it.copy(volume = v) }
        }
    }

    fun setPadMode(mode: PadMode) = _state.update { it.copy(padMode = mode) }

    fun toggleKeyboard() {
        val opening = !_state.value.keyboardOpen
        _state.update { it.copy(keyboardOpen = opening) }
        if (opening) refreshTextField()
    }

    /** Ask the device whether a text field is focused, and what it holds. */
    private fun refreshTextField() {
        val r = remote ?: return
        _state.update { it.copy(checkingField = true) }
        netScope.launch {
            val session = runCatching { r.textInputSession() }.getOrNull()
            _state.update {
                it.copy(checkingField = false, fieldText = session?.textBeforeCursor)
            }
        }
    }

    fun sendText(text: String) {
        val r = remote ?: return
        if (text.isEmpty()) return
        netScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { r.sendText(text) }
                _state.update {
                    if (result == null) it.copy(
                        fieldText = null,
                        error = "No text field is focused on the Apple TV.",
                    ) else it.copy(fieldText = result)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearText() {
        val r = remote ?: return
        netScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { r.sendText("", clearPrevious = true) }
                _state.update { it.copy(fieldText = result) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun loadApps() {
        val r = remote ?: return
        netScope.launch {
            val apps = runCatching { r.listApps() }.getOrDefault(emptyList())
            _state.update { it.copy(apps = apps) }
        }
    }

    fun launchApp(bundleId: String) = command { launchApp(bundleId) }

    /** Seek relative to the current position; negative rewinds. */
    fun skip(seconds: Double) = command { skipBy(seconds) }

    override fun onCleared() {
        runCatching { remote?.close() }
        runCatching { ap2?.close() }
        closePairing()
        closeAirPlayPairing()
        netScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onCleared()
    }
}

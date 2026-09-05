package dev.atvremote.app

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.companion.AppInfo
import dev.atvremote.protocol.companion.AppleTvRemote
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.CompanionClient
import dev.atvremote.protocol.companion.MediaCapabilities
import dev.atvremote.protocol.companion.TouchPhase
import dev.atvremote.protocol.airplay.Ap2Session
import dev.atvremote.protocol.airplay.AirPlayAuth
import dev.atvremote.protocol.airplay.AirPlayConnection
import dev.atvremote.protocol.mrp.NowPlaying
import dev.atvremote.protocol.mrp.PlaybackState
import dev.atvremote.protocol.discovery.AppleTvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    /**
     * Whether the TV itself reports a focused field, as opposed to the user
     * having opened the keyboard by hand.
     */
    val textFieldFocused: Boolean = false,
    val checkingField: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    /** Playhead in seconds as of [playheadAt], or null if none is known. */
    val playhead: Double? = null,
    val playheadAt: Long = 0L,
    val playheadAdvancing: Boolean = false,
    val airplayPaired: Boolean = false,
    val nowPlayingError: String? = null,
    val reconnecting: Boolean = false,
    /** True while the one-shot startup auto-connect is in flight. */
    val autoConnecting: Boolean = false,
    /** The device to offer a wake-and-retry for after a failed connect. */
    val wakeTarget: AppleTvDevice? = null,
    val padMode: PadMode = PadMode.DPAD,
    val error: String? = null,
    val pairedKeys: Set<String> = emptySet(),
)

/**
 * The playhead right now, carried forward from the last anchor.
 *
 * The Apple TV reports a position occasionally, not every second, so the
 * scrubber has to run the clock itself between updates.
 */
fun UiState.positionNow(): Double? {
    val base = playhead ?: return null
    if (!playheadAdvancing) return base
    val since = (SystemClock.elapsedRealtime() - playheadAt) / 1000.0
    return (base + since).coerceAtMost(nowPlaying?.duration ?: Double.MAX_VALUE)
}

/**
 * Fold in a now-playing update, re-anchoring the playhead only when the device
 * reports a new one or playback starts or stops. Re-anchoring on every message
 * would let a metadata-only update — fresh artwork, say — rewind the scrubber
 * to a position sampled seconds ago.
 */
fun UiState.withNowPlaying(playing: NowPlaying?): UiState {
    if (playing == null) {
        return copy(nowPlaying = null, playhead = null, playheadAdvancing = false)
    }
    val advancing = playing.playbackState == PlaybackState.PLAYING
    val resampled = playing.elapsedTime != nowPlaying?.elapsedTime
    if (!resampled && advancing == playheadAdvancing) return copy(nowPlaying = playing)
    return copy(
        nowPlaying = playing,
        // Falling back to the reported position matters on the first update
        // that carries one: without it the anchor stays null, and the seek
        // the notification offers has nothing to seek from.
        playhead = if (resampled) playing.position?.first
        else positionNow() ?: playing.position?.first,
        playheadAt = SystemClock.elapsedRealtime(),
        playheadAdvancing = advancing,
    )
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = NsdDiscovery(app)
    private val store = CredentialStore(app)

    /** Network work outlives individual composables, so it gets its own scope. */
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var remote: AppleTvRemote? = null
    private var currentDevice: AppleTvDevice? = null
    private var ap2: Ap2Session? = null
    private var airplayConnection: AirPlayConnection? = null
    private var airplayPairing: AirPlayAuth.AirPlayPairing? = null
    private var pairingClient: CompanionClient? = null
    private var pairingSession: CompanionClient.PairingSession? = null

    // Touch samples for the pad. Declared ahead of init: the drain coroutine
    // starts there, and a property read before its declaration line would be
    // null — the intermittent startup crash.
    private val touchInput = Channel<TouchSample>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Whether the media notification's service has been asked to run. */
    private var notificationRunning = false

    /** Guards the one-shot startup auto-connect. */
    private var autoConnectAttempted = false

    /** Last prompt posted, so an unchanged one is not reposted on every update. */
    private var keyboardPrompt: Pair<String, String?>? = null

    init {
        dev.atvremote.protocol.Log.enabled = BuildConfig.WIRE_LOGGING
        NotificationBridge.handleCommands(::handleRemoteCommand)
        viewModelScope.launch {
            _state.collect {
                syncNotification(it)
                syncKeyboardNotification(it)
            }
        }
        drainTouchInput()
        autoConnectIfSingle()
        scan()
    }

    // ----------------------------------------------------- media notification

    /**
     * Mirror the connection into the notification.
     *
     * The service cannot reach the Apple TV itself, so it is handed a flat
     * snapshot and sends commands back the other way.
     *
     * It runs for as long as the remote is connected rather than only while
     * something plays. Two reasons: Android 12 forbids starting a foreground
     * service from the background, so waiting for playback would mean the
     * notification could never appear unless the app happened to be open; and
     * the foreground service is itself what keeps the connections alive once
     * the app leaves the screen.
     */
    private fun syncNotification(state: UiState) {
        val device = (state.screen as? Screen.Remote)?.device
        if (device == null) {
            // A null snapshot is the service's cue to stop itself.
            if (notificationRunning) {
                notificationRunning = false
                NotificationBridge.publish(null)
            }
            return
        }

        val playing = state.nowPlaying?.takeIf { it.isActive }
        NotificationBridge.publish(
            NowPlayingSnapshot(
                deviceName = device.name,
                title = playing?.title,
                artist = playing?.artist,
                album = playing?.album,
                artwork = playing?.artwork,
                playing = playing?.playbackState == PlaybackState.PLAYING,
                position = state.positionNow(),
                duration = playing?.position?.second,
                volume = state.volume?.takeIf { state.capabilities?.volume == true },
            )
        )

        if (!notificationRunning) {
            notificationRunning = true
            // Connecting always follows a tap, so this start is a foreground
            // one — but a race with the app being dismissed would otherwise
            // take the whole process down, and losing the notification is a
            // far better outcome than a crash.
            runCatching {
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), NowPlayingService::class.java),
                )
            }.onFailure {
                notificationRunning = false
                android.util.Log.w("atv", "could not start the now-playing notification", it)
            }
        }
    }

    /**
     * Offer the keyboard for as long as the TV is asking for text.
     *
     * Reposted when the field's contents change so the shade reflects what has
     * landed — which is also what clears the spinner after a direct reply.
     */
    private fun syncKeyboardNotification(state: UiState) {
        val device = (state.screen as? Screen.Remote)?.device
        if (device == null || !state.textFieldFocused) {
            if (keyboardPrompt != null) {
                keyboardPrompt = null
                KeyboardNotification.hide(getApplication())
            }
            return
        }

        val prompt = device.name to state.fieldText
        if (prompt == keyboardPrompt) return
        keyboardPrompt = prompt
        KeyboardNotification.show(getApplication(), device.name, state.fieldText)
    }

    private fun handleRemoteCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.PlayPause -> press(Button.PLAY_PAUSE)
            is RemoteCommand.Skip -> skip(command.seconds)
            is RemoteCommand.SeekTo -> seekTo(command.seconds)
            is RemoteCommand.SetVolume -> setVolume(command.level)
            is RemoteCommand.SendText -> sendText(command.text)
            is RemoteCommand.AdjustVolume ->
                if (command.direction > 0) volumeUp()
                else if (command.direction < 0) volumeDown()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Straight into the remote when exactly one pairing exists — the common
     * single-TV case; the back arrow on the remote returns to the list. With
     * two or more pairings the choice stays with the user. Runs once per app
     * start: re-running it after a failed connect would loop.
     */
    private fun autoConnectIfSingle() {
        if (autoConnectAttempted) return
        autoConnectAttempted = true
        val last = store.loadLastDevice() ?: return
        val keys = store.pairedKeys()
        if (keys.size == 1 && last.credentialKey in keys) {
            // Hold the list back while the automatic connect runs, so the
            // user never sees the device screen flash past on the way to the
            // remote.
            _state.update { it.copy(autoConnecting = true) }
            connect(last)
        }
    }

    fun scan() {
        _state.update { it.copy(scanning = true, error = null) }

        // The last device connected to appears immediately, ahead of any
        // discovery answer — stored credentials make it tappable right away.
        store.loadLastDevice()?.let { last ->
            _state.update { s ->
                if (s.devices.any { it.credentialKey == last.credentialKey }) s
                else s.copy(
                    devices = s.devices + last,
                    pairedKeys = s.pairedKeys + last.credentialKey,
                )
            }
        }

        viewModelScope.launch {
            val found = runCatching { discovery.scan(5000, ::onDeviceResolved) }
                .getOrDefault(emptyList())
            _state.update { s ->
                // A paired device that is asleep will not answer discovery;
                // keep it listed rather than dropping it off the screen.
                val last = store.loadLastDevice()
                val merged = if (last != null && found.none { it.credentialKey == last.credentialKey }) {
                    found + last
                } else found
                s.copy(
                    scanning = false,
                    devices = merged,
                    pairedKeys = merged.filter { store.isPaired(it.credentialKey) }
                        .map { it.credentialKey }.toSet(),
                )
            }
        }
    }

    /** Merge a freshly resolved device into the list without duplicates. */
    private fun onDeviceResolved(device: AppleTvDevice) {
        _state.update { s ->
            if (s.devices.any { it.credentialKey == device.credentialKey }) s
            else s.copy(
                devices = s.devices + device,
                pairedKeys = if (store.isPaired(device.credentialKey)) {
                    s.pairedKeys + device.credentialKey
                } else s.pairedKeys,
            )
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
                _state.update { it.copy(busy = false, error = str(R.string.err_pairing_start, e.message ?: "")) }
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
                    it.copy(busy = false, screen = Screen.DeviceList, error = e.message ?: str(R.string.err_pairing_failed))
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
                        autoConnecting = false,
                        wakeTarget = null,
                        screen = Screen.Remote(device),
                        error = e.message ?: str(R.string.err_airplay_pairing_failed),
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

    /**
     * Build and connect a remote, wiring up its callbacks.
     *
     * Shared by the initial connect and by transparent reconnection, so both
     * paths get identical event handling.
     */
    private suspend fun establish(
        device: AppleTvDevice,
        credentials: dev.atvremote.protocol.hap.Credentials,
    ): AppleTvRemote {
        remote?.let { runCatching { it.close() } }

        val r = AppleTvRemote(device.address, device.port, credentials, netScope)
        r.onDisconnect = {
            // Reflect reality rather than leaving a stale "Connected" label.
            // The next command reconnects on demand.
            //
            // Focus goes with it: the field the TV was asking about is gone,
            // and a reply typed into a notification that outlived the
            // connection would be swallowed with the spinner left turning.
            _state.update { s ->
                s.withNowPlaying(null).copy(
                    capabilities = null,
                    textFieldFocused = false,
                    fieldText = null,
                )
            }
        }
        // Mirror the first-party remote: when the Apple TV focuses a text
        // field, present the keyboard automatically; dismiss it when focus
        // goes away.
        r.onTextFocus = { session ->
            _state.update { st ->
                if (session != null) st.copy(
                    keyboardOpen = true,
                    fieldText = session.textBeforeCursor,
                    textFieldFocused = true,
                    checkingField = false,
                ) else st.copy(
                    keyboardOpen = false,
                    fieldText = null,
                    textFieldFocused = false,
                    checkingField = false,
                )
            }
        }
        r.onCapabilities = { caps ->
            _state.update { s -> s.copy(capabilities = caps) }
            if (caps.volume) refreshVolume()
        }
        r.connect()
        return r
    }

    /**
     * An Apple TV drops its Companion connection when it sleeps or the network
     * blips. Rather than surfacing a socket error, rebuild the session and let
     * the caller retry once.
     */
    private suspend fun reconnect(): AppleTvRemote? {
        val known = currentDevice ?: return null
        val credentials = store.load(known.credentialKey) ?: return null
        _state.update { it.copy(reconnecting = true) }
        return try {
            // The Apple TV rotates its Companion port, so a cached port is
            // often already stale by the time we need to reconnect --
            // connecting to it yields "Connection refused". Rediscover first
            // and fall back to what we knew if the scan turns up nothing.
            val device = runCatching {
                discovery.scan(4000).firstOrNull { it.credentialKey == known.credentialKey }
            }.getOrNull() ?: known
            currentDevice = device

            val r = establish(device, credentials)
            remote = r
            _state.update { it.copy(reconnecting = false, error = null) }
            startNowPlaying(device)
            r
        } catch (e: Exception) {
            remote = null
            _state.update { it.copy(reconnecting = false) }
            null
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        getApplication<Application>().getString(id, *args)

    private fun isConnectionLost(e: Throwable): Boolean {
        val message = (e.message ?: "").lowercase()
        return e is java.io.IOException ||
            "broken pipe" in message ||
            "closed" in message ||
            "reset" in message ||
            "not connected" in message ||
            "timed out" in message
    }

    private fun friendlyError(e: Throwable): String =
        if (isConnectionLost(e)) str(R.string.err_lost_connection)
        else e.message ?: str(R.string.err_generic)

    private fun connect(device: AppleTvDevice) {
        val credentials = store.load(device.credentialKey) ?: run {
            startPairing(device)
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        netScope.launch {
            try {
                val r = establish(device, credentials)
                remote = r
                _state.update {
                    it.copy(
                        busy = false,
                        autoConnecting = false,
                        wakeTarget = null,
                        // NB: capabilities are not reset here — they arrive
                        // during the handshake above, and the device will not
                        // re-push them until something changes on its side.
                        screen = Screen.Remote(device),
                        apps = emptyList(),
                        volume = null,
                        airplayPaired = store.isAirPlayPaired(device.credentialKey),
                    ).withNowPlaying(null)
                }
                currentDevice = device
                store.saveLastDevice(device)
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
                        autoConnecting = false,
                        // A sleeping Apple TV is the usual reason for a plain
                        // timeout; offer the wake-and-retry path.
                        wakeTarget = if (stale) null else device,
                        screen = Screen.DeviceList,
                        error = if (stale) str(R.string.err_stale_pairing)
                        else str(R.string.err_connect, e.message ?: ""),
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
                    _state.update { it.withNowPlaying(np) }
                }
                session.onDisconnect = { _state.update { it.withNowPlaying(null) } }
                session.connect()
                ap2 = session
            } catch (e: Exception) {
                ap2 = null
                // Now-playing is optional; a failure here must not break the
                // remote, but it must still be visible rather than silent.
                android.util.Log.w("atv", "now-playing tunnel failed", e)
                _state.update { it.withNowPlaying(null).copy(nowPlayingError = e.message) }
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
                    it.copy(busy = false, error = str(R.string.err_airplay_pairing_start, e.message ?: ""))
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
                ).withNowPlaying(null)
            }
        }
    }

    // ------------------------------------------------------------- commands

    /**
     * Fire-and-forget command.
     *
     * If the connection has died since the last command — which an Apple TV
     * does routinely when it sleeps — reconnect and retry once before
     * reporting anything to the user.
     */
    private fun command(block: suspend AppleTvRemote.() -> Unit) {
        netScope.launch {
            val r = remote
            try {
                if (r == null) throw java.io.IOException("not connected")
                withContext(Dispatchers.IO) { r.block() }
            } catch (e: Exception) {
                if (!isConnectionLost(e)) {
                    _state.update { it.copy(error = friendlyError(e)) }
                    return@launch
                }
                val revived = reconnect()
                if (revived == null) {
                    _state.update { it.copy(error = friendlyError(e)) }
                    return@launch
                }
                runCatching { withContext(Dispatchers.IO) { revived.block() } }
                    .onFailure { retryFailure ->
                        _state.update { it.copy(error = friendlyError(retryFailure)) }
                    }
            }
        }
    }

    fun press(button: Button) = command { press(button) }

    /** Press and hold, which tvOS treats as a separate gesture from a tap. */
    fun hold(button: Button) = command { press(button, holdMs = HOLD_MS) }

    fun holdHome() = command { holdHome() }

    /**
     * tvOS exposes sleep and wake as distinct commands rather than a toggle,
     * and nothing on the wire reports which state the device is in, so the two
     * stay separate here instead of being guessed at.
     */
    fun sleep() = command { sleep() }

    fun wake() = command { wake() }
    fun swipe(sx: Int, sy: Int, ex: Int, ey: Int, ms: Long) = command { swipe(sx, sy, ex, ey, ms) }

    // -------------------------------------------------- touch-surface stream

    private data class TouchSample(val x: Int, val y: Int, val phase: TouchPhase)

    fun touch(x: Int, y: Int, phase: TouchPhase) {
        touchInput.trySend(TouchSample(x, y, phase))
    }

    /**
     * A swipe is a stream of touch samples that must reach the device in order —
     * Press, then Holds, then Release — so they go through a channel drained by
     * a single consumer rather than one coroutine per sample. Hold samples are
     * lightly throttled to ~12 ms since the coordinates are absolute and the
     * wire does not need more.
     */
    private fun drainTouchInput() {
        netScope.launch {
            var lastHoldNanos = 0L
            for (sample in touchInput) {
                if (sample.phase == TouchPhase.HOLD) {
                    val now = System.nanoTime()
                    if (now - lastHoldNanos < 12_000_000L) continue
                    lastHoldNanos = now
                }
                val r = remote ?: continue
                runCatching { r.touch(sample.x, sample.y, sample.phase) }
            }
        }
    }

    /** Held select: down on the long-press timeout, up when the finger lifts. */
    fun selectDown() = command { buttonDown(Button.SELECT) }
    fun selectUp() = command { buttonUp(Button.SELECT) }

    /** Rim direction held down/up; tvOS repeats a held direction itself. */
    fun padDirectionDown(button: Button) = command { buttonDown(button) }
    fun padDirectionUp(button: Button) = command { buttonUp(button) }

    /**
     * Nudge a sleeping Apple TV. A bare Companion session — connect,
     * authenticate, one WAKE press — is all a sleeping device will answer,
     * so this skips the full remote handshake, then retries the real connect
     * once the TV has had a moment to come up.
     */
    fun wakeDevice(device: AppleTvDevice) {
        val credentials = store.load(device.credentialKey) ?: return
        _state.update { it.copy(busy = true, error = null, wakeTarget = null) }
        netScope.launch {
            val woke = runCatching {
                val client = CompanionClient(device.address, device.port, netScope)
                try {
                    client.connect()
                    client.authenticate(credentials)
                    client.request(
                        "_hidC",
                        mapOf("_hBtS" to 2, "_hidC" to Button.WAKE.code),
                    )
                } finally {
                    client.close()
                }
            }.isSuccess
            if (woke) {
                delay(2500)
                connect(device)
            } else {
                _state.update { it.copy(busy = false, error = str(R.string.wake_failed)) }
            }
        }
    }

    /**
     * Whether the phone's hardware volume keys should drive the TV's volume:
     * while the remote itself is on screen and the Apple TV reports it can
     * route volume. Everywhere else the keys stay with the phone.
     */
    fun handlesVolumeKeys(): Boolean =
        state.value.screen is Screen.Remote && state.value.capabilities?.volume == true

    /** A hardware volume-key press (repeats arrive as more presses). */
    fun onVolumeKey(up: Boolean) = if (up) volumeUp() else volumeDown()

    fun volumeUp() = command {
        press(Button.VOLUME_UP)
        refreshVolume()
    }

    fun volumeDown() = command {
        press(Button.VOLUME_DOWN)
        refreshVolume()
    }

    /** Jump straight to a level, which is what the notification's slider does. */
    fun setVolume(level: Double) {
        val clamped = level.coerceIn(0.0, 1.0)
        _state.update { it.copy(volume = clamped) }
        command { setVolume(clamped) }
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
                        error = str(R.string.err_no_field),
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
    /** Long enough for tvOS to read a hold rather than a tap. */
    private val HOLD_MS = 1000L

    fun skip(seconds: Double) {
        nudgePlayhead(seconds)
        command { skipBy(seconds) }
    }

    /**
     * Seek to an absolute position. Companion Link only offers a relative
     * skip, so this asks for the difference from where the playhead is
     * believed to be.
     */
    fun seekTo(seconds: Double) {
        val current = _state.value.positionNow() ?: return
        skip(seconds - current)
    }

    /**
     * Move the local anchor immediately, so the scrubber follows the finger
     * instead of snapping back until the device reports the new position.
     */
    private fun nudgePlayhead(seconds: Double) = _state.update { s ->
        val from = s.positionNow() ?: return@update s
        // Only META_ELAPSED is sanity-checked on the way in, so a duration
        // that is negative or NaN reaches here intact and would make coerceIn
        // throw on its own arguments.
        val limit = s.nowPlaying?.duration?.takeIf { it.isFinite() && it > 0 }
            ?: Double.MAX_VALUE
        s.copy(
            playhead = (from + seconds).coerceIn(0.0, limit),
            playheadAt = SystemClock.elapsedRealtime(),
        )
    }

    override fun onCleared() {
        // The notification outlives neither the connection nor this view model,
        // which owns it, so both go at once.
        NotificationBridge.handleCommands(null)
        NotificationBridge.publish(null)
        notificationRunning = false
        keyboardPrompt = null
        KeyboardNotification.hide(getApplication())
        runCatching { remote?.close() }
        runCatching { ap2?.close() }
        closePairing()
        closeAirPlayPairing()
        netScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onCleared()
    }
}

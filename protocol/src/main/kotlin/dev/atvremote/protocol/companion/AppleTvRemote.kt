package dev.atvremote.protocol.companion

import dev.atvremote.protocol.hap.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Buttons available over the Companion HID channel. */
enum class Button(val code: Int) {
    UP(1),
    DOWN(2),
    LEFT(3),
    RIGHT(4),
    MENU(5),
    SELECT(6),
    HOME(7),
    VOLUME_UP(8),
    VOLUME_DOWN(9),
    SIRI(10),
    SCREENSAVER(11),
    SLEEP(12),
    WAKE(13),
    PLAY_PAUSE(14),
    CHANNEL_UP(15),
    CHANNEL_DOWN(16),
    GUIDE(17),
    PAGE_UP(18),
    PAGE_DOWN(19),
}

/** Commands on the media-control channel, which carry richer state than HID. */
enum class MediaControl(val code: Int) {
    PLAY(1),
    PAUSE(2),
    NEXT_TRACK(3),
    PREVIOUS_TRACK(4),
    GET_VOLUME(5),
    SET_VOLUME(6),
    SKIP_BY(7),
    FAST_FORWARD_BEGIN(8),
    FAST_FORWARD_END(9),
    REWIND_BEGIN(10),
    REWIND_END(11),
}

/** Phases of a touch interaction on the virtual trackpad. */
enum class TouchPhase(val code: Int) {
    PRESS(1),
    HOLD(3),
    RELEASE(4),
    CLICK(5),
}

data class AppInfo(val name: String, val bundleId: String)

/**
 * Which controls the device reports as actually available, from the `_mcF`
 * bitfield in `_iMC` events.
 *
 * Volume is the one that matters in practice: an Apple TV only reports volume
 * capability when it can route it (typically HDMI-CEC). With an IR-based
 * setup the Siri Remote emits infrared itself and the Apple TV is never
 * involved, so no network remote can change the volume.
 */
data class MediaCapabilities(
    val play: Boolean = false,
    val pause: Boolean = false,
    val nextTrack: Boolean = false,
    val previousTrack: Boolean = false,
    val volume: Boolean = false,
    val skipForward: Boolean = false,
    val skipBackward: Boolean = false,
) {
    companion object {
        fun fromFlags(flags: Long) = MediaCapabilities(
            play = flags and 0x0001L != 0L,
            pause = flags and 0x0002L != 0L,
            nextTrack = flags and 0x0004L != 0L,
            previousTrack = flags and 0x0008L != 0L,
            volume = flags and 0x0100L != 0L,
            skipForward = flags and 0x0200L != 0L,
            skipBackward = flags and 0x0400L != 0L,
        )
    }
}

/**
 * High-level Apple TV remote.
 *
 * Wraps [CompanionClient] with the session setup an Apple TV expects and the
 * commands a remote actually needs. Instances are single-connection: call
 * [connect] once, then issue commands.
 */
class AppleTvRemote(
    host: String,
    port: Int,
    private val credentials: Credentials,
    private val scope: CoroutineScope,
    private val deviceName: String = "Android Remote",
) {
    private val client = CompanionClient(host, port, scope)
    private var heartbeatJob: Job? = null
    private var sessionId: Long = 0
    private var touchBaseNanos: Long = System.nanoTime()

    val isConnected: Boolean get() = client.isConnected

    var onEvent: ((String, Map<Any?, Any?>) -> Unit)?
        get() = externalEventHandler
        set(v) { externalEventHandler = v }

    var onDisconnect: ((Throwable?) -> Unit)?
        get() = client.onDisconnect
        set(v) { client.onDisconnect = v }

    /** Invoked whenever the device reports which controls are available. */
    var onCapabilities: ((MediaCapabilities) -> Unit)? = null

    /**
     * Invoked when a text field gains or loses focus on the device, with the
     * active session or null. Drives auto-presenting a keyboard, the way the
     * first-party remote does.
     */
    var onTextFocus: ((RtiSession?) -> Unit)? = null

    /**
     * Our own `_tiStop`/`_tiStart` round trip makes the device emit focus
     * events. Those are echoes of our own request, not real focus changes, so
     * they are suppressed while a text operation is in flight.
     */
    @Volatile
    private var suppressFocusEvents = false

    /**
     * Connect, verify credentials, and bring up the sessions tvOS requires
     * before it will accept input.
     */
    suspend fun connect() {
        client.connect()
        client.authenticate(credentials)

        client.onEvent = { name, content ->
            when (name) {
                "_iMC" -> (content["_mcF"] as? Long)?.let {
                    onCapabilities?.invoke(MediaCapabilities.fromFlags(it))
                }
                // Presence of _tiD means a field is focused; its absence on
                // _tiStopped means focus was lost.
                "_tiStarted", "_tiStopped", "_tiStart" -> if (!suppressFocusEvents) {
                    onTextFocus?.invoke(sessionFrom(content))
                }
            }
            externalEventHandler?.invoke(name, content)
        }

        sendSystemInfo()
        touchStart()
        sessionStart()
        tvRemoteSessionStart()

        startHeartbeat()

        // Ask the device to tell us which controls it can actually route.
        subscribe("_iMC")

        // A field already focused when we connect produces no _tiStarted
        // event, so seed the state from the _tiStart response instead.
        runCatching { onTextFocus?.invoke(textInputStart()) }
    }

    private var externalEventHandler: ((String, Map<Any?, Any?>) -> Unit)? = null

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                // A failure here is the connection dying; the send path
                // reports it and callers reconnect on demand.
                if (runCatching { client.sendNoOp() }.isFailure) return@launch
            }
        }
    }

    fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        client.close()
    }

    private suspend fun sendSystemInfo() {
        client.request(
            "_systemInfo",
            mapOf(
                "_bf" to 0,
                "_cf" to 512,
                "_clFl" to 128,
                // A stable non-null value here; a null identifier makes tvOS
                // stop emitting system status events.
                "_i" to "abcdefabcdef",
                "_idsID" to String(credentials.clientId),
                "_pubID" to "aa:bb:cc:dd:ee:ff",
                "_sf" to 256,
                "_sv" to "170.18",
                "model" to "iPhone14,3",
                "name" to deviceName,
            ),
        )
    }

    private suspend fun touchStart() {
        touchBaseNanos = System.nanoTime()
        client.request("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0))
    }

    private suspend fun sessionStart() {
        val local = Random.nextLong(0, 1L shl 32)
        val response = client.request(
            "_sessionStart",
            mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to local),
        )
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<Any?, Any?>
        val remote = (content?.get("_sid") as? Long) ?: 0L
        sessionId = (remote shl 32) or local
    }

    private suspend fun tvRemoteSessionStart() {
        // Not present on every tvOS version; failure here is not fatal.
        runCatching { client.request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2")) }
    }

    // ------------------------------------------------------------ buttons

    /** Press and release a button. */
    suspend fun press(button: Button, holdMs: Long = 0) {
        hid(button, down = true)
        if (holdMs > 0) delay(holdMs)
        hid(button, down = false)
    }

    private suspend fun hid(button: Button, down: Boolean) {
        client.request("_hidC", mapOf("_hBtS" to if (down) 1 else 2, "_hidC" to button.code))
    }

    suspend fun up() = press(Button.UP)
    suspend fun down() = press(Button.DOWN)
    suspend fun left() = press(Button.LEFT)
    suspend fun right() = press(Button.RIGHT)
    suspend fun select() = press(Button.SELECT)
    suspend fun menu() = press(Button.MENU)
    suspend fun home() = press(Button.HOME)
    suspend fun playPause() = press(Button.PLAY_PAUSE)
    suspend fun volumeUp() = press(Button.VOLUME_UP)
    suspend fun volumeDown() = press(Button.VOLUME_DOWN)
    suspend fun sleep() = press(Button.SLEEP)
    suspend fun wake() = press(Button.WAKE)

    /** Hold Home to reach the app switcher / control centre. */
    suspend fun holdHome() = press(Button.HOME, holdMs = 1000)

    /**
     * Hold a button down until the matching [up] — how a held select opens a
     * context menu, held for exactly as long as the finger stays down.
     */
    suspend fun buttonDown(button: Button) = hid(button, down = true)
    suspend fun buttonUp(button: Button) = hid(button, down = false)

    // ------------------------------------------------------- media control

    suspend fun mediaControl(command: MediaControl, args: Map<String, Any?> = emptyMap()) =
        client.request("_mcc", mapOf("_mcc" to command.code) + args)

    suspend fun play() = mediaControl(MediaControl.PLAY)
    suspend fun pause() = mediaControl(MediaControl.PAUSE)
    suspend fun nextTrack() = mediaControl(MediaControl.NEXT_TRACK)
    suspend fun previousTrack() = mediaControl(MediaControl.PREVIOUS_TRACK)

    suspend fun skipBy(seconds: Double) =
        mediaControl(MediaControl.SKIP_BY, mapOf("_skpS" to seconds))

    /** Current volume in 0..1, or null if the device does not report one. */
    suspend fun getVolume(): Double? {
        val response = mediaControl(MediaControl.GET_VOLUME)
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<Any?, Any?> ?: return null
        return when (val v = content["_vol"]) {
            is Double -> v
            is Float -> v.toDouble()
            is Long -> v.toDouble()
            else -> null
        }
    }

    suspend fun setVolume(level: Double) =
        mediaControl(MediaControl.SET_VOLUME, mapOf("_vol" to level.coerceIn(0.0, 1.0)))

    // ---------------------------------------------------------------- apps

    /** Apps installed on the device, in the order tvOS reports them. */
    suspend fun listApps(): List<AppInfo> {
        val response = client.request("FetchLaunchableApplicationsEvent")
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<Any?, Any?> ?: return emptyList()
        // Reported as a bundle-id -> display-name mapping.
        return content.mapNotNull { (bundleId, name) ->
            val id = bundleId as? String ?: return@mapNotNull null
            AppInfo(name = name as? String ?: id, bundleId = id)
        }
    }

    suspend fun launchApp(bundleId: String) {
        val key = if (bundleId.contains("://")) "_urlS" else "_bundleID"
        client.request("_launchApp", mapOf(key to bundleId))
    }

    // -------------------------------------------------------- touch surface

    /** Send a single touch sample; coordinates are in a 0..1000 space. */
    fun touch(x: Int, y: Int, phase: TouchPhase) {
        client.sendEvent(
            "_hidT",
            mapOf(
                "_ns" to (System.nanoTime() - touchBaseNanos),
                "_tFg" to 1,
                "_cx" to x.coerceIn(0, 1000),
                "_tPh" to phase.code,
                "_cy" to y.coerceIn(0, 1000),
            ),
        )
    }

    /** Interpolated swipe across the virtual trackpad. */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) {
        val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
        touch(startX, startY, TouchPhase.PRESS)
        for (i in 1 until steps) {
            val t = i.toDouble() / steps
            touch(
                (startX + (endX - startX) * t).toInt(),
                (startY + (endY - startY) * t).toInt(),
                TouchPhase.HOLD,
            )
            delay(STEP_MS)
        }
        touch(endX, endY, TouchPhase.RELEASE)
    }

    // ---------------------------------------------------------- text input

    private fun sessionFrom(content: Map<Any?, Any?>): RtiSession? {
        val archive = content["_tiD"] as? ByteArray ?: return null
        return runCatching { RtiPayloads.parseSession(archive) }.getOrNull()
    }

    private suspend fun textInputStart(): RtiSession? {
        val response = client.request("_tiStart")
        @Suppress("UNCHECKED_CAST")
        val content = response["_c"] as? Map<Any?, Any?> ?: return null
        return sessionFrom(content)
    }

    private suspend fun textInputStop() {
        runCatching { client.request("_tiStop") }
    }

    /**
     * Whether a text field currently has focus on the device, and what it
     * already contains. Returns null when nothing is accepting input.
     */
    suspend fun textInputSession(): RtiSession? {
        // Restart the session so the returned state is current: the device
        // does not push updates for a session opened before the field focused.
        suppressFocusEvents = true
        try {
            textInputStop()
            return textInputStart()
        } finally {
            suppressFocusEvents = false
        }
    }

    /**
     * Type [text] into the focused field on the Apple TV.
     *
     * Returns the resulting field contents, or null if no field is focused.
     * Set [clearPrevious] to replace rather than append.
     */
    suspend fun sendText(text: String, clearPrevious: Boolean = false): String? {
        val session = textInputSession()
        if (session == null) {
            onTextFocus?.invoke(null)
            return null
        }
        var current = session.textBeforeCursor

        if (clearPrevious) {
            client.sendEvent(
                "_tiC",
                mapOf("_tiV" to 1, "_tiD" to RtiPayloads.clearText(session.sessionUuid)),
            )
            current = ""
        }

        if (text.isNotEmpty()) {
            client.sendEvent(
                "_tiC",
                mapOf("_tiV" to 1, "_tiD" to RtiPayloads.inputText(session.sessionUuid, text)),
            )
            current += text
        }

        // Re-assert focus: the suppressed echo events above may have left a
        // listener with stale state.
        onTextFocus?.invoke(session)
        return current
    }

    // -------------------------------------------------------------- events

    suspend fun subscribe(event: String) =
        client.sendEvent("_interest", mapOf("_regEvents" to listOf(event)))

    private companion object {
        const val STEP_MS = 16L
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}

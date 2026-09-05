package dev.atvremote.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import dev.atvremote.protocol.mrp.NowPlaying
import dev.atvremote.protocol.mrp.PlaybackState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.atvremote.protocol.companion.AppInfo
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.companion.TouchAcceleration
import dev.atvremote.protocol.companion.TouchPhase
import dev.atvremote.protocol.discovery.AppleTvDevice
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.hypot

// combinedClickable, for the power button's tap-versus-hold split.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(device: AppleTvDevice, state: UiState, vm: RemoteViewModel) {
    var showApps by remember { mutableStateOf(false) }
    // The system back button folds the app drawer away before it ever gets a
    // chance to leave the remote itself.
    BackHandler(enabled = showApps) { showApps = false }
    // A hold has no on-screen feedback of its own, so the buzz is the only
    // signal that it registered rather than a tap.
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Landscape puts the pad and the controls side by side; portrait stacks
        // them, with the pad taking whatever height is left over.
        val landscape = maxWidth > maxHeight

        // The drawer is the landscape screen too, so its apps load for it.
        LaunchedEffect(showApps, landscape) {
            if ((showApps || landscape) && state.apps.isEmpty()) vm.loadApps()
        }

        Column(Modifier.fillMaxSize()) {
        // ---- header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_disconnect),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { vm.disconnect() }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = when {
                    state.reconnecting -> stringResource(R.string.reconnecting)
                    state.capabilities?.volume == true && state.volume != null ->
                        stringResource(R.string.volume_percent, (state.volume * 100).toInt())
                    else -> stringResource(R.string.connected)
                }
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.Keyboard,
                contentDescription = stringResource(R.string.cd_keyboard),
                tint = if (state.keyboardOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { vm.toggleKeyboard() }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Apps,
                contentDescription = stringResource(R.string.cd_apps),
                tint = if (showApps) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { showApps = !showApps }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(4.dp))
            // A tap opens Control Centre, which is what the remote's own power
            // button does — sleeping outright, with no confirmation and no way
            // back, is not what pressing power on a remote means. Sleep lives
            // inside that panel; waking does not, since a sleeping TV has no
            // panel to open, so it stays on the hold.
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = stringResource(R.string.cd_power),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { vm.holdHome() },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.wake()
                        },
                    )
                    .padding(8.dp),
            )
        }

        state.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            ErrorBanner(message) { vm.dismissError() }
        }

        AnimatedVisibility(
            visible = state.keyboardOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = ExitTransition.None,
        ) {
            Spacer(Modifier.height(12.dp))
            TextEntry(state, vm)
        }


        // The drawer owns this screen in two cases: opened explicitly, and
        // landscape — on a big-but-short display the pad has no room to
        // breathe, and a grid of launchable apps is the better use of the
        // space. Nothing else: just the apps.
        AnimatedVisibility(
            visible = showApps || landscape,
            modifier = Modifier.weight(1f),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = ExitTransition.None,
        ) {
            AppGrid(state, vm, Modifier.fillMaxSize(), columns = if (landscape) 5 else 3)
        }

        if (!(showApps || landscape)) {
            // Every seam — above the card, around the pad, between the button
            // rows, and at the page edges — shares the leftover height
            // equally, while the pad region gets the lion's share so the
            // square inside it grows to full width whenever the space allows.
            Spacer(Modifier.weight(1f))
            NowPlayingSection(device, state, vm)
            Spacer(Modifier.weight(1f))

            // Hidden outright while typing: squeezed down to a sliver is the
            // worst of both worlds, and the pad is useless during text entry.
            if (!state.keyboardOpen) {
            Box(
                modifier = Modifier
                    .weight(10f)
                    .fillMaxWidth()
                    .animateContentSize(),
                contentAlignment = Alignment.Center,
            ) {
                TouchPad(
                    modifier = Modifier.fillMaxSize(),
                    onDirection = { vm.press(it) },
                    onSelect = {
                        // OK commits the focused field: fold the panel with it.
                        if (state.keyboardOpen) vm.toggleKeyboard()
                        vm.press(Button.SELECT)
                    },
                    onSelectDown = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectDown()
                    },
                    onSelectUp = { vm.selectUp() },
                    onTouch = { x, y, phase -> vm.touch(x, y, phase) },
                )
            }
            }

            Spacer(Modifier.weight(1f))
            TransportRow(state, vm)
            Spacer(Modifier.weight(1f))
            VolumeRow(vm)
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The now-playing card, shown only while there is actual content on the TV.
 *
 * An idle player keeps reporting itself — paused, no title, no length — and a
 * card of nothing is pure noise. Content means the wire carried a title or a
 * duration; anything else folds the card away and lets the pad breathe. The
 * card grows and fades rather than jolting the column as it comes and goes.
 */
@Composable
private fun NowPlayingSection(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
) {
    Spacer(Modifier.height(12.dp))
    if (state.airplayPaired) {
        val playing = state.nowPlaying?.takeIf {
            it.isActive && (it.title != null || it.duration != null)
        }
        AnimatedVisibility(
            visible = playing != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            NowPlayingCard(state, playing ?: NowPlaying(), vm)
        }
    } else {
        EnableNowPlaying(device, state, vm)
    }
}

@Composable
private fun TransportRow(state: UiState, vm: RemoteViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.menu)) { vm.press(Button.MENU) }

        // One button toggles playback, so it shows the action it will
        // perform. Without now-playing there is no state to reflect, and
        // it falls back to the play glyph.
        val isPlaying = state.nowPlaying?.playbackState == PlaybackState.PLAYING
        RoundButton(
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            description = stringResource(if (isPlaying) R.string.pause else R.string.play),
        ) { vm.press(Button.PLAY_PAUSE) }

        RoundButton(Icons.Default.Home, stringResource(R.string.home)) { vm.press(Button.HOME) }
    }
}

@Composable
private fun VolumeRow(vm: RemoteViewModel) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A touch taller than the icon alone so the row balances against the
        // 64 dp transport buttons above it instead of looking squeezed.
        PillButton(Icons.Default.VolumeDown, stringResource(R.string.volume_down), Modifier.weight(1f).height(52.dp)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.volumeDown()
        }
        PillButton(Icons.Default.VolumeUp, stringResource(R.string.volume_up), Modifier.weight(1f).height(52.dp)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.volumeUp()
        }
    }
}

/**
 * The Siri Remote's click pad as one surface, and the whole surface is live.
 *
 * Gesture classification is positional:
 *  - a tap inside the centre circle selects; holding it is the long press
 *    that opens context menus
 *  - a tap on the rim steers by the dominant axis of the tap position, which
 *    gives every direction a quadrant-sized target
 *  - a drag anywhere streams real touch samples (Press, accelerated Holds,
 *    Release) so momentum scrolling behaves like the hardware remote
 *
 * tvOS repeats a held direction itself, so rim holds send the direction once.
 * The chevrons and the centre circle are visual affordances only.
 */
@Composable
private fun TouchPad(
    modifier: Modifier = Modifier,
    onDirection: (Button) -> Unit,
    onSelect: () -> Unit,
    onSelectDown: () -> Unit,
    onSelectUp: () -> Unit,
    onTouch: (x: Int, y: Int, phase: TouchPhase) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        val padHeight = minOf(maxHeight, maxWidth)
        val chevron = (side * 0.09f).coerceAtMost(30.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(padHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    val centre = 500f
                    // Frac is measured against a fixed 300 dp reference pad —
                    // the size the sensitivity curve was tuned on — not against
                    // this surface. The surface changes shape with the layout,
                    // and a small one would amplify finger tremor into focus
                    // jumps.
                    val refPx = 300.dp.toPx()
                    val centreRadius = minOf(size.width, size.height) * 0.30f

                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        val slop = viewConfiguration.touchSlop
                        var mode = Mode.Undecided

                        var vx = centre
                        var vy = centre
                        var lastPos = start
                        var lastTime = down.uptimeMillis

                        fun inCentre(pos: Offset): Boolean {
                            val dx = pos.x - size.width / 2f
                            val dy = pos.y - size.height / 2f
                            return hypot(dx, dy) < centreRadius
                        }

                        fun direction(pos: Offset) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val dx = pos.x - size.width / 2f
                            val dy = pos.y - size.height / 2f
                            if (abs(dx) > abs(dy)) {
                                onDirection(if (dx > 0) Button.RIGHT else Button.LEFT)
                            } else {
                                onDirection(if (dy > 0) Button.DOWN else Button.UP)
                            }
                        }

                        fun advance(pos: Offset, time: Long) {
                            val dt = (time - lastTime).coerceAtLeast(1L) / 1000f
                            val fracX = (pos.x - lastPos.x) / refPx
                            val fracY = (pos.y - lastPos.y) / refPx
                            val speed = hypot(fracX, fracY) / dt
                            val gain = TouchAcceleration.gain(speed)
                            vx = (vx + fracX * 1000f * gain * HORIZONTAL_SENSITIVITY)
                                .coerceIn(0f, 1000f)
                            vy = (vy + fracY * 1000f * gain * VERTICAL_SENSITIVITY)
                                .coerceIn(0f, 1000f)
                            lastPos = pos
                            lastTime = time
                            onTouch(vx.toInt(), vy.toInt(), TouchPhase.HOLD)
                        }

                        // Phase 1: within the long-press window, decide tap vs.
                        // drag vs. hold — the three gestures cannot be told
                        // apart before either the finger lifts, moves, or the
                        // timeout fires.
                        try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                while (mode == Mode.Undecided) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) {
                                        mode = if (inCentre(change.position)) Mode.Tap else Mode.RimTap
                                        break
                                    }
                                    if ((change.position - start).getDistance() > slop) {
                                        mode = Mode.Drag
                                        onTouch(500, 500, TouchPhase.PRESS)
                                        lastPos = change.position
                                        lastTime = change.uptimeMillis
                                        advance(change.position, change.uptimeMillis)
                                        break
                                    }
                                    change.consume()
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            if (inCentre(start)) {
                                mode = Mode.HoldSelect
                                onSelectDown()
                            } else {
                                mode = Mode.RimHold
                                direction(start)
                            }
                        }

                        if (mode == Mode.Tap) { onSelect(); return@awaitEachGesture }

                        // Phase 2: track until the finger lifts.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (mode == Mode.Drag) advance(change.position, change.uptimeMillis)
                            if (change.changedToUp()) break
                            change.consume()
                        }

                        when (mode) {
                            Mode.Drag -> onTouch(vx.toInt(), vy.toInt(), TouchPhase.RELEASE)
                            Mode.HoldSelect -> onSelectUp()
                            Mode.RimTap -> direction(start)
                            // RimHold already steered when the hold began.
                            else -> Unit
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Visual affordances only: the centre circle and the rim chevrons.
            Box(
                modifier = Modifier
                    .size(side * 0.6f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            )
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = side * 0.02f).size(chevron),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.02f).size(chevron),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = side * 0.02f).size(chevron),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = side * 0.02f).size(chevron),
            )
        }
    }
}

private enum class Mode { Undecided, Tap, RimTap, Drag, HoldSelect, RimHold }

/**
 * Fire [onStep] once on touch-down, then keep repeating while the finger stays
 * down — first repeat after [firstDelayMs], then every [stepDelayMs]. How the
 * volume keys step without being tapped over and over.
 */
private fun Modifier.repeatOnHold(
    firstDelayMs: Long = 400,
    stepDelayMs: Long = 150,
    onStep: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onStep()
        var steps = 1
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            val heldFor = (change.uptimeMillis - down.uptimeMillis - firstDelayMs)
                .coerceAtLeast(0L)
            val target = 1 + (heldFor / stepDelayMs).toInt()
            while (steps <= target) {
                onStep()
                steps++
            }
            if (!change.pressed) break
            change.consume()
        }
    }
}

// Both axes 1:1 against the 300 dp reference pad, so the same finger travel
// moves the focus the same distance whichever way you swipe.
private const val HORIZONTAL_SENSITIVITY = 1.0f
private const val VERTICAL_SENSITIVITY = 1.0f

/**
 * One rim arrow: a tap target sitting in the ring between the pad edge and the
 * touch surface, sized as a fraction of the pad so it stays inside the ring.
 */
@Composable
private fun RimDirection(
    button: Button,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp,
    description: String,
    onDirection: (Button) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDirection(button)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * Text entry for the focused field on the Apple TV.
 *
 * Text is pushed on submit rather than per keystroke: each send restarts the
 * RTI session to read authoritative state, so per-character sends would be both
 * slow and racy.
 */
@Composable
private fun TextEntry(state: UiState, vm: RemoteViewModel) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.type_on_tv)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendText(draft)
                    draft = ""
                }),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        vm.sendText(draft)
                        draft = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = stringResource(R.string.send_text),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { vm.clearText() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Backspace,
                    contentDescription = stringResource(R.string.clear_field),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        val status = when {
            state.checkingField -> stringResource(R.string.checking_field)
            state.fieldText == null -> stringResource(R.string.no_field)
            state.fieldText.isEmpty() -> stringResource(R.string.field_empty)
            else -> stringResource(R.string.field_is, state.fieldText)
        }
        Text(
            status,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Now-playing card, modelled on the iOS Control Centre widget: artwork and
 * track on top, a scrubber, transport controls, then volume.
 *
 * Artwork arrives as raw bytes on the MRP channel; it is decoded here rather
 * than in the protocol layer so that module stays free of Android types.
 */
@Composable
private fun NowPlayingCard(state: UiState, playing: NowPlaying, vm: RemoteViewModel) {
    val artwork = remember(playing.artwork?.size) {
        playing.artwork?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    // The device reports a position now and then, so the card runs the clock
    // itself in between; the anchor it extrapolates from lives in the state.
    var position by remember { mutableStateOf(state.positionNow()) }
    LaunchedEffect(state.playhead, state.playheadAt, state.playheadAdvancing) {
        position = state.positionNow()
        while (state.playheadAdvancing) {
            delay(500)
            position = state.positionNow()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                // The app is the source line. Playing and paused are already
                // obvious from the transport glyph, so only the states that
                // are not are worth spelling out.
                val stateLabel = when (playing.playbackState) {
                    PlaybackState.SEEKING -> stringResource(R.string.state_seeking)
                    PlaybackState.STOPPED -> stringResource(R.string.state_stopped)
                    PlaybackState.INTERRUPTED -> stringResource(R.string.state_interrupted)
                    else -> null
                }
                val caption = listOfNotNull(playing.appName, stateLabel).joinToString(" · ")
                if (caption.isNotBlank()) {
                    Text(
                        caption,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    playing.title ?: stringResource(R.string.nothing_playing),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                playing.artist?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        val elapsed = position
        val duration = playing.position?.second
        if (elapsed != null && duration != null) {
            Spacer(Modifier.height(12.dp))
            Scrubber(elapsed, duration) { vm.seekTo(it) }
        }

        // Transport stays available while paused: skipping about is as useful
        // then as it is during playback. The play/pause button itself lives in
        // the bottom transport row, so it is not repeated here.
        val seekable = playing.playbackState in setOf(
            PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.SEEKING,
        )
        if (seekable) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(Icons.Default.Replay10, stringResource(R.string.skip_back), 30.dp) {
                    vm.skip(-SKIP_SECONDS)
                }
                TransportButton(Icons.Default.Forward10, stringResource(R.string.skip_forward), 30.dp) {
                    vm.skip(SKIP_SECONDS)
                }
            }
        }
    }
}

private const val SKIP_SECONDS = 10.0

/**
 * Playhead with elapsed and remaining times.
 *
 * Dragging only moves the local fill; the seek is sent on release, because
 * every intermediate position would otherwise become its own network command.
 */
@Composable
private fun Scrubber(position: Double, duration: Double, onSeek: (Double) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val fraction = scrubbing ?: (position / duration).toFloat().coerceIn(0f, 1f)
    val shown = fraction * duration

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val fillColor = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(shown),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "-${formatTime(duration - shown)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width).coerceIn(0f, 1f) * duration)
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubbing = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            scrubbing?.let { onSeek(it * duration) }
                            scrubbing = null
                        },
                        onDragCancel = { scrubbing = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubbing = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(color = trackColor, cornerRadius = radius)
                if (fraction > 0f) {
                    drawRoundRect(
                        color = fillColor,
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, total % 3600 / 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(10.dp)
            .size(size),
    )
}

@Composable
private fun PillButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .repeatOnHold(onStep = onClick)
            .semantics {
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EnableNowPlaying(
    device: dev.atvremote.protocol.discovery.AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !state.busy) { vm.startAirPlayPairing(device) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.show_playing),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.needs_second_pairing),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (state.busy) "…" else stringResource(R.string.set_up),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AppGrid(state: UiState, vm: RemoteViewModel, modifier: Modifier = Modifier, columns: Int = 3) {
    if (state.apps.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                stringResource(R.string.loading_apps),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.apps, key = { it.bundleId }) { app ->
            AppTile(app) { vm.launchApp(app.bundleId) }
        }
    }
}

/**
 * One app, drawn as a tvOS-shaped tile.
 *
 * Real artwork is fetched in the background and takes a moment to arrive, so
 * every tile starts as initials over a colour derived from its bundle id. That
 * stand-in is also the permanent answer for anything the store does not know,
 * which covers the built-in apps.
 */
@Composable
private fun AppTile(app: AppInfo, onClick: () -> Unit) {
    // tvOS icons are landscape, 400x240, and the shelf reads wrong at 1:1.
    val hue = (app.bundleId.hashCode().absoluteValue % 360).toFloat()
    val tile = Brush.linearGradient(
        listOf(Color.hsv(hue, 0.45f, 0.62f), Color.hsv((hue + 26f) % 360f, 0.55f, 0.42f))
    )

    // Apple's own apps are drawn from bundled artwork and never fetched; they
    // are in no catalogue, and the tile is the same 5:3 either way.
    val bundled = remember(app.bundleId) { appleIcon(app.bundleId) }
    val lookupId = remember(app.bundleId) { storeBundleId(app.bundleId) }
    val context = LocalContext.current
    var artwork by remember(app.bundleId) { mutableStateOf<AppArtwork?>(null) }
    LaunchedEffect(app.bundleId) {
        if (lookupId != null) artwork = AppIcons.load(context, app.name, lookupId)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(5f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(tile)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val icon = artwork
            when {
                bundled != null -> Image(
                    painter = painterResource(bundled),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // Fetched artwork replaces the initials once it lands.
                icon != null -> Image(
                    bitmap = icon.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = if (icon.wide) ContentScale.Crop else ContentScale.Fit,
                    modifier = if (icon.wide) Modifier.fillMaxSize()
                    else Modifier.fillMaxHeight().padding(vertical = 7.dp),
                )

                else -> Text(
                    initials(app.name),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }

        Spacer(Modifier.height(5.dp))

        Text(
            app.name,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Up to two letters, the way a contact avatar reduces a name. */
private fun initials(name: String): String {
    val words = name.split(' ', '-', '+').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

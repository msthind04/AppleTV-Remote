package dev.danielclements.puck

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.delay
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
    val feedback = rememberFeedback(state.settings.hapticsEnabled, state.settings.buttonSoundEnabled)
    // Posture drives Book/Tabletop (DESIGN_SPEC.md §8); null on a non-foldable
    // device, where every mode below falls through to Phone/Landscape/Centered
    // exactly as it did before foldable support existed.
    val fold = rememberFoldingFeature()

    // The mode is chosen from the window, not the padded content area: the
    // 840dp/600dp breakpoints are window widths, and a Pixel Fold's inner
    // screen (~841dp) would otherwise lose 40dp to padding and miss Book.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .imePadding(),
    ) {
        val mode = computeLayoutMode(fold, maxWidth, maxHeight)
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {

        // Multi-pane modes always show an app grid, so their apps load up front.
        val appsVisible = showApps || mode == RemoteLayoutMode.BOOK || mode == RemoteLayoutMode.TABLETOP
        LaunchedEffect(appsVisible) {
            if (appsVisible && state.apps.isEmpty()) vm.loadApps()
        }

        // Folding or unfolding swaps layouts with a short crossfade; all of
        // the connection, pairing and now-playing state lives in the view
        // model, so nothing reconnects when the posture changes.
        Crossfade(targetState = mode, animationSpec = tween(250), label = "layoutMode") { current ->
        when (current) {
            RemoteLayoutMode.BOOK -> BookRemoteContent(device, state, vm, feedback, fold)
            RemoteLayoutMode.TABLETOP -> TabletopRemoteContent(device, state, vm, feedback, fold)
            RemoteLayoutMode.CENTERED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 520.dp).fillMaxHeight()) {
                    PhoneRemoteContent(device, state, vm, feedback, showApps) { showApps = !showApps }
                }
            }
            RemoteLayoutMode.LANDSCAPE ->
                LandscapeRemoteContent(device, state, vm, feedback, showApps) { showApps = !showApps }
            RemoteLayoutMode.PHONE ->
                PhoneRemoteContent(device, state, vm, feedback, showApps) { showApps = !showApps }
        }
        }
        }
    }
}

/**
 * The Apple TV's name and connection state, sized to whatever room the tab
 * tray leaves. The name takes up to two lines and ends in an ellipsis, so no
 * name, however long, can wrap past the header or push the tabs around.
 * While reconnecting it drops to one line to make room for the status.
 */
@Composable
private fun DeviceTitle(device: AppleTvDevice, state: UiState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dotColor = if (state.reconnecting) scheme.onSurfaceVariant else scheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .then(
                    if (!state.reconnecting) {
                        Modifier.drawBehind { drawCircle(dotColor.copy(alpha = 0.2f), radius = size.width * 0.9f) }
                    } else Modifier,
                )
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = PuckText.deviceTitle.copy(fontSize = 18.sp, lineHeight = 20.sp),
                color = scheme.onSurface,
                maxLines = if (state.reconnecting) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.reconnecting) {
                Text(
                    stringResource(R.string.reconnecting),
                    style = PuckText.caption,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The header row, shared by every layout mode (DESIGN_SPEC.md §6.1). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteHeader(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
    feedback: (HapticFeedbackType) -> Unit,
    appsActive: Boolean,
    onToggleApps: () -> Unit,
    onShowRemote: () -> Unit,
    modifier: Modifier = Modifier,
    /** Two-pane layouts always show the app grid, so they have no Apps tab. */
    appsTab: Boolean = true,
    /** Portrait phones move the tabs to a bottom bar and keep only power here. */
    tabsInHeader: Boolean = true,
) {
    Row(
        modifier = modifier.height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PuckRoundButton(
            onClick = { vm.disconnect() },
            size = 44.dp,
            containerColor = Puck.colors.raised,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_disconnect),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        DeviceTitle(device, state, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (!tabsInHeader) PowerButton(vm, feedback) else PuckActionTray {
            // The remote itself is a tab alongside keyboard and apps, lit
            // whenever neither of those is covering it.
            PuckTrayIconButton(onClick = onShowRemote, active = !state.keyboardOpen && !appsActive) {
                Icon(Icons.Default.SettingsRemote, contentDescription = stringResource(R.string.cd_remote), modifier = Modifier.size(20.dp))
            }
            PuckTrayIconButton(onClick = { vm.toggleKeyboard() }, active = state.keyboardOpen) {
                Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.cd_keyboard), modifier = Modifier.size(20.dp))
            }
            if (appsTab) PuckTrayIconButton(onClick = onToggleApps, active = appsActive) {
                Icon(Icons.Default.Apps, contentDescription = stringResource(R.string.cd_apps), modifier = Modifier.size(20.dp))
            }
            PowerButton(vm, feedback)
        }
    }
}

/**
 * A tap opens Control Centre, which is what the remote's own power
 * button does — sleeping outright, with no confirmation and no way
 * back, is not what pressing power on a remote means. Sleep lives
 * inside that panel; waking does not, since a sleeping TV has no
 * panel to open, so it stays on the hold.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PowerButton(vm: RemoteViewModel, feedback: (HapticFeedbackType) -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .combinedClickable(
                onClick = { vm.holdHome() },
                onLongClick = {
                    feedback(HapticFeedbackType.LongPress)
                    vm.wake()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.PowerSettingsNew,
            contentDescription = stringResource(R.string.cd_power),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * Portrait phones keep the Remote / Keyboard / Apps tabs along the bottom, in
 * thumb reach, which leaves the whole header to the device name.
 */
@Composable
private fun BottomTabs(
    state: UiState,
    vm: RemoteViewModel,
    appsActive: Boolean,
    onShowRemote: () -> Unit,
    onToggleApps: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .recessed(RoundedCornerShape(30.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BottomTab(Icons.Default.SettingsRemote, stringResource(R.string.cd_remote), !state.keyboardOpen && !appsActive, onShowRemote, Modifier.weight(1f))
        BottomTab(Icons.Default.Keyboard, stringResource(R.string.cd_keyboard), state.keyboardOpen, { vm.toggleKeyboard() }, Modifier.weight(1f))
        BottomTab(Icons.Default.Apps, stringResource(R.string.cd_apps), appsActive, onToggleApps, Modifier.weight(1f))
    }
}

@Composable
private fun BottomTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) Puck.colors.raised else Color.Transparent)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = PuckText.caption, color = if (selected) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Phone portrait, phone landscape, and Centered (Phone, just width-capped). */
@Composable
private fun PhoneRemoteContent(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
    feedback: (HapticFeedbackType) -> Unit,
    showApps: Boolean,
    onToggleApps: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val showRemote = {
            if (state.keyboardOpen) vm.toggleKeyboard()
            if (showApps) onToggleApps()
        }
        RemoteHeader(
            device, state, vm, feedback,
            appsActive = showApps,
            onToggleApps = onToggleApps,
            onShowRemote = showRemote,
            tabsInHeader = false,
        )

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
            TextEntry(device, state, vm)
        }


        // The drawer owns this screen when opened from the Apps tab: just the apps.
        AnimatedVisibility(
            visible = showApps,
            modifier = Modifier.weight(1f),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = ExitTransition.None,
        ) {
            AppGrid(state, vm, Modifier.fillMaxSize(), columns = 3)
        }

        if (!showApps) {
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
                RemotePad(
                    style = state.settings.remoteStyle,
                    showHint = !state.settings.padHintSeen,
                    onDismissHint = vm::dismissPadHint,
                    modifier = Modifier.fillMaxSize(),
                    hapticsEnabled = state.settings.hapticsEnabled,
                    soundEnabled = state.settings.buttonSoundEnabled,
                    sensitivity = state.settings.sensitivity.multiplier,
                    connected = !state.reconnecting,
                    onDirectionDown = {
                        feedback(HapticFeedbackType.LongPress)
                        vm.padDirectionDown(it)
                    },
                    onDirectionUp = { vm.padDirectionUp(it) },
                    onSelect = {
                        // OK commits the focused field: fold the panel with it.
                        if (state.keyboardOpen) vm.toggleKeyboard()
                        vm.press(Button.SELECT)
                    },
                    onSelectDown = { vm.selectDown() },
                    onSelectUp = { vm.selectUp() },
                    onTouch = { x, y, phase -> vm.touch(x, y, phase) },
                )
            }
            }

            Spacer(Modifier.weight(1f))
            Deck(state, vm)
            Spacer(Modifier.weight(1f))
        } else {
            // With the app grid open the grid takes the remaining height.
        }

        Spacer(Modifier.height(8.dp))
        BottomTabs(state, vm, appsActive = showApps, onShowRemote = showRemote, onToggleApps = onToggleApps)
    }
}

/**
 * Phone landscape: header on top; below it the Remote tab (the pad) or the
 * Apps tab (the grid) on the left, and a vertical deck on the right so
 * play/pause, back, home and volume are always in reach.
 */
@Composable
private fun LandscapeRemoteContent(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
    feedback: (HapticFeedbackType) -> Unit,
    showApps: Boolean,
    onToggleApps: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        RemoteHeader(
            device, state, vm, feedback,
            appsActive = showApps,
            onToggleApps = onToggleApps,
            onShowRemote = {
                if (state.keyboardOpen) vm.toggleKeyboard()
                if (showApps) onToggleApps()
            },
        )
        state.error?.let { message ->
            Spacer(Modifier.height(6.dp))
            ErrorBanner(message) { vm.dismissError() }
        }
        AnimatedVisibility(visible = state.keyboardOpen) {
            Column {
                Spacer(Modifier.height(8.dp))
                TextEntry(device, state, vm)
            }
        }
        Spacer(Modifier.height(10.dp))
        val deckOnRight = state.settings.controlsSide == ControlsSide.RIGHT
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!deckOnRight) TabletopDeck(state, vm, Modifier.width(236.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    showApps -> AppGrid(state, vm, Modifier.fillMaxSize(), columns = 4)
                    !state.keyboardOpen -> RemotePad(
                        style = state.settings.remoteStyle,
                        showHint = !state.settings.padHintSeen,
                        onDismissHint = vm::dismissPadHint,
                        modifier = Modifier.fillMaxSize(),
                        hapticsEnabled = state.settings.hapticsEnabled,
                        soundEnabled = state.settings.buttonSoundEnabled,
                        sensitivity = state.settings.sensitivity.multiplier,
                        connected = !state.reconnecting,
                        onDirectionDown = { feedback(HapticFeedbackType.LongPress); vm.padDirectionDown(it) },
                        onDirectionUp = { vm.padDirectionUp(it) },
                        onSelect = { vm.press(Button.SELECT) },
                        onSelectDown = { vm.selectDown() },
                        onSelectUp = { vm.selectUp() },
                        onTouch = { x, y, phase -> vm.touch(x, y, phase) },
                    )
                }
            }
            if (deckOnRight) TabletopDeck(state, vm, Modifier.width(236.dp).fillMaxHeight())
        }
    }
}

/**
 * Unfolded / wide: left pane is the phone remote (header, pad, deck), right
 * pane a Now Playing hero plus a 3-column app grid (DESIGN_SPEC.md §8,
 * `FoldBook.dc.html`). Not verified on a physical or emulated foldable —
 * there was none available this session; the layout and its reuse of the
 * already-gesture-tested TouchPad/Deck should be structurally sound, but
 * treat it as unverified until it's been run on real hardware.
 */
@Composable
private fun BookRemoteContent(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
    feedback: (HapticFeedbackType) -> Unit,
    fold: androidx.window.layout.FoldingFeature?,
) {
    // The pane gap is at least the hinge's own width so nothing sits under
    // it (DESIGN_SPEC.md §8), and never less than 40dp.
    val hingeWidth = fold?.takeIf { it.isSeparating }?.bounds?.let {
        with(androidx.compose.ui.platform.LocalDensity.current) { it.width().toDp() }
    } ?: 0.dp
    val controlsOnRight = state.settings.controlsSide == ControlsSide.RIGHT
    // The remote pane and the media pane trade places for left/right-handed use.
    val remotePane: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            RemoteHeader(
                device, state, vm, feedback,
                appsActive = false,
                onToggleApps = {},
                onShowRemote = { if (state.keyboardOpen) vm.toggleKeyboard() },
                appsTab = false,
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RemotePad(
                    style = state.settings.remoteStyle,
                    showHint = !state.settings.padHintSeen,
                    onDismissHint = vm::dismissPadHint,
                    modifier = Modifier.fillMaxSize(),
                    hapticsEnabled = state.settings.hapticsEnabled,
                    soundEnabled = state.settings.buttonSoundEnabled,
                    sensitivity = state.settings.sensitivity.multiplier,
                    connected = !state.reconnecting,
                    onDirectionDown = { feedback(HapticFeedbackType.LongPress); vm.padDirectionDown(it) },
                    onDirectionUp = { vm.padDirectionUp(it) },
                    onSelect = { vm.press(Button.SELECT) },
                    onSelectDown = { vm.selectDown() },
                    onSelectUp = { vm.selectUp() },
                    onTouch = { x, y, phase -> vm.touch(x, y, phase) },
                )
            }
            Spacer(Modifier.height(14.dp))
            Deck(state, vm)
        }
    }
    val mediaPane: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NowPlayingHero(device, state, vm, artworkAspect = 16f / 9f, stacked = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.apps_label), style = PuckText.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                AppGrid(state, vm, Modifier.weight(1f), columns = 4)
            }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(maxOf(hingeWidth, 40.dp))) {
        if (controlsOnRight) { mediaPane(); remotePane() } else { remotePane(); mediaPane() }
    }
}

/**
 * Half-open flat on a table: header + a Now Playing hero above the hinge,
 * trackpad + a vertical deck column below it (DESIGN_SPEC.md §8,
 * `FoldTabletop.dc.html`). Same caveat as [BookRemoteContent] — unverified
 * without a physical or emulated foldable to test the actual hinge split on.
 */
@Composable
private fun TabletopRemoteContent(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
    feedback: (HapticFeedbackType) -> Unit,
    fold: androidx.window.layout.FoldingFeature?,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            RemoteHeader(
                device, state, vm, feedback,
                appsActive = false,
                onToggleApps = {},
                onShowRemote = { if (state.keyboardOpen) vm.toggleKeyboard() },
                appsTab = false,
            )
            Spacer(Modifier.height(16.dp))
            NowPlayingHero(device, state, vm, artworkAspect = 1f, modifier = Modifier.weight(1f))
        }

        // The hinge itself: a plain gap at least as tall as the fold's own
        // bounds report, so nothing is drawn under it.
        val hingeHeight = fold?.bounds?.let { with(androidx.compose.ui.platform.LocalDensity.current) { it.height().toDp() } } ?: 0.dp
        Spacer(Modifier.height(maxOf(hingeHeight, 1.dp)))

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                RemotePad(
                    style = state.settings.remoteStyle,
                    showHint = !state.settings.padHintSeen,
                    onDismissHint = vm::dismissPadHint,
                    modifier = Modifier.fillMaxSize(),
                    hapticsEnabled = state.settings.hapticsEnabled,
                    soundEnabled = state.settings.buttonSoundEnabled,
                    sensitivity = state.settings.sensitivity.multiplier,
                    connected = !state.reconnecting,
                    onDirectionDown = { feedback(HapticFeedbackType.LongPress); vm.padDirectionDown(it) },
                    onDirectionUp = { vm.padDirectionUp(it) },
                    onSelect = { vm.press(Button.SELECT) },
                    onSelectDown = { vm.selectDown() },
                    onSelectUp = { vm.selectUp() },
                    onTouch = { x, y, phase -> vm.touch(x, y, phase) },
                )
            }
            TabletopDeck(state, vm, Modifier.width(236.dp).fillMaxHeight())
        }
    }
}

/**
 * The now-playing card, always visible while paired. Keeping it permanent
 * (playing or not) keeps the pad's size — and with it the tap-vs-swipe
 * geometry — identical in every state, which the gesture classifier relies
 * on. When nothing plays the card simply says so.
 */
@Composable
private fun NowPlayingSection(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
) {
    Spacer(Modifier.height(12.dp))
    if (state.airplayPaired) NowPlayingCard(state, state.nowPlaying ?: NowPlaying(), vm)
    else EnableNowPlaying(device, state, vm)
}

/**
 * The deck: a tray holding the transport row (back/menu, play/pause with its
 * "moat" ring, home) and the volume rocker (DESIGN_SPEC.md §6.3).
 */
@Composable
private fun Deck(state: UiState, vm: RemoteViewModel) {
    val feedback = rememberFeedback(state.settings.hapticsEnabled, state.settings.buttonSoundEnabled)
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(36.dp))
            .background(c.tray)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PuckRoundButton(
                onClick = { feedback(HapticFeedbackType.LongPress); vm.press(Button.MENU) },
                size = 68.dp,
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.menu),
                    modifier = Modifier.size(26.dp),
                )
            }

            // One button toggles playback, so it shows the action it will
            // perform. Without now-playing there is no state to reflect, and
            // it falls back to the play glyph.
            val isPlaying = state.nowPlaying?.playbackState == PlaybackState.PLAYING
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(c.tray)
                    .border(1.dp, scheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PuckRoundButton(
                    onClick = { feedback(HapticFeedbackType.LongPress); vm.press(Button.PLAY_PAUSE) },
                    size = 92.dp,
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            PuckRoundButton(
                onClick = { feedback(HapticFeedbackType.LongPress); vm.press(Button.HOME) },
                size = 68.dp,
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Icon(Icons.Default.Home, contentDescription = stringResource(R.string.home), modifier = Modifier.size(26.dp))
            }
        }

        PuckVolumeRocker(
            onDown = { feedback(HapticFeedbackType.LongPress); vm.volumeDown() },
            onUp = { feedback(HapticFeedbackType.LongPress); vm.volumeUp() },
            down = {
                Icon(Icons.Default.VolumeDown, contentDescription = stringResource(R.string.volume_down), modifier = Modifier.size(24.dp))
            },
            up = {
                Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.volume_up), modifier = Modifier.size(24.dp))
            },
        )
    }
}

/**
 * The larger Now Playing surface for Book/Tabletop (DESIGN_SPEC.md §8):
 * artwork at [artworkAspect], title/caption, scrubber and skip buttons — the
 * same information as [NowPlayingCard], laid out for a pane rather than a
 * fixed-height card. Falls back to [EnableNowPlaying]'s pairing prompt the
 * same way [NowPlayingSection] does.
 */
@Composable
private fun NowPlayingHero(
    device: AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
    artworkAspect: Float,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    if (!state.airplayPaired) {
        Box(modifier.fillMaxWidth()) {
            EnableNowPlaying(device, state, vm)
        }
        return
    }

    val playing = state.nowPlaying ?: NowPlaying()
    val artwork = remember(playing.artwork?.size) {
        playing.artwork?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    var position by remember { mutableStateOf(state.positionNow()) }
    LaunchedEffect(state.playhead, state.playheadAt, state.playheadAdvancing) {
        position = state.positionNow()
        while (state.playheadAdvancing) {
            delay(500)
            position = state.positionNow()
        }
    }

    val art: @Composable (Modifier) -> Unit = { m ->
        Box(
            modifier = m
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.tertiaryContainer),
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
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = scheme.onTertiaryContainer)
            }
        }
    }
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(
                playing.title ?: stringResource(R.string.nothing_playing),
                style = PuckText.screenTitle,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val stateLabel = when (playing.playbackState) {
                PlaybackState.SEEKING -> stringResource(R.string.state_seeking)
                PlaybackState.STOPPED -> stringResource(R.string.state_stopped)
                PlaybackState.INTERRUPTED -> stringResource(R.string.state_interrupted)
                else -> null
            }
            val caption = listOfNotNull(playing.appName ?: stateLabel).joinToString(" · ")
            if (caption.isNotBlank()) {
                Text(caption, style = PuckText.body, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            val elapsed = position
            val duration = playing.position?.second
            if (elapsed != null && duration != null) {
                Spacer(Modifier.height(14.dp))
                Scrubber(elapsed, duration) { vm.seekTo(it) }
            }

            if (!state.reconnecting) {
                Spacer(Modifier.height(10.dp))
                Row {
                    PuckRoundButton(onClick = vm::skipToPrevious, size = 52.dp, containerColor = c.raised, contentColor = scheme.onSurface) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.skip_previous_track), modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    PuckRoundButton(onClick = vm::skipToNext, size = 52.dp, containerColor = c.raised, contentColor = scheme.onSurface) {
                        Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.skip_next_track), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
        .background(c.card)
        .padding(16.dp)
    if (stacked) {
        // Book: artwork over the details, so the card takes only the height
        // it needs and leaves the rest of the pane to the app grid.
        Column(cardModifier) {
            art(Modifier.fillMaxWidth().aspectRatio(artworkAspect))
            Spacer(Modifier.height(16.dp))
            info(Modifier.fillMaxWidth())
        }
    } else {
        Row(cardModifier, verticalAlignment = Alignment.CenterVertically) {
            art(Modifier.fillMaxHeight().aspectRatio(artworkAspect))
            Spacer(Modifier.width(16.dp))
            info(Modifier.weight(1f))
        }
    }
}

/**
 * The deck turned sideways for Tabletop (DESIGN_SPEC.md §8, `FoldTabletop.dc.html`):
 * play/pause on top, back/home below it, then the volume rocker — the same
 * buttons as [Deck], stacked instead of in a row.
 */
@Composable
private fun TabletopDeck(state: UiState, vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val feedback = rememberFeedback(state.settings.hapticsEnabled, state.settings.buttonSoundEnabled)
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .background(c.tray)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val isPlaying = state.nowPlaying?.playbackState == PlaybackState.PLAYING
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(c.tray)
                .border(1.dp, scheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PuckRoundButton(
                onClick = { feedback(HapticFeedbackType.LongPress); vm.press(Button.PLAY_PAUSE) },
                size = 92.dp,
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PuckRoundButton(
                onClick = { feedback(HapticFeedbackType.LongPress); vm.press(Button.MENU) },
                size = 68.dp,
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.menu), modifier = Modifier.size(26.dp))
            }
            PuckRoundButton(
                onClick = { feedback(HapticFeedbackType.LongPress); vm.press(Button.HOME) },
                size = 68.dp,
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Icon(Icons.Default.Home, contentDescription = stringResource(R.string.home), modifier = Modifier.size(26.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        PuckVolumeRocker(
            onDown = { feedback(HapticFeedbackType.LongPress); vm.volumeDown() },
            onUp = { feedback(HapticFeedbackType.LongPress); vm.volumeUp() },
            down = { Icon(Icons.Default.VolumeDown, contentDescription = stringResource(R.string.volume_down), modifier = Modifier.size(24.dp)) },
            up = { Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.volume_up), modifier = Modifier.size(24.dp)) },
        )
    }
}

/**
 * The Siri Remote's click pad as one surface, and the whole surface is live.
 *
 * Styled to match the hardware remote's own glass clickpad as closely as a
 * flat screen can: plain dark glass, no printed arrows or labels. The real
 * pad carries no affordances either — its shape and the remote's physical
 * feel are the only things that teach a direction tap from a press from a
 * swipe, so this one leans on the same gesture logic to carry that meaning
 * without needing glyphs to say it.
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
 */
/** The real centre-vs-edge hit threshold, as a fraction of the pad's own diameter — see [TouchPad]'s gesture block. */
/** A square-ish rounded rectangle, not a pill — the trackpad is plain glass. */
private val PAD_CORNER = 28.dp

private const val ZONE_DIAMETER_FRACTION = 0.38f * 2f

/**
 * The main control surface: the trackpad, or a plain four-way D-pad, per the
 * Remote style setting. Also owns the TalkBack custom actions (a screen
 * reader cannot swipe a trackpad) and the first-run hint overlay.
 */
@Composable
private fun RemotePad(
    style: RemoteStyle,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
    soundEnabled: Boolean = false,
    sensitivity: Float = 1f,
    connected: Boolean = true,
    showHint: Boolean = false,
    onDismissHint: () -> Unit = {},
    onDirectionDown: (Button) -> Unit,
    onDirectionUp: (Button) -> Unit,
    onSelect: () -> Unit,
    onSelectDown: () -> Unit,
    onSelectUp: () -> Unit,
    onTouch: (x: Int, y: Int, phase: TouchPhase) -> Unit,
) {
    val tap: (Button) -> Unit = { onDirectionDown(it); onDirectionUp(it) }
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.cd_dpad_up)) { tap(Button.UP); true },
        CustomAccessibilityAction(stringResource(R.string.cd_dpad_down)) { tap(Button.DOWN); true },
        CustomAccessibilityAction(stringResource(R.string.cd_dpad_left)) { tap(Button.LEFT); true },
        CustomAccessibilityAction(stringResource(R.string.cd_dpad_right)) { tap(Button.RIGHT); true },
        CustomAccessibilityAction(stringResource(R.string.cd_dpad_select)) { onSelectDown(); onSelectUp(); true },
    )
    Box(modifier) {
        when (style) {
            RemoteStyle.TRACKPAD -> TouchPad(
                Modifier.fillMaxSize(), hapticsEnabled, soundEnabled, sensitivity, connected,
                onDirectionDown, onDirectionUp, onSelect, onSelectDown, onSelectUp, onTouch, actions,
            )
            RemoteStyle.DPAD -> DPad(
                Modifier.fillMaxSize(), hapticsEnabled, soundEnabled,
                onDirectionDown, onDirectionUp, onSelectDown, onSelectUp, actions,
            )
        }
        if (showHint) PadHint(style, onDismissHint)
    }
}

/** One-time overlay explaining the pad; any tap dismisses it. */
@Composable
private fun PadHint(style: RemoteStyle, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lines = if (style == RemoteStyle.TRACKPAD) {
        listOf(R.string.hint_trackpad_1, R.string.hint_trackpad_2, R.string.hint_trackpad_3)
    } else {
        listOf(R.string.hint_dpad_1, R.string.hint_dpad_2, R.string.hint_dpad_3)
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(PAD_CORNER))
            .background(Puck.colors.tray.copy(alpha = 0.96f))
            .clickable(role = Role.Button, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            Text(stringResource(R.string.hint_title), style = PuckText.rowTitle, color = scheme.onSurface)
            Spacer(Modifier.height(10.dp))
            lines.forEach {
                Text(stringResource(it), style = PuckText.body, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.hint_dismiss), style = PuckText.rowTitle, color = scheme.onPrimaryContainer)
            }
        }
    }
}

/**
 * A ring split into four wedges around a centre OK disc, the way a physical
 * remote's click ring is laid out. A press sends the button down and a
 * release sends it up, so holding repeats exactly as the trackpad's hold does.
 */
@Composable
private fun DPad(
    modifier: Modifier,
    hapticsEnabled: Boolean,
    soundEnabled: Boolean,
    onDirectionDown: (Button) -> Unit,
    onDirectionUp: (Button) -> Unit,
    onSelectDown: () -> Unit,
    onSelectUp: () -> Unit,
    a11yActions: List<CustomAccessibilityAction>,
) {
    val feedback = rememberFeedback(hapticsEnabled, soundEnabled)
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors
    val description = stringResource(R.string.cd_dpad)
    // null = nothing held; SELECT is the centre disc.
    var held by remember { mutableStateOf<Button?>(null) }
    val centreFraction = 0.5f

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(side)
                .semantics { contentDescription = description; customActions = a11yActions }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f
                        val delta = down.position - centre
                        val dist = delta.getDistance()
                        if (dist > radius) return@awaitEachGesture
                        val button = when {
                            dist <= radius * centreFraction -> Button.SELECT
                            kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y) ->
                                if (delta.x > 0) Button.RIGHT else Button.LEFT
                            else -> if (delta.y > 0) Button.DOWN else Button.UP
                        }
                        held = button
                        feedback(HapticFeedbackType.LongPress)
                        if (button == Button.SELECT) onSelectDown() else onDirectionDown(button)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            held = null
                            if (button == Button.SELECT) onSelectUp() else onDirectionUp(button)
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.width / 2f
                val centre = Offset(r, r)
                drawCircle(c.tray, radius = r, center = centre)
                drawCircle(scheme.outlineVariant, radius = r - 0.5.dp.toPx(), center = centre, style = Stroke(1.dp.toPx()))
                // Wedge highlight for the held direction.
                val sweepStart = when (held) {
                    Button.RIGHT -> -45f
                    Button.DOWN -> 45f
                    Button.LEFT -> 135f
                    Button.UP -> 225f
                    else -> null
                }
                if (sweepStart != null) {
                    drawArc(
                        scheme.primaryContainer, sweepStart, 90f, useCenter = true,
                        topLeft = Offset.Zero, size = size,
                    )
                }
                // Diagonal seams between the wedges.
                val seam = scheme.outlineVariant
                val d = r * 0.7071f
                for ((sx, sy) in listOf(1 to 1, 1 to -1)) {
                    drawLine(
                        seam, Offset(r - sx * d, r - sy * d), Offset(r + sx * d, r + sy * d), 1.dp.toPx(),
                    )
                }
                drawCircle(c.raised, radius = r * centreFraction, center = centre)
                drawCircle(
                    if (held == Button.SELECT) scheme.primaryContainer else Color.Transparent,
                    radius = r * centreFraction, center = centre,
                )
                drawCircle(seam, radius = r * centreFraction, center = centre, style = Stroke(1.dp.toPx()))
            }
            // Chevrons sit mid-wedge, between the centre disc and the rim.
            val arrowOffset = side * (1f + centreFraction) / 4f
            val chevron = scheme.onSurface
            listOf(
                Triple(Icons.Default.KeyboardArrowUp, 0.dp to -arrowOffset, R.string.cd_dpad_up),
                Triple(Icons.Default.KeyboardArrowDown, 0.dp to arrowOffset, R.string.cd_dpad_down),
                Triple(Icons.Default.KeyboardArrowLeft, -arrowOffset to 0.dp, R.string.cd_dpad_left),
                Triple(Icons.Default.KeyboardArrowRight, arrowOffset to 0.dp, R.string.cd_dpad_right),
            ).forEach { (icon, offset, label) ->
                Icon(
                    icon,
                    contentDescription = stringResource(label),
                    tint = chevron,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(offset.first, offset.second)
                        .size(34.dp),
                )
            }
            Text(
                "OK",
                style = PuckText.label,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun TouchPad(
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
    soundEnabled: Boolean = false,
    sensitivity: Float = 1f,
    connected: Boolean = true,
    onDirectionDown: (Button) -> Unit,
    onDirectionUp: (Button) -> Unit,
    onSelect: () -> Unit,
    onSelectDown: () -> Unit,
    onSelectUp: () -> Unit,
    onTouch: (x: Int, y: Int, phase: TouchPhase) -> Unit,
    a11yActions: List<CustomAccessibilityAction>,
) {
    val feedback = rememberFeedback(hapticsEnabled, soundEnabled)
    val touchPadDescription = stringResource(R.string.touchpad_description)
    val scheme = MaterialTheme.colorScheme
    val puckColors = Puck.colors

    // Drawing-only state the gesture block below feeds as it runs — none of
    // it is read by the gesture logic itself, only by drawTrackpad().
    var touchPoint by remember { mutableStateOf<Offset?>(null) }
    var touchTrail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var centerPressed by remember { mutableStateOf(false) }
    val ringAlpha by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = tween(400),
        label = "trackpadRing",
    )

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        val haloColor = scheme.primary.copy(alpha = 0.08f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(side)
                .then(
                    if (connected) Modifier.border(6.dp, haloColor, RoundedCornerShape(PAD_CORNER))
                    else Modifier,
                )
                .clip(RoundedCornerShape(PAD_CORNER))
                .pointerInput(Unit) {
                    val centre = 500f
                    // Frac is measured against a fixed reference pad — the size
                    // the sensitivity curve was tuned on — not against this
                    // surface. The surface changes shape with the layout, and a
                    // small one would amplify finger tremor into focus jumps.
                    //
                    // 160 dp, not the phone's full touch surface: a real Siri
                    // Remote's clickpad is only ~35 mm, and even with the gain
                    // curve's amplification a reference closer to this surface's
                    // actual size (300dp) left an ordinary swipe barely crossing
                    // it once — reading on the TV as "moved one item," not a
                    // swipe. A smaller reference means the same finger travel
                    // crosses it more times, which is both more frac (more raw
                    // movement) and higher frac/sec (more acceleration gain).
                    val refPx = 160.dp.toPx()
                    // A touch more generous than the visual circle (0.30):
                    // taps near its edge are still selects, and the rim
                    // chevrons sit far enough out to stay directional.
                    val centreRadius = minOf(size.width, size.height) * 0.38f

                    fun direction(pos: Offset) {
                        feedback(HapticFeedbackType.LongPress)
                        val dx = pos.x - size.width / 2f
                        val dy = pos.y - size.height / 2f
                        val button = if (abs(dx) > abs(dy)) {
                            if (dx > 0) Button.RIGHT else Button.LEFT
                        } else {
                            if (dy > 0) Button.DOWN else Button.UP
                        }
                        onDirectionDown(button)
                        onDirectionUp(button)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        // Deliberately larger than the system touch slop: a
                        // tap whose finger drifts a few dp must not turn into
                        // a micro-swipe — in video players even a tiny
                        // horizontal swipe skips ±10 seconds, which read as
                        // mysterious rewinds on centre taps.
                        // Rim vs centre is decided on touch-down, so rim
                        // presses respond the instant the finger lands.
                        val centreDown = run {
                            val dx = start.x - size.width / 2f
                            val dy = start.y - size.height / 2f
                            hypot(dx, dy) < centreRadius
                        }

                        // Rim presses hold the HID key down, so tvOS
                        // repeats the direction for as long as the finger
                        // stays; the response is instantaneous.
                        var rimDirection: Button? = null
                        if (!centreDown) {
                            val dir = run {
                                val dx = start.x - size.width / 2f
                                val dy = start.y - size.height / 2f
                                if (abs(dx) > abs(dy)) {
                                    if (dx > 0) Button.RIGHT else Button.LEFT
                                } else {
                                    if (dy > 0) Button.DOWN else Button.UP
                                }
                            }
                            onDirectionDown(dir)
                            rimDirection = dir
                        }

                        var drag = false
                        var tap = false
                        var holdSelect = false
                        var upPos = start

                        var vx = centre
                        var vy = centre
                        var lastPos = start
                        var lastTime = down.uptimeMillis

                        fun advance(pos: Offset, time: Long) {
                            val dt = (time - lastTime).coerceAtLeast(1L) / 1000f
                            val fracX = (pos.x - lastPos.x) / refPx
                            val fracY = (pos.y - lastPos.y) / refPx
                            val speed = hypot(fracX, fracY) / dt
                            val gain = TouchAcceleration.gain(speed)
                            vx = (vx + fracX * 1000f * gain * HORIZONTAL_SENSITIVITY * sensitivity)
                                .coerceIn(0f, 1000f)
                            vy = (vy + fracY * 1000f * gain * VERTICAL_SENSITIVITY * sensitivity)
                                .coerceIn(0f, 1000f)
                            lastPos = pos
                            lastTime = time
                            onTouch(vx.toInt(), vy.toInt(), TouchPhase.HOLD)
                            // Drawing only: the live finger dot and its short trail.
                            touchPoint = pos
                            touchTrail = (touchTrail + pos).takeLast(8)
                        }

                        // Phase 1: for centre touches, wait for up (tap), the
                        // long-press timeout (held select), or movement (drag).
                        if (centreDown) {
                            val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) { tap = true; upPos = change.position; break }
                                    if ((change.position - start).getDistance() > CENTRE_COMMIT_DP.dp.toPx()) { drag = true; break }
                                    change.consume()
                                }
                                true
                            }
                            if (finished == null) {
                                holdSelect = true
                                centerPressed = true
                                onSelectDown()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) { upPos = change.position; break }
                                    change.consume()
                                }
                                centerPressed = false
                            }
                        } else {
                            // Rim: the direction is already held down; a
                            // movement past the slop hands the gesture over to
                            // a swipe.
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) { tap = true; upPos = change.position; break }
                                if ((change.position - start).getDistance() > RIM_COMMIT_DP.dp.toPx()) { drag = true; break }
                                change.consume()
                            }
                        }

                        if (drag) {
                            if (rimDirection != null) onDirectionUp(rimDirection)
                            onTouch(500, 500, TouchPhase.PRESS)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                advance(change.position, change.uptimeMillis)
                                if (change.changedToUp()) break
                                change.consume()
                            }
                            onTouch(vx.toInt(), vy.toInt(), TouchPhase.RELEASE)
                            touchPoint = null
                            touchTrail = emptyList()
                        } else {
                            when {
                                holdSelect -> onSelectUp()
                                centreDown && tap -> {
                                    // Classify by the midpoint of the gesture:
                                    // a tap that drifts a little is still a
                                    // select unless it clearly left the centre.
                                    val mid = Offset(
                                        (start.x + upPos.x) / 2f,
                                        (start.y + upPos.y) / 2f,
                                    )
                                    val dx = mid.x - size.width / 2f
                                    val dy = mid.y - size.height / 2f
                                    if (hypot(dx, dy) < centreRadius) onSelect()
                                    else direction(mid)
                                }
                                else -> onDirectionUp(rimDirection ?: return@awaitEachGesture)
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = touchPadDescription
                    customActions = a11yActions
                },
            contentAlignment = Alignment.Center,
        ) {
            // A plain glass surface, like the hardware clickpad: no printed
            // arrows, markers or centre target — the gestures alone (tap an
            // edge, press the middle, swipe anywhere) are the interface.
            // Only touch feedback is drawn; see PuckDepth.kt's drawTrackpad().
            // TalkBack still gets a description (above), just not a visible one.
            Canvas(Modifier.fillMaxSize()) {
                drawTrackpad(
                    s = TrackpadVisualState(
                        connected = connected,
                        ringAlpha = ringAlpha,
                        touch = touchPoint,
                        trail = touchTrail,
                        centerPressed = centerPressed,
                    ),
                    scheme = scheme,
                    c = puckColors,
                    corner = PAD_CORNER,
                )
            }
        }
    }
}

/**
 * Fire [onStep] once on touch-down, then keep repeating while the finger stays
 * down — first repeat after [firstDelayMs], then every [stepDelayMs]. How the
 * volume keys step without being tapped over and over.
 */
fun Modifier.repeatOnHold(
    firstDelayMs: Long = 400,
    stepDelayMs: Long = 150,
    onPressedChange: (Boolean) -> Unit = {},
    onStep: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPressedChange(true)
        onStep()
        var steps = 1
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                val heldFor = (change.uptimeMillis - down.uptimeMillis - firstDelayMs)
                    .coerceAtLeast(0L)
                val target = 1 + (heldFor / stepDelayMs).toInt()
                while (steps < target) {
                    onStep()
                    steps++
                }
                if (!change.pressed) break
                change.consume()
            }
        } finally {
            onPressedChange(false)
        }
    }
}

// Both axes 1:1 against the reference pad, so the same finger travel moves
// the focus the same distance whichever way you swipe.
private const val HORIZONTAL_SENSITIVITY = 1.0f
private const val VERTICAL_SENSITIVITY = 1.0f

/**
 * A haptic buzz, plus an optional system click sound, for a discrete button
 * press — gated by the user's Settings toggles rather than always firing.
 *
 * Every call site asks for [HapticFeedbackType.LongPress], not the seemingly
 * more fitting [HapticFeedbackType.TextHandleMove] — it maps to
 * `HapticFeedbackConstants.TEXT_HANDLE_MOVE`, which several OEMs (Samsung's
 * One UI among them) silently no-op, since it's meant for a text cursor
 * being dragged, not a generic button tap. `LONG_PRESS` is the one that
 * reliably fires everywhere, Compose's haptic API just doesn't expose
 * anything named for the occasion.
 */
@Composable
private fun rememberFeedback(hapticsEnabled: Boolean, soundEnabled: Boolean): (HapticFeedbackType) -> Unit {
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    return remember(hapticsEnabled, soundEnabled, haptics, view) {
        { type: HapticFeedbackType ->
            if (hapticsEnabled) haptics.performHapticFeedback(type)
            if (soundEnabled) view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }
}

/**
 * How far the finger must travel before a gesture commits as a swipe. Below
 * it everything is a tap — the gate that keeps tap drift from becoming a
 * micro-swipe (which video players read as a +-10 second skip).
 */
private const val RIM_COMMIT_DP = 40

/**
 * Centre taps get a larger allowance: the pad shrinks while the now-playing
 * card is up, and a tap near the small circle's edge must still be a select —
 * a rim misread there plays as a 10 second skip in the player.
 */
private const val CENTRE_COMMIT_DP = 60

/**
 * Text entry for the focused field on the Apple TV.
 *
 * Text is pushed on submit rather than per keystroke: each send restarts the
 * RTI session to read authoritative state, so per-character sends would be both
 * slow and racy.
 */
@Composable
private fun TextEntry(device: AppleTvDevice, state: UiState, vm: RemoteViewModel) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Styled per DESIGN_SPEC.md §6.5's panel (raised fill, well field with a
    // primary border, a primary send puck) but kept as the existing inline
    // expanding panel rather than turned into a real bottom sheet overlay —
    // that would change whether the trackpad stays mounted underneath it,
    // which is layout behavior, not presentation.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.raised)
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.keyboard_panel_label, device.name.uppercase()),
            style = PuckText.label,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text(stringResource(R.string.type_on_tv)) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.well,
                    unfocusedContainerColor = c.well,
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.outlineVariant,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendText(draft)
                    draft = ""
                }),
            )
            Spacer(Modifier.width(10.dp))
            PuckRoundButton(
                onClick = { vm.sendText(draft); draft = "" },
                size = 56.dp,
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            ) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send_text), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            PuckRoundButton(
                onClick = { vm.clearText() },
                size = 56.dp,
                containerColor = c.card,
                contentColor = scheme.onSurfaceVariant,
            ) {
                Icon(Icons.Default.Backspace, contentDescription = stringResource(R.string.clear_field), modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        val status = when {
            state.checkingField -> stringResource(R.string.checking_field)
            state.fieldText == null -> stringResource(R.string.no_field)
            state.fieldText.isEmpty() -> stringResource(R.string.field_empty)
            else -> stringResource(R.string.field_is, state.fieldText)
        }
        Text(status, style = PuckText.caption, color = scheme.onSurfaceVariant)
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

    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Fixed height: the card must occupy the same space whether it is
            // empty or full — the pad's touch geometry depends on it.
            .height(124.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.card),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.tertiaryContainer),
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
                            tint = scheme.onTertiaryContainer,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        playing.title ?: stringResource(R.string.nothing_playing),
                        style = PuckText.rowTitle,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    // The app is the source line. Playing and paused are
                    // already obvious from the transport glyph, so only the
                    // states that are not are worth spelling out.
                    val stateLabel = when (playing.playbackState) {
                        PlaybackState.SEEKING -> stringResource(R.string.state_seeking)
                        PlaybackState.STOPPED -> stringResource(R.string.state_stopped)
                        PlaybackState.INTERRUPTED -> stringResource(R.string.state_interrupted)
                        else -> null
                    }
                    val elapsedLabel = position?.let { formatTime(it) }
                    val caption = listOfNotNull(playing.appName ?: stateLabel, elapsedLabel).joinToString(" · ")
                    if (caption.isNotBlank()) {
                        Text(
                            caption,
                            style = PuckText.caption,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // New: previous/next track skip buttons, shown only while
                // connected — Now Playing being enabled at all is already
                // guaranteed by the caller (NowPlayingSection only shows this
                // card when state.airplayPaired is true).
                if (!state.reconnecting) {
                    Spacer(Modifier.width(8.dp))
                    PuckRoundButton(
                        onClick = vm::skipToPrevious,
                        size = 44.dp,
                        containerColor = c.raised,
                        contentColor = scheme.onSurface,
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.skip_previous_track), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    PuckRoundButton(
                        onClick = vm::skipToNext,
                        size = 44.dp,
                        containerColor = c.raised,
                        contentColor = scheme.onSurface,
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.skip_next_track), modifier = Modifier.size(20.dp))
                    }
                }
            }

            // One compact row: skip back, the scrubber with its times, skip
            // forward. Transport stays available while paused: skipping about
            // is as useful then as during playback. Play/pause itself lives
            // in the deck, so it is not repeated here.
            val elapsed = position
            val duration = playing.position?.second
            val seekable = playing.playbackState in setOf(
                PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.SEEKING,
            )
            if (elapsed != null && duration != null) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (seekable) TransportButton(Icons.Default.Replay10, stringResource(R.string.skip_back), 22.dp) {
                        vm.skip(-SKIP_SECONDS)
                    }
                    Box(Modifier.weight(1f)) { Scrubber(elapsed, duration) { vm.seekTo(it) } }
                    if (seekable) TransportButton(Icons.Default.Forward10, stringResource(R.string.skip_forward), 22.dp) {
                        vm.skip(SKIP_SECONDS)
                    }
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

    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = MaterialTheme.colorScheme.tertiary

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(shown),
                style = PuckText.data,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "-${formatTime(duration - shown)}",
                style = PuckText.data,
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
private fun EnableNowPlaying(
    device: dev.atvremote.protocol.discovery.AppleTvDevice,
    state: UiState,
    vm: RemoteViewModel,
) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.outlineVariant
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                )
            }
            .clickable(enabled = !state.busy) { vm.startAirPlayPairing(device) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.show_playing),
            style = PuckText.body.copy(fontWeight = FontWeight.Medium),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (state.busy) "…" else stringResource(R.string.set_up),
            style = PuckText.body.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.primary,
        )
    }
}

@Composable
private fun AppGrid(state: UiState, vm: RemoteViewModel, modifier: Modifier = Modifier, columns: Int = 3) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Puck.colors.tray)
            .padding(14.dp),
    ) {
        if (state.apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Text(stringResource(R.string.loading_apps), style = PuckText.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Box
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(state.apps, key = { it.bundleId }) { app ->
                AppTile(app) { vm.launchApp(app.bundleId) }
            }
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
                .clip(RoundedCornerShape(14.dp))
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
            style = PuckText.caption,
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

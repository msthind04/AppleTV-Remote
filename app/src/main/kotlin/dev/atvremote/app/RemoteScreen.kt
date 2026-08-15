package dev.atvremote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.BitmapFactory
import dev.atvremote.protocol.mrp.NowPlaying
import dev.atvremote.protocol.mrp.PlaybackState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.atvremote.protocol.companion.Button
import dev.atvremote.protocol.discovery.AppleTvDevice
import kotlin.math.abs

@Composable
fun RemoteScreen(device: AppleTvDevice, state: UiState, vm: RemoteViewModel) {
    var showApps by remember { mutableStateOf(false) }

    LaunchedEffect(showApps) {
        if (showApps && state.apps.isEmpty()) vm.loadApps()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // ---- header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Disconnect",
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
                    state.reconnecting -> "Reconnecting…"
                    state.capabilities?.volume == true && state.volume != null ->
                        "Volume ${(state.volume * 100).toInt()}%"
                    else -> "Connected"
                }
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.Keyboard,
                contentDescription = "Keyboard",
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
                contentDescription = "Apps",
                tint = if (showApps) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { showApps = !showApps }
                    .padding(8.dp),
            )
        }

        state.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            ErrorBanner(message) { vm.dismissError() }
        }

        if (showApps) {
            Spacer(Modifier.height(12.dp))
            AppsRow(state, vm)
        }

        if (state.keyboardOpen) {
            Spacer(Modifier.height(12.dp))
            TextEntry(state, vm)
        }

        val playing = state.nowPlaying
        if (playing != null && playing.isActive) {
            Spacer(Modifier.height(12.dp))
            NowPlayingCard(playing, vm)
        } else if (!state.airplayPaired) {
            Spacer(Modifier.height(12.dp))
            EnableNowPlaying(device, state, vm)
        }

        Spacer(Modifier.height(14.dp))

        PadModeToggle(state.padMode) { vm.setPadMode(it) }

        Spacer(Modifier.height(10.dp))

        // ---- touch surface: D-pad taps or trackpad swiping ----
        TouchPad(
            mode = state.padMode,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            onDirection = { vm.press(it) },
            onSelect = { vm.press(Button.SELECT) },
            onSwipe = { sx, sy, ex, ey -> vm.swipe(sx, sy, ex, ey, 220) },
        )

        Spacer(Modifier.height(20.dp))

        // ---- transport row ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Menu") { vm.press(Button.MENU) }

            // One button toggles playback, so it shows the action it will
            // perform. Without now-playing there is no state to reflect, and
            // it falls back to the play glyph.
            val isPlaying = state.nowPlaying?.playbackState == PlaybackState.PLAYING
            RoundButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
            ) { vm.press(Button.PLAY_PAUSE) }

            RoundButton(Icons.Default.Home, "Home") { vm.press(Button.HOME) }
        }

        // Volume only appears when the Apple TV reports it can route it. With an
        // IR setup the Siri Remote blasts infrared itself, so nothing on the
        // network can change the volume and dead buttons would be misleading.
        if (state.capabilities?.volume == true) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundButton(Icons.Default.VolumeDown, "Volume down") { vm.volumeDown() }
                RoundButton(Icons.Default.VolumeUp, "Volume up") { vm.volumeUp() }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Combined D-pad and trackpad.
 *
 * A tap near the centre selects; a tap elsewhere maps to the dominant
 * direction, which is what makes it usable one-handed. Dragging sends a real
 * swipe so momentum scrolling in tvOS lists behaves naturally.
 */
@Composable
private fun TouchPad(
    mode: PadMode,
    modifier: Modifier = Modifier,
    onDirection: (Button) -> Unit,
    onSelect: () -> Unit,
    onSwipe: (Int, Int, Int, Int) -> Unit,
) {
    // Keyed on `mode` so the gesture detectors are rebuilt when it changes.
    val gestures = when (mode) {
        PadMode.DPAD -> Modifier.pointerInput(mode) {
            detectTapGestures { offset ->
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = offset.x - cx
                val dy = offset.y - cy
                val deadZone = size.width * 0.22f

                if (abs(dx) < deadZone && abs(dy) < deadZone) {
                    onSelect()
                } else if (abs(dx) > abs(dy)) {
                    onDirection(if (dx > 0) Button.RIGHT else Button.LEFT)
                } else {
                    onDirection(if (dy > 0) Button.DOWN else Button.UP)
                }
            }
        }

        PadMode.SWIPE -> Modifier
            .pointerInput(mode) {
                // A tap still selects; only directional taps are given up.
                detectTapGestures { onSelect() }
            }
            .pointerInput(mode) {
                var start = Offset.Zero
                var current = Offset.Zero
                detectDragGestures(
                    onDragStart = { start = it; current = it },
                    onDrag = { change, amount ->
                        change.consume()
                        current += amount
                    },
                    onDragEnd = {
                        // Map local pixels into the 0..1000 space the device uses.
                        val sx = (start.x / size.width * 1000).toInt().coerceIn(0, 1000)
                        val sy = (start.y / size.height * 1000).toInt().coerceIn(0, 1000)
                        val ex = (current.x / size.width * 1000).toInt().coerceIn(0, 1000)
                        val ey = (current.y / size.height * 1000).toInt().coerceIn(0, 1000)
                        if (abs(ex - sx) > 40 || abs(ey - sy) > 40) onSwipe(sx, sy, ex, ey)
                    },
                )
            }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        // Direction affordances only apply when taps steer.
        if (mode == PadMode.DPAD) {
        Icon(
            Icons.Default.KeyboardArrowUp, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(30.dp),
        )
        Icon(
            Icons.Default.KeyboardArrowDown, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).size(30.dp),
        )
        Icon(
            Icons.Default.KeyboardArrowLeft, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp).size(30.dp),
        )
        Icon(
            Icons.Default.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(30.dp),
        )
        }

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "OK",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (mode == PadMode.SWIPE) {
            Text(
                "Swipe to scroll · tap to select",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            )
        }
    }
}

/** Segmented control choosing how the touch surface interprets gestures. */
@Composable
private fun PadModeToggle(current: PadMode, onSelect: (PadMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PadModeOption(
            label = "D-pad",
            icon = Icons.Default.Gamepad,
            selected = current == PadMode.DPAD,
            modifier = Modifier.weight(1f),
        ) { onSelect(PadMode.DPAD) }

        PadModeOption(
            label = "Swipe",
            icon = Icons.Default.TouchApp,
            selected = current == PadMode.SWIPE,
            modifier = Modifier.weight(1f),
        ) { onSelect(PadMode.SWIPE) }
    }
}

@Composable
private fun PadModeOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
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
                placeholder = { Text("Type on the Apple TV") },
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
                    contentDescription = "Send text",
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
                    contentDescription = "Clear field",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        val status = when {
            state.checkingField -> "Checking for a focused field…"
            state.fieldText == null -> "No text field focused on the Apple TV."
            state.fieldText.isEmpty() -> "Field is empty."
            else -> "Field: \"${state.fieldText}\""
        }
        Text(
            status,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Now-playing summary.
 *
 * Artwork arrives as raw bytes on the MRP channel; it is decoded here rather
 * than in the protocol layer so that module stays free of Android types.
 */
@Composable
private fun NowPlayingCard(playing: NowPlaying, vm: RemoteViewModel) {
    val artwork = remember(playing.artwork?.size) {
        playing.artwork?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
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
            Text(
                playing.title ?: "Nothing playing",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            playing.artist?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val badge = buildString {
                append(
                    when (playing.playbackState) {
                        PlaybackState.PLAYING -> "Playing"
                        PlaybackState.PAUSED -> "Paused"
                        PlaybackState.SEEKING -> "Seeking"
                        PlaybackState.STOPPED -> "Stopped"
                        PlaybackState.INTERRUPTED -> "Interrupted"
                        PlaybackState.UNKNOWN -> ""
                    }
                )
                playing.appName?.let { if (isNotEmpty()) append(" · "); append(it) }
            }
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

        // Seek controls appear whenever there is a real item loaded. Skipping
        // while paused is just as useful as while playing, so this is not
        // restricted to the playing state.
        val seekable = playing.playbackState in setOf(
            PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.SEEKING,
        )
        if (seekable) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeekButton(
                    icon = Icons.Default.Replay10,
                    label = "10s back",
                    modifier = Modifier.weight(1f),
                ) { vm.skip(-SKIP_SECONDS) }

                SeekButton(
                    icon = Icons.Default.Forward10,
                    label = "10s forward",
                    modifier = Modifier.weight(1f),
                ) { vm.skip(SKIP_SECONDS) }
            }
        }
    }
}

private const val SKIP_SECONDS = 10.0

@Composable
private fun SeekButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
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
                "Show what's playing",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Needs a second pairing code from the TV",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (state.busy) "…" else "Set up",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AppsRow(state: UiState, vm: RemoteViewModel) {
    if (state.apps.isEmpty()) {
        Text(
            "Loading apps…",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.apps, key = { it.bundleId }) { app ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { vm.launchApp(app.bundleId) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    app.name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

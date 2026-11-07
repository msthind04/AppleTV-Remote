package dev.danielclements.puck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.danielclements.puck.R
import dev.atvremote.protocol.discovery.AppleTvDevice
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.LaunchedEffect

@Composable
fun PinEntryScreen(device: AppleTvDevice, state: UiState, vm: RemoteViewModel) {
    var pin by remember { mutableStateOf("") }
    val forAirPlay = (state.screen as? Screen.PinEntry)?.forAirPlay == true
    val focusRequester = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The TV glyph: four dots, two lit, in a recessed well with a raised
        // bezel — a static echo of the code shown on the actual TV screen.
        Box(
            modifier = Modifier
                .width(176.dp)
                .height(112.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.well)
                .border(6.dp, c.raised, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(scheme.primary.copy(alpha = if (i < 2) 1f else 0.35f)),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 14.dp)
                    .size(56.dp, 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.raised),
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            if (forAirPlay) stringResource(R.string.pin_enable_now_playing) else stringResource(R.string.pin_pair_with, device.name),
            style = PuckText.screenTitleLarge,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (forAirPlay) stringResource(R.string.pin_airplay_hint) else stringResource(R.string.pin_hint),
            style = PuckText.body,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(28.dp))

        Text(stringResource(R.string.pin_code_label), style = PuckText.label, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        Box {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    PinCell(
                        char = pin.getOrNull(i),
                        active = i == pin.length,
                        error = state.error != null,
                    )
                }
            }
            // The one real text field driving the cells above, for IME and
            // accessibility — invisible but present and focused.
            BasicTextField(
                value = pin,
                onValueChange = { input -> pin = input.filter { it.isDigit() }.take(4) },
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0f)
                    .focusRequester(focusRequester),
                textStyle = LocalTextStyle.current,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            state.error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(message, style = PuckText.body.copy(fontWeight = FontWeight.SemiBold), color = scheme.error)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .raised(RoundedCornerShape(30.dp), scheme.primary)
                .clickable(enabled = pin.length == 4 && !state.busy) { vm.submitPin(device, pin) },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.5.dp,
                    color = scheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.pairing), style = PuckText.rowTitle.copy(fontWeight = FontWeight.Bold), color = scheme.onPrimary)
            } else {
                Text(
                    if (forAirPlay) stringResource(R.string.pin_enable_cta) else stringResource(R.string.pair),
                    style = PuckText.rowTitle.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                    color = scheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.cancelPairing() }, enabled = !state.busy) {
            Text(stringResource(R.string.cancel), style = PuckText.rowTitle, color = scheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pin_expires), style = PuckText.caption, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinCell(char: Char?, active: Boolean, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .width(68.dp)
            .height(84.dp)
            .then(
                if (char != null) Modifier.raised(shape, c.raised)
                else Modifier.clip(shape).background(c.well),
            )
            .then(
                when {
                    error -> Modifier.border(2.dp, scheme.error, shape)
                    active -> Modifier.border(2.dp, scheme.primary, shape)
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            char?.toString() ?: if (active) "" else "·",
            style = PuckText.pin,
            color = if (char != null) scheme.onSurface else scheme.outlineVariant,
        )
    }
}

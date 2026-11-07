package dev.danielclements.puck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import dev.danielclements.puck.R
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding

@Composable
fun DeviceListScreen(state: UiState, vm: RemoteViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    if (showAddDialog) {
        AddAddressDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { showAddDialog = false; vm.addDeviceByAddress(it) },
        )
    }

    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp)
            .padding(top = 18.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A small disc logo — the same "puck" shape language as
                    // every control on the remote screen, just static here.
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.app_name_lower), style = PuckText.wordmark, color = scheme.onBackground)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.devices_subtitle), style = PuckText.body, color = scheme.onSurfaceVariant)
            }
            PuckRoundButton(
                onClick = { vm.openSettings() },
                size = 48.dp,
                containerColor = c.raised,
                contentColor = scheme.onSurface,
            ) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings), modifier = Modifier.size(21.dp))
            }
        }

        Spacer(Modifier.height(18.dp))

        state.error?.let { message ->
            ErrorBanner(message) { vm.dismissError() }
            Spacer(Modifier.height(12.dp))
        }

        state.wakeTarget?.let { target ->
            PuckPrimaryButton(
                onClick = { vm.wakeDevice(target) },
                enabled = !state.busy,
                text = stringResource(R.string.wake_and_retry, target.name),
            )
            Spacer(Modifier.height(12.dp))
        }

        if (state.autoConnecting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.found_count, state.devices.size),
                style = PuckText.label,
                color = scheme.onSurfaceVariant,
            )
            if (state.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.listening), style = PuckText.label, color = scheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (state.devices.isEmpty() && !state.scanning) {
            EmptyState(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.devices, key = { it.credentialKey }) { device ->
                    DeviceRow(
                        name = device.name,
                        subtitle = "${device.model ?: "Apple TV"}  ·  ${device.address}",
                        paired = device.credentialKey in state.pairedKeys,
                        enabled = !state.busy,
                        onClick = { vm.select(device) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        PuckSecondaryButton(
            onClick = { vm.scan() },
            enabled = !state.scanning && !state.busy,
        ) {
            if (state.scanning || state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = scheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (state.busy) R.string.connecting else R.string.scanning), style = PuckText.rowTitle)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_again), style = PuckText.rowTitle)
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { showAddDialog = true }, enabled = !state.busy) {
                Text(stringResource(R.string.add_address), style = PuckText.rowTitle)
            }
            TextButton(onClick = { vm.startDemo() }, enabled = !state.busy) {
                Text(stringResource(R.string.try_demo), style = PuckText.rowTitle)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.disclaimer),
            style = PuckText.caption,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeviceRow(
    name: String,
    subtitle: String,
    paired: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (paired) Modifier.raised(shape, c.raised)
                else Modifier.clip(shape).background(c.tray).border(1.5.dp, scheme.outlineVariant, shape),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (paired) scheme.primaryContainer else c.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Tv,
                contentDescription = null,
                tint = if (paired) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = PuckText.rowTitle, color = scheme.onSurface)
            Text(subtitle, style = PuckText.data, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        val chipShape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .height(32.dp)
                .then(
                    if (paired) Modifier.border(1.5.dp, scheme.outlineVariant, chipShape)
                    else Modifier.clip(chipShape).background(scheme.primary),
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (paired) stringResource(R.string.device_paired) else stringResource(R.string.device_pair),
                style = PuckText.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                color = if (paired) scheme.onSurfaceVariant else scheme.onPrimary,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawCircle(scheme.outlineVariant, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    },
            )
            Box(
                Modifier
                    .size(92.dp)
                    .drawBehind {
                        drawCircle(scheme.outlineVariant, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    },
            )
            Box(
                Modifier
                    .size(44.dp)
                    .raised(CircleShape, c.raised),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.no_devices), style = PuckText.deviceTitle, color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.no_devices_hint),
            style = PuckText.body,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onDismiss)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = PuckText.body,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(stringResource(R.string.dismiss), style = PuckText.caption, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** The one primary CTA on a screen — DESIGN_SPEC.md §2's fixed primary role. */
@Composable
private fun PuckPrimaryButton(onClick: () -> Unit, enabled: Boolean, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .raised(RoundedCornerShape(26.dp), scheme.primary)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = PuckText.rowTitle, color = scheme.onPrimary)
    }
}

@Composable
private fun PuckSecondaryButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .raised(RoundedCornerShape(30.dp), scheme.secondaryContainer)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides scheme.onSecondaryContainer,
            content = content,
        )
    }
}

@Composable
private fun AddAddressDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_address_title)) },
        text = {
            Column {
                Text(stringResource(R.string.add_address_desc), style = PuckText.caption)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.add_address_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.add_address_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_address_cancel)) } },
    )
}

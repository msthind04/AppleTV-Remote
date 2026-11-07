package dev.danielclements.puck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atvremote.protocol.discovery.AppleTvDevice

@Composable
fun ManageDevicesScreen(state: UiState, vm: RemoteViewModel) {
    BackHandler { vm.closeManageDevices() }
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    var renaming by remember { mutableStateOf<AppleTvDevice?>(null) }
    var forgetting by remember { mutableStateOf<AppleTvDevice?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        Row(
            modifier = Modifier.height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PuckRoundButton(onClick = { vm.closeManageDevices() }, size = 44.dp, containerColor = c.raised, contentColor = scheme.onSurface) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.manage_devices_title), style = PuckText.screenTitle, color = scheme.onBackground)
        }

        Spacer(Modifier.height(24.dp))

        if (state.managedDevices.isEmpty()) {
            Text(stringResource(R.string.manage_devices_empty), style = PuckText.body, color = scheme.onSurfaceVariant)
        } else {
            Text(
                stringResource(R.string.manage_devices_section, state.managedDevices.size),
                style = PuckText.label,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(c.tray)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.managedDevices.forEach { device ->
                    ManagedDeviceRow(
                        device = device,
                        onRename = { renaming = device },
                        onForget = { forgetting = device },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.manage_devices_footnote),
                style = PuckText.caption,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    renaming?.let { device ->
        RenameDialog(
            device = device,
            onConfirm = { name -> vm.renameDevice(device, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    forgetting?.let { device ->
        ForgetDialog(
            device = device,
            onConfirm = { vm.forgetManagedDevice(device); forgetting = null },
            onDismiss = { forgetting = null },
        )
    }
}

@Composable
private fun ManagedDeviceRow(device: AppleTvDevice, onRename: () -> Unit, onForget: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Puck.colors.card)
            .padding(12.dp, 12.dp, 8.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Tv, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = PuckText.rowTitle, color = scheme.onSurface)
            Text(device.model ?: "Apple TV", style = PuckText.data, color = scheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.cd_rename),
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onRename)
                .padding(12.dp),
        )
        Icon(
            Icons.Default.Delete,
            contentDescription = stringResource(R.string.cd_forget),
            tint = scheme.error,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onForget)
                .padding(12.dp),
        )
    }
}

@Composable
private fun RenameDialog(device: AppleTvDevice, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(device.credentialKey) { mutableStateOf(device.name) }
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    PuckDialog(onDismissRequest = onDismiss) {
        Column {
            Text(stringResource(R.string.rename_device_title, device.name), style = PuckText.dialogTitle, color = scheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.rename_device_hint), style = PuckText.body, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.device_name_hint),
                style = PuckText.label,
                color = scheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.well,
                    unfocusedContainerColor = c.well,
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.primary,
                ),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    containerColor = c.card,
                    contentColor = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    text = stringResource(R.string.rename),
                    onClick = { onConfirm(name) },
                    enabled = name.isNotBlank(),
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    raised = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ForgetDialog(device: AppleTvDevice, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val c = Puck.colors

    PuckDialog(onDismissRequest = onDismiss) {
        Column {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.forget_device_title, device.name), style = PuckText.dialogTitle, color = scheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.forget_device_message), style = PuckText.body, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(
                    text = stringResource(R.string.keep),
                    onClick = onDismiss,
                    containerColor = c.card,
                    contentColor = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    text = stringResource(R.string.forget),
                    onClick = onConfirm,
                    containerColor = scheme.error,
                    contentColor = scheme.onError,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    raised: Boolean = false,
) {
    val shape = RoundedCornerShape(27.dp)
    Row(
        modifier = modifier
            .height(54.dp)
            .then(if (raised) Modifier.raised(shape, containerColor) else Modifier.clip(shape).background(containerColor))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = PuckText.rowTitle.copy(fontWeight = FontWeight.Bold), color = contentColor.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

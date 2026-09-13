package dev.atvremote.app

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The phone's own name, as the Apple TV should list it under Remotes.
 *
 * This is the name the user set in Settings → About phone, the same one the
 * phone advertises over Bluetooth and Wi-Fi Direct. It can be unset on a
 * fresh device, so the model number stands in.
 */
fun Context.ownDeviceName(): String =
    Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL

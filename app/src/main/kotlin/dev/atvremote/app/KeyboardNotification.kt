package dev.atvremote.app

import dev.atvremote.app.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * Prompt to type, shown while the Apple TV has a text field focused.
 *
 * An iPhone offers the keyboard from the lock screen the moment tvOS asks for
 * text, and Android's direct reply is the closer match to that than merely
 * opening the app would be: the text is typed and sent from the shade, without
 * unlocking or switching away from whatever is on screen.
 */
object KeyboardNotification {

    /** Key the system files the typed text under. */
    const val RESULT_KEY = "text"

    private const val CHANNEL_ID = "keyboard"
    private const val NOTIFICATION_ID = 2

    fun show(context: Context, deviceName: String, existing: String?) {
        createChannel(context)

        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, KeyboardReplyReceiver::class.java),
            // Mutable, because the system writes the typed text into it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_keyboard),
            context.getString(R.string.notif_type),
            reply,
        )
            .addRemoteInput(RemoteInput.Builder(RESULT_KEY).setLabel(context.getString(R.string.notif_type_here)).build())
            .setAllowGeneratedReplies(false)
            .build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(context.getString(R.string.notif_type_title, deviceName))
            .setContentText(
                existing?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.notif_type_body)
            )
            .setContentIntent(openApp(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Dismissing it by hand should not fight the focus state on the TV,
            // which is what actually decides whether the prompt belongs there.
            .setOngoing(false)
            .setAutoCancel(false)
            // Reposted on every keystroke that lands, so alerting once is the
            // difference between a prompt and a nuisance.
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()

        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun hide(context: Context) = manager(context).cancel(NOTIFICATION_ID)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_keyboard),
            // High, so it arrives as a banner the way the iPhone prompt does —
            // it is only useful in the moment the TV is waiting for it.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_keyboard_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager(context).createNotificationChannel(channel)
    }
}

/** Receives text typed into the notification's reply field. */
class KeyboardReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KeyboardNotification.RESULT_KEY)
            ?.toString()
            ?: return
        if (text.isNotEmpty()) NotificationBridge.send(RemoteCommand.SendText(text))
    }
}

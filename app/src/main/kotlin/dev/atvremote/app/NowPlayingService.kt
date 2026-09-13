package dev.atvremote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Media notification for whatever the Apple TV is playing.
 *
 * The point of a service is not the notification itself but the process: a
 * foreground service is what keeps the Companion and MRP connections alive
 * once the app leaves the screen, which is exactly when a notification is
 * worth having.
 *
 * The scrubber and the buttons in the system's media controls are driven by
 * the [MediaSession], not by the notification's own actions — those are the
 * fallback for older shades — so both are kept in step.
 */
class NowPlayingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession

    /** Decoded artwork, kept until the bytes change, since decoding is not cheap. */
    private var artwork: Bitmap? = null

    private val nothingPlaying: String get() = getString(R.string.nothing_playing)
    private var artworkBytes = -1

    private var volumeProvider: VolumeProvider? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSession(this, "RemoteForAppleTv").apply {
            setSessionActivity(openApp())
            setCallback(object : MediaSession.Callback() {
                // The TV owns playback state, so play and pause are the same
                // toggle from here; the button drawn is whatever the last
                // reported state implies.
                override fun onPlay() = NotificationBridge.send(RemoteCommand.PlayPause)
                override fun onPause() = NotificationBridge.send(RemoteCommand.PlayPause)
                override fun onRewind() = NotificationBridge.send(RemoteCommand.Skip(-SKIP))
                override fun onFastForward() = NotificationBridge.send(RemoteCommand.Skip(SKIP))
                override fun onSkipToPrevious() = NotificationBridge.send(RemoteCommand.Skip(-SKIP))
                override fun onSkipToNext() = NotificationBridge.send(RemoteCommand.Skip(SKIP))
                override fun onSeekTo(pos: Long) =
                    NotificationBridge.send(RemoteCommand.SeekTo(pos / 1000.0))
            })
            isActive = true
        }

        scope.launch {
            NotificationBridge.snapshot.collectLatest { snapshot ->
                if (snapshot == null) {
                    // Nothing playing, or the remote disconnected.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    apply(snapshot)
                    notificationManager().notify(NOTIFICATION_ID, notification(snapshot))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> NotificationBridge.send(RemoteCommand.PlayPause)
            ACTION_REWIND -> NotificationBridge.send(RemoteCommand.Skip(-SKIP))
            ACTION_FORWARD -> NotificationBridge.send(RemoteCommand.Skip(SKIP))
        }

        // Android gives a started service seconds to show its notification,
        // whether or not a snapshot has arrived yet.
        val snapshot = NotificationBridge.snapshot.value
        startForeground(NOTIFICATION_ID, notification(snapshot))
        snapshot?.let(::apply)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    /** Push the snapshot into the session, which is what the shade reads. */
    private fun apply(snapshot: NowPlayingSnapshot) {
        if (snapshot.artwork?.size != artworkBytes) {
            artworkBytes = snapshot.artwork?.size ?: -1
            artwork = snapshot.artwork?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
            }
        }

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title ?: nothingPlaying)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist ?: snapshot.deviceName)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.album)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
        // A duration only means something alongside a position; without one the
        // system hides the scrubber, which beats drawing a bar that cannot move.
        snapshot.duration?.let {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong())
        }
        session.setMetadata(metadata.build())

        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD
        // Both halves are needed: seeking is relative to the current playhead,
        // so advertising it without one gives the shade a scrubber whose every
        // drag is silently discarded.
        if (snapshot.duration != null && snapshot.position != null) {
            actions = actions or PlaybackState.ACTION_SEEK_TO
        }

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (snapshot.playing) PlaybackState.STATE_PLAYING
                    else PlaybackState.STATE_PAUSED,
                    snapshot.position?.let { (it * 1000).toLong() }
                        ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    // The shade runs the scrubber forward from here itself, so
                    // it animates between the updates the Apple TV sends.
                    if (snapshot.playing) 1f else 0f,
                )
                .build()
        )

        applyVolume(snapshot.volume)
    }

    /**
     * Expose volume as a remote one when the Apple TV can route it, which puts
     * a slider in the media controls. When it cannot, the session falls back to
     * local so the phone's own volume keys behave normally.
     */
    private fun applyVolume(level: Double?) {
        if (level == null) {
            if (volumeProvider != null) {
                volumeProvider = null
                session.setPlaybackToLocal(android.media.AudioAttributes.Builder().build())
            }
            return
        }

        val steps = (level * VOLUME_STEPS).toInt().coerceIn(0, VOLUME_STEPS)
        val existing = volumeProvider
        if (existing != null) {
            existing.currentVolume = steps
            return
        }

        val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, steps) {
            override fun onSetVolumeTo(volume: Int) {
                currentVolume = volume
                NotificationBridge.send(RemoteCommand.SetVolume(volume / VOLUME_STEPS.toDouble()))
            }

            override fun onAdjustVolume(direction: Int) {
                NotificationBridge.send(RemoteCommand.AdjustVolume(direction))
            }
        }
        volumeProvider = provider
        session.setPlaybackToRemote(provider)
    }

    private fun notification(snapshot: NowPlayingSnapshot?): Notification {
        val playing = snapshot?.playing == true
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(snapshot?.title ?: nothingPlaying)
            .setContentText(snapshot?.artist ?: snapshot?.deviceName ?: "")
            .setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Dismissible while paused, pinned while playing, as media
            // notifications conventionally behave.
            .setOngoing(playing)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(action(R.drawable.ic_media_rewind, getString(R.string.skip_back), ACTION_REWIND))
            .addAction(
                action(
                    if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    if (playing) getString(R.string.pause) else getString(R.string.play),
                    ACTION_PLAY_PAUSE,
                )
            )
            .addAction(action(R.drawable.ic_media_forward, getString(R.string.skip_forward), ACTION_FORWARD))

        snapshot?.deviceName?.let { builder.setSubText(it) }
        artwork?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val intent = Intent(this, NowPlayingService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            pending,
        ).build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_nowplaying),
            // Media controls are glanceable, not interruptive.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_nowplaying_desc)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "now_playing_v2"
        private const val NOTIFICATION_ID = 1
        private const val SKIP = 10.0
        private const val VOLUME_STEPS = 20

        private const val ACTION_PLAY_PAUSE = "dev.atvremote.app.PLAY_PAUSE"
        private const val ACTION_REWIND = "dev.atvremote.app.REWIND"
        private const val ACTION_FORWARD = "dev.atvremote.app.FORWARD"
    }
}

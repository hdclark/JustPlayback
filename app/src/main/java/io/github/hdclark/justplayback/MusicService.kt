package io.github.hdclark.justplayback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        const val CHANNEL_ID = "playback"
        const val ACTION_PREVIOUS = "io.github.hdclark.justplayback.PREVIOUS"
        const val ACTION_REWIND = "io.github.hdclark.justplayback.REWIND"
        const val ACTION_PLAY_PAUSE = "io.github.hdclark.justplayback.PLAY_PAUSE"
        const val ACTION_FAST_FORWARD = "io.github.hdclark.justplayback.FAST_FORWARD"
        const val ACTION_NEXT = "io.github.hdclark.justplayback.NEXT"
        const val NOTIFICATION_ID = 1
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null

    var files: List<MusicFile> = emptyList()
        private set
    private var remaining: MutableList<MusicFile> = mutableListOf()
    var current: MusicFile? = null
        private set
    private val history: MutableList<MusicFile> = mutableListOf()

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PREVIOUS -> playPrevious()
                ACTION_REWIND -> seekRelative(-10_000)
                ACTION_PLAY_PAUSE -> pauseResume()
                ACTION_FAST_FORWARD -> seekRelative(10_000)
                ACTION_NEXT -> playNext()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()

        mediaSession = MediaSession(this, "JustPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = pauseResume()
                override fun onPause() = pauseResume()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrevious()
                override fun onSeekTo(pos: Long) {
                    mediaPlayer?.seekTo(pos.toInt())
                }
                override fun onFastForward() = seekRelative(10_000)
                override fun onRewind() = seekRelative(-10_000)
            })
            isActive = true
        }

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JustPlayback::WakeLock"
        ).apply { setReferenceCounted(false) }

        val filter = IntentFilter().apply {
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_REWIND)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_FAST_FORWARD)
            addAction(ACTION_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure startForeground is called quickly to satisfy foreground service requirements
        if (current == null) {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
            startForegroundCompat(notification)
        }
        return START_STICKY
    }

    fun play(file: MusicFile, allFiles: List<MusicFile>) {
        files = allFiles
        current = file
        history.clear()
        remaining = allFiles.filter { it.id != file.id }.toMutableList().also { it.shuffle() }
        startPlayback(file)
    }

    fun playNext() {
        if (remaining.isEmpty()) {
            stopPlayback()
            return
        }
        current?.let { history.add(it) }
        val next = remaining.removeAt(0)
        current = next
        startPlayback(next)
    }

    fun playPrevious() {
        if (history.isEmpty()) {
            mediaPlayer?.seekTo(0)
            return
        }
        current?.let { remaining.add(0, it) }
        val prev = history.removeAt(history.lastIndex)
        current = prev
        startPlayback(prev)
    }

    fun seekRelative(ms: Int) {
        mediaPlayer?.let { mp ->
            val pos = (mp.currentPosition + ms).coerceIn(0, mp.duration)
            mp.seekTo(pos)
        }
    }

    fun pauseResume() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
        } else {
            if (requestAudioFocus()) {
                mp.start()
            }
        }
        updateNotification()
        updatePlaybackState()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    @Suppress("WakelockTimeout")
    private fun startPlayback(file: MusicFile) {
        if (!requestAudioFocus()) return

        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(this@MusicService, Uri.parse(file.uri))
            setOnPreparedListener { mp ->
                mp.start()
                wakeLock?.acquire()
                updatePlaybackState()
                updateNotification()
            }
            setOnErrorListener { _, _, _ ->
                playNext()
                true
            }
            setOnCompletionListener { playNext() }
            prepareAsync()
        }

        updateNotification()
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        current = null
        wakeLock?.release()
        abandonAudioFocus()
        updatePlaybackState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getOrCreateAudioFocusRequest(): AudioFocusRequest {
        focusRequest?.let { return it }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(this)
            .build()
            .also { focusRequest = it }
    }

    private fun requestAudioFocus(): Boolean {
        val request = getOrCreateAudioFocusRequest()
        val result = audioManager.requestAudioFocus(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                mediaPlayer?.pause()
                updateNotification()
                updatePlaybackState()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                mediaPlayer?.pause()
                updateNotification()
                updatePlaybackState()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.3f, 0.3f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f)
                mediaPlayer?.start()
                updateNotification()
                updatePlaybackState()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playback controls"
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun pendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply { `package` = packageName }
        return PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val file = current ?: return
        val isPlaying = mediaPlayer?.isPlaying == true
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play

        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(file.name)
            .setContentText(formatNotificationMeta(file))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainIntent)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                    "Previous",
                    pendingIntent(ACTION_PREVIOUS)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_rew),
                    "-10s",
                    pendingIntent(ACTION_REWIND)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, playPauseIcon),
                    if (isPlaying) "Pause" else "Play",
                    pendingIntent(ACTION_PLAY_PAUSE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_ff),
                    "+10s",
                    pendingIntent(ACTION_FAST_FORWARD)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "Next",
                    pendingIntent(ACTION_NEXT)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .build()

        startForegroundCompat(notification)
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updatePlaybackState() {
        val state = if (mediaPlayer?.isPlaying == true)
            PlaybackState.STATE_PLAYING
        else
            PlaybackState.STATE_PAUSED
        val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val playbackState = PlaybackState.Builder()
            .setState(state, pos, 1.0f)
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_REWIND
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun formatNotificationMeta(file: MusicFile): String {
        val kb = file.size / 1024
        return if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(actionReceiver)
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.release()
        wakeLock?.release()
        abandonAudioFocus()
    }
}

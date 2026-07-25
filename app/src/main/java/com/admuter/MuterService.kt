package com.admuter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Foreground Service that:
 *  1. Registers [SpotifyReceiver] dynamically.
 *  2. Also registers an internal [ActionReceiver] to receive the local broadcasts
 *     fired by [SpotifyReceiver].
 *  3. Uses [AudioManager] to mute STREAM_MUSIC when an ad is detected and
 *     restores the cached volume when normal music resumes.
 */
class MuterService : LifecycleService() {

    companion object {
        private const val TAG = "MuterService"
        private const val CHANNEL_ID = "admuter_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "admuter_prefs"
        private const val KEY_CACHED_VOLUME = "cached_volume"
        private const val KEY_SERVICE_RUNNING = "service_running"

        /** Intent action to start this service. */
        const val ACTION_START = "com.admuter.ACTION_START"
        /** Intent action to stop this service. */
        const val ACTION_STOP = "com.admuter.ACTION_STOP"

        // Keys for crash diagnostics
        private const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"

        /**
         * Query whether the service is currently running via persisted state.
         */
        fun isRunning(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        }

        /**
         * Read the last recorded crash info for diagnostics.
         */
        fun getLastCrashInfo(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_LAST_CRASH, null)
        }

        /**
         * Clear crash diagnostics.
         */
        fun clearCrashInfo(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_LAST_CRASH).remove(KEY_LAST_CRASH_TIME).apply()
        }
    }

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences

    /**
     * The volume level (0..[maxVolume]) that was set by the user before
     * we muted for an ad. Restored when a normal track is detected.
     */
    private var cachedVolume: Int = -1
    private val maxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** Whether we are currently in a muted state for an ad. */
    private var isMutedForAd: Boolean = false

    // ---- Receivers ----

    /** Dynamically registered Spotify broadcast receiver. */
    private val spotifyReceiver = SpotifyReceiver()

    /** Internal receiver for the local broadcasts sent by [SpotifyReceiver]. */
    private val actionReceiver = ActionReceiver()

    // ---- Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        createNotificationChannel()
        restoreCachedVolumeFromPrefs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    val notification = buildNotification(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    registerReceivers()
                    prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()
                    Log.d(TAG, "Service started — receivers registered")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start foreground service: ${t.message}", t)
                    // Record crash for diagnostics (use commit() to ensure it persists)
                    val stackTrace = Log.getStackTraceString(t)
                    val msg = t.message ?: "(no message)"
                    prefs.edit()
                        .putString(KEY_LAST_CRASH, "${t.javaClass.simpleName}: $msg\n$stackTrace")
                        .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                        .commit()
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                try {
                    unregisterReceivers()
                } catch (_: Exception) { }
                restoreVolumeIfMuted()
                prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) { }
                stopSelf()
                Log.d(TAG, "Service stopped — receivers unregistered")
            }
        }
        // Use START_NOT_STICKY to prevent Android from restarting the service
        // after we deliberately stop it. With START_STICKY, calling stopSelf()
        // would cause an infinite restart loop.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        unregisterReceivers()
        restoreVolumeIfMuted()
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
        super.onDestroy()
    }

    // ---- Receiver registration ----

    private fun registerReceivers() {
        // Register Spotify metadata & playback-state broadcasts using
        // ContextCompat for backward-compatible RECEIVER_EXPORTED flag.
        val spotifyFilter = IntentFilter().apply {
            addAction(SpotifyReceiver.ACTION_METADATA_CHANGED)
            addAction(SpotifyReceiver.ACTION_PLAYBACK_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, spotifyReceiver, spotifyFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        // Register our own package-scoped action receiver
        val actionFilter = IntentFilter().apply {
            addAction(SpotifyReceiver.ACTION_AD_DETECTED)
            addAction(SpotifyReceiver.ACTION_MUSIC_DETECTED)
            addAction(SpotifyReceiver.ACTION_NO_METADATA)
        }
        registerReceiver(actionReceiver, actionFilter, RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(spotifyReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered
        }
        try {
            unregisterReceiver(actionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    // ---- Audio control ----

    /**
     * Mutes STREAM_MUSIC by setting volume to 0.
     * Caches the current volume level right before muting.
     */
    @Synchronized
    fun muteForAd() {
        if (isMutedForAd) {
            Log.d(TAG, "Already muted for ad — skipping")
            return
        }

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Only cache if we haven't already or if volume > 0
        if (currentVolume > 0) {
            cachedVolume = currentVolume
            prefs.edit().putInt(KEY_CACHED_VOLUME, cachedVolume).apply()
            Log.d(TAG, "Cached volume=$cachedVolume (max=$maxVolume)")
        } else if (cachedVolume < 0) {
            // If volume is already 0 and we have no cache, use half volume as fallback
            cachedVolume = (maxVolume * 0.5).toInt().coerceAtLeast(1)
            prefs.edit().putInt(KEY_CACHED_VOLUME, cachedVolume).apply()
            Log.d(TAG, "Volume was 0 with no cache — using fallback=$cachedVolume")
        }

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        isMutedForAd = true
        Log.d(TAG, "→ MUTED (cached=$cachedVolume)")

        updateNotification(true)
    }

    /**
     * Restores the cached volume level.
     */
    @Synchronized
    fun restoreVolume() {
        if (!isMutedForAd) {
            Log.d(TAG, "Not muted — skipping restore")
            return
        }

        val volumeToRestore = if (cachedVolume >= 0) {
            cachedVolume
        } else {
            prefs.getInt(KEY_CACHED_VOLUME, -1).let { prefVolume ->
                if (prefVolume >= 0) prefVolume
                else (maxVolume * 0.5).toInt().coerceAtLeast(1)
            }
        }

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volumeToRestore.coerceIn(0, maxVolume),
            0
        )
        isMutedForAd = false
        cachedVolume = -1
        prefs.edit().remove(KEY_CACHED_VOLUME).apply()
        Log.d(TAG, "→ VOLUME RESTORED to $volumeToRestore")

        updateNotification(false)
    }

    private fun restoreVolumeIfMuted() {
        if (isMutedForAd) {
            restoreVolume()
        }
    }

    private fun restoreCachedVolumeFromPrefs() {
        cachedVolume = prefs.getInt(KEY_CACHED_VOLUME, -1)
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(isAdMuted: Boolean): Notification {
        val stopIntent = Intent(this, MuterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (isAdMuted) {
            "Ad Detected — Audio Muted"
        } else {
            "Monitoring Spotify Playback"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(isAdMuted: Boolean) {
        val notification = buildNotification(isAdMuted)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ---- Internal BroadcastReceiver for action commands ----

    /**
     * Receives the local broadcasts from [SpotifyReceiver] and triggers
     * mute/restore on [MuterService].
     */
    inner class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SpotifyReceiver.ACTION_AD_DETECTED -> muteForAd()
                SpotifyReceiver.ACTION_MUSIC_DETECTED -> restoreVolume()
                SpotifyReceiver.ACTION_NO_METADATA -> {
                    // When playback stops entirely, restore volume if muted
                    restoreVolume()
                }
            }
        }
    }
}

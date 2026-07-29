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
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground Service that:
 *  1. Registers [SpotifyReceiver] dynamically (for Spotify's broadcast intents).
 *  2. Registers an internal [ActionReceiver] to receive local broadcasts
 *     from [SpotifyReceiver], [SpotifyNotificationListener], or [MainActivity] (test).
 *  3. Uses [AudioManager] to mute STREAM_MUSIC when an ad is detected and
 *     restores the cached volume when normal music resumes.
 *  4. Automatically dispatches a "skip"/next-track command when an ad is
 *     detected, so the ad is dismissed as well as muted.
 *
 * ## Thread safety
 * All mutable shared state ([isMutedForAd], [cachedVolume], [lastSkipTimestamp])
 * uses [AtomicBoolean] / [AtomicInteger] / [AtomicLong] so that calls from
 * binder threads (BroadcastReceiver.onReceive) and the main thread are safe.
 *
 * ## Clean lifecycle
 *  - [ACTION_STOP] simply calls [stopSelf]; all cleanup (receiver
 *    unregistration, volume restoration, state persistence) happens in
 *    [onDestroy].
 *  - [MainActivity] never speculatively sets the running state —
 *    it reads [isRunning] from the in-memory flag, which is only
 *    updated by the service itself.
 *
 * ## Auto-skip cooldown
 * Skip attempts are rate-limited to one every [SKIP_COOLDOWN_MS]
 * milliseconds to avoid spamming the system when multiple ad-detection
 * sources fire simultaneously.
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

        /** Minimum interval (ms) between consecutive auto-skip attempts. */
        private const val SKIP_COOLDOWN_MS = 3000L

        /**
         * In-memory running state, updated ONLY by the service itself.
         * MainActivity reads this to determine the current state.
         */
        @Volatile
        private var runningState: Boolean = false

        /**
         * Query whether the service is currently running — uses the in-memory
         * state for instant accuracy (no SharedPreferences delay).
         */
        fun isRunning(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
            return runningState
        }

        /**
         * Persist the running state to SharedPreferences and update in-memory flag.
         * Called ONLY by the service itself.
         */
        fun persistRunningState(context: Context, running: Boolean) {
            runningState = running
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
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

    // ---- Thread-safe state ----

    /** Volume level to restore after an ad ends. -1 means uncached. */
    private val cachedVolume = AtomicInteger(-1)

    /** Whether we are currently muted for an ad. */
    private val isMutedForAd = AtomicBoolean(false)

    /** Timestamp (ms) of the last auto-skip attempt, for cooldown enforcement. */
    private val lastSkipTimestamp = AtomicLong(0L)

    private val maxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // ---- Receivers ----

    /** Dynamically registered Spotify broadcast receiver. */
    private val spotifyReceiver = SpotifyReceiver()

    /** Internal receiver for the local broadcasts sent by detection sources. */
    private val actionReceiver = ActionReceiver()

    // ---- Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        createNotificationChannel()
        // Restore cached volume from prefs in case service was killed while muted
        cachedVolume.set(prefs.getInt(KEY_CACHED_VOLUME, -1))
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
                    persistRunningState(this, true)
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
                // Delegate all cleanup to onDestroy — just request tear-down.
                Log.d(TAG, "ACTION_STOP received — calling stopSelf()")
                stopSelf()
            }
        }
        // Use START_NOT_STICKY to prevent Android from restarting the service
        // after we deliberately stop it.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Centralized cleanup: no matter how the service stops
     * (ACTION_STOP, system kill, crash), receivers are unregistered,
     * volume is restored, and state is persisted.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up")
        unregisterReceivers()
        restoreVolumeIfMuted()
        persistRunningState(this, false)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) { }
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
        ContextCompat.registerReceiver(
            this, actionReceiver, actionFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
     * Thread-safe via [AtomicBoolean] / [AtomicInteger].
     *
     * After muting, automatically attempts to skip the ad track by
     * dispatching KEYCODE_MEDIA_NEXT (and a MediaSession-based fallback).
     * The volume **always stays muted** regardless of whether the skip
     * succeeds or fails — volume is only restored by [restoreVolume]
     * when a normal music track is detected.
     */
    fun muteForAd() {
        if (isMutedForAd.getAndSet(true)) {
            Log.d(TAG, "Already muted for ad — skipping")
            return
        }

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Only cache if we haven't already or if volume > 0
        if (currentVolume > 0) {
            cachedVolume.set(currentVolume)
            prefs.edit().putInt(KEY_CACHED_VOLUME, currentVolume).apply()
            Log.d(TAG, "Cached volume=$currentVolume (max=$maxVolume)")
        } else if (cachedVolume.get() < 0) {
            // If volume is already 0 and we have no cache, use half volume as fallback
            val fallback = (maxVolume * 0.5).toInt().coerceAtLeast(1)
            cachedVolume.set(fallback)
            prefs.edit().putInt(KEY_CACHED_VOLUME, fallback).apply()
            Log.d(TAG, "Volume was 0 with no cache — using fallback=$fallback")
        }

        // ---- MUTE FIRST (volume always stays muted) ----
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        Log.d(TAG, "→ MUTED (cached=${cachedVolume.get()})")

        updateNotification(true)

        // ---- THEN attempt auto-skip (mute is independent of skip result) ----
        skipToNextTrack()
    }

    /**
     * Restores the cached volume level.
     * Thread-safe via [AtomicBoolean] / [AtomicInteger].
     */
    fun restoreVolume() {
        if (!isMutedForAd.getAndSet(false)) {
            Log.d(TAG, "Not muted — skipping restore")
            return
        }

        val volumeToRestore = cachedVolume.getAndSet(-1).let { vol ->
            if (vol >= 0) vol
            else {
                prefs.getInt(KEY_CACHED_VOLUME, -1).let { prefVolume ->
                    if (prefVolume >= 0) {
                        cachedVolume.compareAndSet(-1, prefVolume)
                        prefVolume
                    } else (maxVolume * 0.5).toInt().coerceAtLeast(1)
                }
            }
        }

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volumeToRestore.coerceIn(0, maxVolume),
            0
        )
        prefs.edit().remove(KEY_CACHED_VOLUME).apply()
        Log.d(TAG, "→ VOLUME RESTORED to $volumeToRestore")

        updateNotification(false)
    }

    private fun restoreVolumeIfMuted() {
        if (isMutedForAd.get()) {
            restoreVolume()
        }
    }

    // ---- Auto-skip (ad dismissal) ----

    /**
     * Attempts to skip the current (ad) track using two methods:
     *
     *  1. **KEYCODE_MEDIA_NEXT** — system-level media key event that
     *     any media app (including Spotify) responds to.
     *  2. **MediaSessionManager** — programmatic [MediaController.transportControls.skipToNext]
     *     targeted specifically at Spotify's session (more precise).
     *
     * Both methods are attempted. The skip is rate-limited by [SKIP_COOLDOWN_MS]
     * to avoid flooding the system with skip requests.
     *
     * **Important:** This method does NOT affect volume — the mute state
     * is managed independently by [muteForAd] / [restoreVolume].
     */
    private fun skipToNextTrack() {
        // ---- Cooldown check ----
        val now = System.currentTimeMillis()
        val lastSkip = lastSkipTimestamp.get()
        if (now - lastSkip < SKIP_COOLDOWN_MS) {
            Log.d(TAG, "Skip cooldown active — ${SKIP_COOLDOWN_MS - (now - lastSkip)}ms remaining")
            return
        }
        lastSkipTimestamp.set(now)

        // ---- Method 1: KEYCODE_MEDIA_NEXT via AudioManager ----
        try {
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
            )
            Log.d(TAG, "→ Dispatched KEYCODE_MEDIA_NEXT")
            DebugEventLog.add("[MuterService] KEYCODE_MEDIA_NEXT dispatched")
        } catch (e: Exception) {
            Log.d(TAG, "KEYCODE_MEDIA_NEXT failed: ${e.message}")
        }

        // ---- Method 2: MediaSessionManager skipToNext() ----
        try {
            val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers: List<MediaController> = sessionManager.getActiveSessions(null)
            for (controller in controllers) {
                if (controller.packageName == "com.spotify.music") {
                    controller.transportControls.skipToNext()
                    Log.d(TAG, "→ Called skipToNext() on Spotify MediaController")
                    DebugEventLog.add("[MuterService] skipToNext() via MediaSessionManager")
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaSession skipToNext() failed: ${e.message}")
        }
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
     * Receives the local broadcasts from [SpotifyReceiver],
     * [SpotifyNotificationListener], or [MainActivity]'s test button
     * and triggers mute/restore on [MuterService].
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

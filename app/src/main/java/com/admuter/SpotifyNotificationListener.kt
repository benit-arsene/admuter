package com.admuter

import android.app.Notification
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Single consolidated NotificationListenerService that monitors Spotify's
 * notifications to detect advertisements and normal music playback.
 *
 * This replaces the previous approach of having two separate listeners
 * (SpotifyNotificationListener + AdNotificationListener) which caused
 * duplicate/conflicting broadcasts.
 *
 * ## Reliability advantages
 *  - Does NOT require the user to enable "Device Broadcast Status" inside Spotify.
 *  - Spotify always posts a notification when playing (required by Android).
 *  - Works on all Android versions including Android 14+.
 *
 * ## False-positive protection
 * Only processes notifications with [Notification.CATEGORY_TRANSPORT] or media
 * metadata present, ignoring download notifications, system alerts, etc.
 *
 * ## Auto-skip
 * When an ad is detected, this listener attempts to skip the ad
 * directly via the notification's MediaSession.Token (if available),
 * in addition to sending the AD_DETECTED broadcast which triggers
 * [MuterService.muteForAd] (which also attempts a system-level skip).
 *
 * ## How to enable
 * The user must grant notification access:
 *   Settings → Apps → Special Access → Notification Access → Enable AdMuter
 *
 * NOTE: Google Play Protect may block installation because of this service.
 * If that happens, temporarily disable Play Protect during installation:
 *   Settings → Security → Play Protect → toggle off → install → re-enable
 */
class SpotifyNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SpotifyNLS"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        /** Minimum duration (ms) between consecutive broadcasts to avoid duplicates. */
        private const val DEBOUNCE_MS = 500L

        /** Minimum interval (ms) between consecutive skip attempts. */
        private const val SKIP_COOLDOWN_MS = 3000L
    }

    // ---- Thread-safe state for debounce ----
    @Volatile
    private var lastAction: String? = null
    @Volatile
    private var lastProcessTime: Long = 0L

    // ---- Cooldown for MediaSession-based skip ----
    @Volatile
    private var lastSkipTime: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected — processing existing notifications")
        DebugEventLog.add("[SpotifyNotificationListener] Connected")
        for (sbn in activeNotifications) {
            processSpotifyNotification(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return

        // ---- False-positive guard: skip non-media notifications ----
        if (!isMediaNotification(sbn.notification)) {
            Log.d(TAG, "Skipping non-media notification (category=${sbn.notification.category})")
            return
        }

        processSpotifyNotification(sbn)
    }

    /**
     * Returns true only if the notification is a media-playback notification:
     *   - Has [Notification.CATEGORY_TRANSPORT], OR
     *   - Contains media-related extras (title / info text)
     * This filters out download-complete notifications, friend-activity,
     * playlist-update alerts, and other non-playback notifications from Spotify.
     */
    private fun isMediaNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras ?: return false
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val info = extras.getString(Notification.EXTRA_INFO_TEXT) ?: ""
        return title.isNotEmpty() || info.isNotEmpty()
    }

    private fun processSpotifyNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val info = extras.getString(Notification.EXTRA_INFO_TEXT) ?: ""

        Log.d(TAG, "Spotify notification — title=\"$title\", info=\"$info\"")
        DebugEventLog.add("[SpotifyNotificationListener] title=\"$title\" info=\"$info\"")

        val isAd = isAdNotification(notification, title, info)

        val intentAction = if (isAd) {
            Log.d(TAG, "→ Ad detected from notification")
            DebugEventLog.add("[SpotifyNotificationListener] → AD detected")

            // ---- Attempt to skip via the notification's MediaSession.Token ----
            skipViaNotificationMediaSession(notification)

            SpotifyReceiver.ACTION_AD_DETECTED
        } else if (title.isNotEmpty()) {
            Log.d(TAG, "→ Music track detected from notification")
            DebugEventLog.add("[SpotifyNotificationListener] → Music detected")
            SpotifyReceiver.ACTION_MUSIC_DETECTED
        } else {
            Log.d(TAG, "→ Empty notification, skipping")
            return
        }

        // Debounce guard: skip if same action was sent within DEBOUNCE_MS
        val now = System.currentTimeMillis()
        if (intentAction == lastAction && (now - lastProcessTime) < DEBOUNCE_MS) {
            Log.d(TAG, "→ Debounced duplicate $intentAction")
            return
        }
        lastAction = intentAction
        lastProcessTime = now

        val localIntent = Intent().apply {
            `package` = packageName
            action = intentAction
        }
        sendBroadcast(localIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return

        Log.d(TAG, "→ Spotify notification removed (playback stopped), sending NO_METADATA")
        DebugEventLog.add("[SpotifyNotificationListener] Notification removed — sending NO_METADATA")

        // Debounce NO_METADATA
        val now = System.currentTimeMillis()
        if (SpotifyReceiver.ACTION_NO_METADATA == lastAction && (now - lastProcessTime) < DEBOUNCE_MS) {
            Log.d(TAG, "→ Debounced duplicate NO_METADATA")
            return
        }
        lastAction = SpotifyReceiver.ACTION_NO_METADATA
        lastProcessTime = now

        val localIntent = Intent().apply {
            `package` = packageName
            action = SpotifyReceiver.ACTION_NO_METADATA
        }
        sendBroadcast(localIntent)
    }

    // ---- Auto-skip via MediaSession.Token ----

    /**
     * Attempts to skip the ad track by extracting [MediaSession.Token]
     * from the notification extras and calling [MediaController.transportControls.skipToNext].
     *
     * This is more direct than the system-level KEYCODE_MEDIA_NEXT approach
     * because it targets Spotify's specific media session.
     *
     * Rate-limited by [SKIP_COOLDOWN_MS] to prevent spamming.
     * Volume is unaffected by this method — mute is handled by [MuterService].
     */
    private fun skipViaNotificationMediaSession(notification: Notification) {
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < SKIP_COOLDOWN_MS) {
            Log.d(TAG, "Skip cooldown active — ${SKIP_COOLDOWN_MS - (now - lastSkipTime)}ms remaining")
            return
        }

        val extras = notification.extras ?: return
        val rawToken: Any? = extras.get(Notification.EXTRA_MEDIA_SESSION)
        if (rawToken !is MediaSession.Token) {
            Log.d(TAG, "No MediaSession.Token in notification — cannot skip via session")
            return
        }

        try {
            val controller = MediaController(this, rawToken)
            controller.transportControls.skipToNext()
            lastSkipTime = now
            Log.d(TAG, "→ skipToNext() via notification MediaSession.Token")
            DebugEventLog.add("[SpotifyNotificationListener] skipToNext() via MediaSession.Token")
        } catch (e: Exception) {
            Log.d(TAG, "MediaSession skip failed: ${e.message}")
        }
    }

    private fun isAdNotification(notification: Notification, title: String, info: String): Boolean {
        // ---- Exact matches (most common ad patterns) ----
        if (title.equals("Advertisement", ignoreCase = true)) return true
        if (info.equals("Advertisement", ignoreCase = true)) return true
        if (title.equals("Ad", ignoreCase = true)) return true
        if (info.equals("Ad", ignoreCase = true)) return true

        // ---- Partial content matches ----
        if (info.contains(Regex("\\badvertisement\\b", RegexOption.IGNORE_CASE)) ||
            info.contains(Regex("\\bsponsored\\b", RegexOption.IGNORE_CASE))
        ) return true

        // ---- Title is just "Spotify" with no artist → likely an ad ----
        if (title.equals("Spotify", ignoreCase = true)) {
            val extras = notification.extras
            val artist = extras?.getString(Notification.EXTRA_SUB_TEXT, "") ?: ""
            if (artist.isBlank()) return true
        }

        return false
    }
}

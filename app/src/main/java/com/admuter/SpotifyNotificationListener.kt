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

    /**
     * Whether we suspect an ad is playing.
     * Set to [AdState.SUSPICIOUS] when "Spotify is trying to play…" is seen,
     * and cleared when a real music track notification is received.
     *
     * While [SUSPICIOUS], notification-removed events do NOT send
     * [SpotifyReceiver.ACTION_NO_METADATA] because the ad might still
     * be playing after the notification disappears.
     */
    @Volatile
    private var adState: AdState = AdState.IDLE
    @Volatile
    private var adSuspicionStartTime: Long = 0L

    private enum class AdState { IDLE, SUSPICIOUS }

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
        val artist = extras.getString(Notification.EXTRA_SUB_TEXT, "") ?: ""

        Log.d(TAG, "Spotify notification — title=\"$title\", info=\"$info\", artist=\"$artist\"")
        DebugEventLog.add("[SpotifyNotificationListener] title=\"$title\" info=\"$info\" artist=\"$artist\"")

        // ---- Determine what we're seeing ----
        val isAd = isAdNotification(notification, title, info)
        val isLoading = isSpotifyLoading(title, info, artist)

        val now = System.currentTimeMillis()

        if (isAd) {
            // ----- AD DETECTED -----
            adState = AdState.IDLE  // confirmed ad, no need for suspicion
            Log.d(TAG, "→ Ad detected from notification")
            DebugEventLog.add("[SpotifyNotificationListener] → AD detected")
            skipViaNotificationMediaSession(notification)
            sendAction(SpotifyReceiver.ACTION_AD_DETECTED, now)
            return
        }

        if (isLoading) {
            // ----- "Spotify is trying to play…" — possible ad loading -----
            adState = AdState.SUSPICIOUS
            adSuspicionStartTime = now
            Log.d(TAG, "→ Spotify loading — treating as potential ad, muting")
            DebugEventLog.add("[SpotifyNotificationListener] → Spotify loading → muting (SUSPICIOUS)")
            sendAction(SpotifyReceiver.ACTION_AD_DETECTED, now)
            return
        }

        // ----- REAL MUSIC TRACK -----
        if (title.isNotEmpty()) {
            // If we were suspicious, clear it — the real track has started
            if (adState == AdState.SUSPICIOUS) {
                Log.d(TAG, "→ Suspicion cleared — real track now playing")
                DebugEventLog.add("[SpotifyNotificationListener] Suspicion cleared → real track playing")
            }
            adState = AdState.IDLE
            Log.d(TAG, "→ Music track detected from notification")
            DebugEventLog.add("[SpotifyNotificationListener] → Music detected")
            sendAction(SpotifyReceiver.ACTION_MUSIC_DETECTED, now)
            return
        }

        // ----- EMPTY NOTIFICATION -----
        // If empty but we're in suspicious state, it's likely the ad still playing
        if (adState == AdState.SUSPICIOUS) {
            Log.d(TAG, "→ Empty notification while suspicious — ad likely still playing, no action")
            DebugEventLog.add("[SpotifyNotificationListener] Empty while suspicious — keeping muted")
            // Periodically refresh the AD_DETECTED to extend the grace period in MuterService
            if (now - adSuspicionStartTime > 1000L) {
                sendAction(SpotifyReceiver.ACTION_AD_DETECTED, now)
                adSuspicionStartTime = now
            }
            return
        }

        // Truly empty with no suspicion — skip
        Log.d(TAG, "→ Empty notification, ignoring")
    }

    /**
     * Helper: send a local broadcast action with debounce.
     */
    private fun sendAction(action: String, now: Long) {
        if (action == lastAction && (now - lastProcessTime) < DEBOUNCE_MS) {
            Log.d(TAG, "→ Debounced duplicate $action")
            return
        }
        lastAction = action
        lastProcessTime = now
        val localIntent = Intent().apply {
            `package` = packageName
            this.action = action
        }
        sendBroadcast(localIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return

        val now = System.currentTimeMillis()

        // ---- CRITICAL: If we're suspicious (ad was loading), DON'T send NO_METADATA ----
        // The notification often disappears during an ad, but the ad keeps playing.
        // Sending NO_METADATA would cause MuterService to restore volume, making the ad audible.
        if (adState == AdState.SUSPICIOUS) {
            Log.d(TAG, "→ Notification removed while SUSPICIOUS — suppressing NO_METADATA (ad likely still playing)")
            DebugEventLog.add("[SpotifyNotificationListener] Notification removed while SUSPICIOUS → NO_METADATA suppressed")
            // Stay suspicious — the ad might still be playing without a notification
            return
        }

        Log.d(TAG, "→ Spotify notification removed (playback stopped), sending NO_METADATA")
        DebugEventLog.add("[SpotifyNotificationListener] Notification removed — sending NO_METADATA")
        sendAction(SpotifyReceiver.ACTION_NO_METADATA, now)
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

    /**
     * Returns true when the notification indicates Spotify is loading/buffering
     * content that hasn't resolved to a specific track yet — often the first
     * sign of an ad loading.
     *
     * Known Spotify ad-loading pattern:
     *   title="Spotify is trying to play…" info="" artist=""  → ad loading
     *
     * This is intentionally separate from [isAdNotification] because it's a
     * *suspicion*, not a confirmed ad. The caller uses it to enter
     * [AdState.SUSPICIOUS] and mute proactively, then clears the suspicion
     * when a real track notification arrives.
     */
    private fun isSpotifyLoading(title: String, info: String, artist: String): Boolean {
        // "Spotify is trying to play…" with no identifiable artist = ad loading
        return title.contains("trying to play", ignoreCase = true) && artist.isBlank()
    }
}

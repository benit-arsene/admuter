package com.admuter

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NotificationListenerService that detects Spotify ads by reading
 * [MediaMetadata] directly from Spotify's active [MediaSession].
 *
 * ## Why MediaSession instead of notification extras?
 * During ad playback, Spotify strips standard notification extras
 * (EXTRA_TITLE, EXTRA_INFO_TEXT, etc.) and suppresses BroadcastReceiver
 * intents. However, the notification itself is still posted (Android
 * requires it for media playback), and it still carries a
 * [MediaSession.Token] in its extras. Using this token, we create a
 * [MediaController] and read the actual [MediaMetadata] fields
 * (title, artist, media ID, duration) directly from Spotify's session.
 *
 * ## Detection path
 *  1. Notification arrives (may have blank extras during ads)
 *  2. **Primary:** Extract [MediaSession.Token] from notification → create
 *     [MediaController] → read [MediaMetadata] → classify with enhanced rules
 *  3. **Fallback:** If token is unavailable, use [MediaSessionManager] to find
 *     Spotify's active session
 *  4. **Fallback:** If both MediaSession approaches fail, use notification
 *     extras with the original [isAdNotification] rules
 *
 * ## Enhanced ad classification
 *  - Media ID starts with "spotify:ad:" or "spotify:advertisement:"
 *  - Blank/empty title while [PlaybackState.STATE_PLAYING]
 *  - Title/Artist contains "Advertisement", exact "Ad", or "Spotify"
 *  - Duration ≤ 30 seconds with non-standard media ID (not "spotify:track:" or
 *    "spotify:episode:")
 *
 * ## Muting & skipping
 * When an ad is detected, this listener sends [SpotifyReceiver.ACTION_AD_DETECTED]
 * via local broadcast, which [MuterService.ActionReceiver] picks up and handles
 * (mute + skip). It also attempts to skip directly via [MediaController.transportControls.skipToNext]
 * for immediate ad dismissal.
 *
 * ## Notification removed suppression
 * During ad transitions, the notification may be removed while the ad is still
 * playing. MuterService handles this via [MuterService.NO_METADATA_GRACE_PERIOD_MS]
 * — it suppresses volume restore for 3s after any ad detection.
 *
 * ## How to enable
 * User must grant notification access:
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

    // ---- Thread-safe state ----

    @Volatile
    private var lastAction: String? = null
    @Volatile
    private var lastProcessTime: Long = 0L
    @Volatile
    private var lastSkipTime: Long = 0L

    /** Debounce: skip re-processing the same media ID within [DEBOUNCE_MS]. */
    @Volatile
    private var lastMediaId: String? = null
    @Volatile
    private var lastMediaIdTime: Long = 0L

    /** Active-sessions ComponentName used for [MediaSessionManager.getActiveSessions]. */
    private val myComponentName: ComponentName by lazy {
        ComponentName(this, javaClass)
    }

    // ---- Lifecycle ----

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        DebugEventLog.add("[SpotifyNLS] Connected")
        // Process any existing notifications (handles service restart mid-ad)
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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return

        Log.d(TAG, "Spotify notification removed")
        DebugEventLog.add("[SpotifyNLS] Notification removed — sending NO_METADATA")
        sendAction(SpotifyReceiver.ACTION_NO_METADATA, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------
    //  Detection pipeline
    // ---------------------------------------------------------------

    /**
     * Returns true only if the notification is a media-playback notification:
     *   - Has [Notification.CATEGORY_TRANSPORT], OR
     *   - Contains title / info text, OR
     *   - Contains a [MediaSession.Token] (extras may be blank but token still present)
     *
     * This filters out download-complete notifications, friend-activity,
     * playlist-update alerts, and other non-playback notifications from Spotify.
     */
    private fun isMediaNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras ?: return false
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val info = extras.getString(Notification.EXTRA_INFO_TEXT) ?: ""
        if (title.isNotEmpty() || info.isNotEmpty()) return true
        // Even with blank extras, a MediaSession.Token means it's a media notification
        if (extras.get(Notification.EXTRA_MEDIA_SESSION) is MediaSession.Token) return true
        return false
    }

    /**
     * Main processing pipeline:
     *  1. Log raw notification extras (often blank during ads)
     *  2. **Primary:** Try MediaSession metadata detection
     *  3. **Fallback:** Try notification extras with original rules
     */
    private fun processSpotifyNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras

        // Always log raw notification data for debugging
        val rawTitle = extras?.getString(Notification.EXTRA_TITLE) ?: ""
        val rawInfo = extras?.getString(Notification.EXTRA_INFO_TEXT) ?: ""
        Log.d(TAG, "Raw notification — title=\"$rawTitle\" info=\"$rawInfo\"")
        DebugEventLog.add("[SpotifyNLS] Raw — title=\"$rawTitle\" info=\"$rawInfo\"")

        // ---- PRIMARY: MediaSession-based detection ----
        // This works even when Spotify strips notification extras during ads
        // because the MediaSession.Token is still present in the notification.
        if (detectFromMediaSession(notification)) {
            Log.d(TAG, "MediaSession detection succeeded")
            return
        }

        // ---- FALLBACK: notification extras ----
        // Use when MediaSession.Token is unavailable or metadata is empty.
        if (extras == null) {
            Log.d(TAG, "No extras available — cannot detect")
            return
        }

        val title = rawTitle
        val info = rawInfo
        val artist = extras.getString(Notification.EXTRA_SUB_TEXT, "") ?: ""

        Log.d(TAG, "MediaSession unavailable — fallback to extras: title=\"$title\" info=\"$info\" artist=\"$artist\"")
        DebugEventLog.add("[SpotifyNLS] Fallback extras — title=\"$title\" info=\"$info\" artist=\"$artist\"")

        val now = System.currentTimeMillis()
        val isAd = isAdNotification(notification, title, info)

        if (isAd) {
            Log.d(TAG, "→ AD detected via notification extras")
            DebugEventLog.add("[SpotifyNLS] → AD detected via extras")
            skipViaNotificationMediaSession(notification)
            sendAction(SpotifyReceiver.ACTION_AD_DETECTED, now)
        } else if (title.isNotEmpty()) {
            Log.d(TAG, "→ Music detected via notification extras")
            DebugEventLog.add("[SpotifyNLS] → Music detected via extras")
            sendAction(SpotifyReceiver.ACTION_MUSIC_DETECTED, now)
        } else {
            Log.d(TAG, "→ Empty extras — cannot classify, ignoring")
        }
    }

    // ---------------------------------------------------------------
    //  MediaSession metadata detection (PRIMARY)
    // ---------------------------------------------------------------

    /**
     * Attempts to read [MediaMetadata] + [PlaybackState] from Spotify's
     * media session and classify the current track.
     *
     * Returns `true` if metadata was successfully read and classified.
     * Returns `false` if no metadata could be obtained.
     */
    private fun detectFromMediaSession(notification: Notification): Boolean {
        val data = readMediaSessionData(notification) ?: return false
        classifyAndSend(data.metadata, data.playbackState)
        return true
    }

    /**
     * Holds [MediaMetadata] and optional [PlaybackState] read from a single
     * [MediaController], so we only create one controller for both reads.
     */
    private class MediaSessionData(
        val metadata: MediaMetadata,
        val playbackState: PlaybackState?
    )

    /**
     * Reads [MediaMetadata] + [PlaybackState] from Spotify's media session.
     *
     *  1. **MediaSession.Token** from notification extras (preferred).
     *  2. **MediaSessionManager.getActiveSessions()** (fallback — uses
     *     [myComponentName] for forward-compat on API 34+).
     *
     * Returns [MediaSessionData] or null if neither method yields valid metadata.
     */
    private fun readMediaSessionData(notification: Notification): MediaSessionData? {
        // ---- Method 1: MediaSession.Token from notification extras ----
        val token = notification.extras?.get(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        if (token != null) {
            try {
                val controller = MediaController(this, token)
                val metadata = controller.metadata
                if (metadata != null && hasRelevantMetadata(metadata)) {
                    Log.d(TAG, "Got metadata directly from notification MediaSession.Token")
                    DebugEventLog.add("[SpotifyNLS] Metadata from notification MediaSession.Token")
                    return MediaSessionData(metadata, controller.playbackState)
                }
            } catch (e: Exception) {
                Log.d(TAG, "MediaSession.Token → MediaController failed: ${e.message}")
            }
        }

        // ---- Method 2: MediaSessionManager (find Spotify's active session) ----
        try {
            val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val activeSessions: List<MediaController> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    sessionManager.getActiveSessions(myComponentName)
                } else {
                    @Suppress("DEPRECATION")
                    sessionManager.getActiveSessions(null)
                }
            for (controller in activeSessions) {
                if (controller.packageName == SPOTIFY_PACKAGE) {
                    val metadata = controller.metadata
                    if (metadata != null && hasRelevantMetadata(metadata)) {
                        Log.d(TAG, "Got metadata via MediaSessionManager")
                        DebugEventLog.add("[SpotifyNLS] Metadata via MediaSessionManager")
                        return MediaSessionData(metadata, controller.playbackState)
                    }
                }
            }
            Log.d(TAG, "Spotify session not found via MediaSessionManager")
        } catch (e: SecurityException) {
            Log.d(TAG, "MediaSessionManager needs notification listener permission: ${e.message}")
            DebugEventLog.add("[SpotifyNLS] MediaSessionManager blocked — need Notification Access permission")
        } catch (e: Exception) {
            Log.d(TAG, "MediaSessionManager error: ${e.message}")
        }

        return null
    }

    /**
     * Returns true if the [MediaMetadata] has at least one relevant field
     * (media ID, title, or artist) non-blank.
     */
    private fun hasRelevantMetadata(metadata: MediaMetadata): Boolean {
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return !id.isNullOrEmpty() || !title.isNullOrEmpty() || !artist.isNullOrEmpty()
    }

    /**
     * Classifies the track using [classifyAsAd] with enhanced rules and
     * sends the corresponding broadcast ([ACTION_AD_DETECTED] or
     * [ACTION_MUSIC_DETECTED]).
     *
     * Includes media-ID-based debounce to prevent rapid re-processing.
     */
    private fun classifyAndSend(metadata: MediaMetadata, playbackState: PlaybackState?) {
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val logLine = "MediaSession — id=$id title=\"$title\" artist=\"$artist\" duration=${duration}ms"
        Log.d(TAG, logLine)
        DebugEventLog.add("[SpotifyNLS] $logLine")

        // Debounce: skip if same media ID within debounce window
        val now = System.currentTimeMillis()
        if (id.isNotEmpty() && id == lastMediaId && (now - lastMediaIdTime) < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced duplicate media ID: $id")
            return
        }
        lastMediaId = id
        lastMediaIdTime = now

        val isAd = classifyAsAd(id, title, artist, duration, playbackState)

        if (isAd) {
            Log.d(TAG, "→ AD detected via MediaSession metadata")
            DebugEventLog.add("[SpotifyNLS] → AD detected via MediaSession")

            // Attempt to skip directly via MediaSession
            skipToNextViaMediaSession()

            sendAction(SpotifyReceiver.ACTION_AD_DETECTED, now)
        } else {
            Log.d(TAG, "→ Music detected via MediaSession metadata")
            DebugEventLog.add("[SpotifyNLS] → Music detected via MediaSession")
            sendAction(SpotifyReceiver.ACTION_MUSIC_DETECTED, now)
        }
    }

    // ---------------------------------------------------------------
    //  Enhanced ad classification rules
    // ---------------------------------------------------------------

    /**
     * Enhanced ad classification using [MediaMetadata] fields.
     *
     * Returns `true` (ad) if **any** of the following rules match:
     *
     *  1. **Media ID** starts with `"spotify:ad:"` or `"spotify:advertisement:"`.
     *  2. **Blank title** while [PlaybackState.STATE_PLAYING] (music tracks
     *     always have a title; ads often have none).
     *  3. **Title/Artist** contains "Advertisement", exact "Ad", or "Spotify"
     *     (case-insensitive). "Ad" is checked with word boundaries to avoid
     *     matching "Mad", "Bad", "Radio", etc.
     *  4. **Short duration** (≤ 30 seconds) when media ID is NOT a standard
     *     track or episode format (e.g. not `"spotify:track:..."`). Standard
     *     music tracks are almost never < 30s.
     */
    private fun classifyAsAd(
        id: String,
        title: String,
        artist: String,
        duration: Long,
        playbackState: PlaybackState?
    ): Boolean {
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

        // ---- Rule 1: Media ID indicates an ad ----
        if (id.startsWith("spotify:ad:") || id.startsWith("spotify:advertisement:")) {
            DebugEventLog.add("[SpotifyNLS] Rule 1 matched: ad media ID prefix")
            return true
        }

        // ---- Rule 2: Blank/empty title while playing ----
        // Normal music tracks always have a title; ads often don't.
        if (title.isBlank() && isPlaying) {
            DebugEventLog.add("[SpotifyNLS] Rule 2 matched: blank title while playing")
            return true
        }

        // ---- Rule 3: Title/Artist contains ad keywords ----
        val lowerTitle = title.lowercase()
        val lowerArtist = artist.lowercase()

        if (lowerTitle.contains("advertisement") || lowerArtist.contains("advertisement")) {
            DebugEventLog.add("[SpotifyNLS] Rule 3a matched: contains 'advertisement'")
            return true
        }

        // Exact match for "Ad" (not "mad", "bad", "radio", etc.)
        if (title.equals("Ad", ignoreCase = true) || artist.equals("Ad", ignoreCase = true)) {
            DebugEventLog.add("[SpotifyNLS] Rule 3b matched: exact 'Ad'")
            return true
        }

        // "Spotify" as the sole title/artist identifier
        if (title.equals("Spotify", ignoreCase = true) || artist.equals("Spotify", ignoreCase = true)) {
            DebugEventLog.add("[SpotifyNLS] Rule 3c matched: contains 'Spotify'")
            return true
        }

        // ---- Rule 4: Short duration with non-standard media ID ----
        // Music tracks are almost always > 30 seconds. If the duration is
        // ≤ 30s and the ID isn't a standard track/episode, it's likely an ad.
        if (duration in 1..30_000) {
            if (!id.startsWith("spotify:track:") && !id.startsWith("spotify:episode:")) {
                DebugEventLog.add("[SpotifyNLS] Rule 4 matched: short duration (${duration}ms) + non-track ID")
                return true
            }
        }

        return false
    }

    // ---------------------------------------------------------------
    //  Auto-skip
    // ---------------------------------------------------------------

    /**
     * Attempts to skip the current ad track by calling
     * [MediaController.transportControls.skipToNext] on Spotify's
     * active media session via [MediaSessionManager].
     *
     * Rate-limited by [SKIP_COOLDOWN_MS] to prevent spamming.
     */
    private fun skipToNextViaMediaSession() {
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < SKIP_COOLDOWN_MS) {
            Log.d(TAG, "Skip cooldown active — ${SKIP_COOLDOWN_MS - (now - lastSkipTime)}ms remaining")
            return
        }

        try {
            val sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val activeSessions: List<MediaController> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    sessionManager.getActiveSessions(myComponentName)
                } else {
                    @Suppress("DEPRECATION")
                    sessionManager.getActiveSessions(null)
                }
            for (controller in activeSessions) {
                if (controller.packageName == SPOTIFY_PACKAGE) {
                    controller.transportControls.skipToNext()
                    lastSkipTime = now
                    Log.d(TAG, "→ skipToNext() via MediaSessionManager")
                    DebugEventLog.add("[SpotifyNLS] skipToNext() via MediaSessionManager")
                    return
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaSession skipToNext() failed: ${e.message}")
        }
    }

    /**
     * Alternate skip method using the notification's [MediaSession.Token]
     * directly. Rate-limited by [SKIP_COOLDOWN_MS].
     *
     * Used as a secondary skip path in the notification-extras fallback.
     */
    private fun skipViaNotificationMediaSession(notification: Notification) {
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < SKIP_COOLDOWN_MS) return

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
            DebugEventLog.add("[SpotifyNLS] skipToNext() via notification token")
        } catch (e: Exception) {
            Log.d(TAG, "MediaSession skip failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    //  Broadcast helper
    // ---------------------------------------------------------------

    /**
     * Sends a local broadcast with the given action, debounced by [DEBOUNCE_MS]
     * to prevent duplicate rapid-fire broadcasts from multiple detection paths.
     */
    private fun sendAction(action: String, now: Long) {
        if (action == lastAction && (now - lastProcessTime) < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced duplicate $action")
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

    // ---------------------------------------------------------------
    //  Fallback: notification extras ad detection
    // ---------------------------------------------------------------

    /**
     * Original notification-extras-based ad detection used as a fallback
     * when [MediaMetadata] is unavailable.
     */
    private fun isAdNotification(notification: Notification, title: String, info: String): Boolean {
        if (title.equals("Advertisement", ignoreCase = true)) return true
        if (info.equals("Advertisement", ignoreCase = true)) return true
        if (title.equals("Ad", ignoreCase = true)) return true
        if (info.equals("Ad", ignoreCase = true)) return true
        if (info.contains(Regex("\\badvertisement\\b", RegexOption.IGNORE_CASE)) ||
            info.contains(Regex("\\bsponsored\\b", RegexOption.IGNORE_CASE))
        ) return true
        if (title.equals("Spotify", ignoreCase = true)) {
            val extras = notification.extras
            val artist = extras?.getString(Notification.EXTRA_SUB_TEXT, "") ?: ""
            if (artist.isBlank()) return true
        }
        return false
    }
}

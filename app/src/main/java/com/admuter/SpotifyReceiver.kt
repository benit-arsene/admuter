package com.admuter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for Spotify's broadcast intents and classifies
 * each event as either an advertisement or a standard music track.
 *
 * Supported Spotify actions:
 *  - [ACTION_METADATA_CHANGED]  — "com.spotify.music.metadatachanged"
 *  - [ACTION_PLAYBACK_STATE_CHANGED] — "com.spotify.music.playbackstatechanged"
 *
 * On ad detection it sends a local [ACTION_AD_DETECTED] broadcast to [MuterService].
 * On normal music it sends [ACTION_MUSIC_DETECTED].
 * When playback stops it sends [ACTION_NO_METADATA].
 *
 * ## Debounce
 * A track-update cache prevents duplicate broadcasts for the same track
 * within a 500 ms window, eliminating redundant mute/unmute cycles when
 * both METADATA_CHANGED and PLAYBACK_STATE_CHANGED fire for the same event.
 */
class SpotifyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SpotifyReceiver"

        /** Minimum time (ms) between duplicate actions to avoid re-processing. */
        private const val DEBOUNCE_MS = 500L

        /** Spotify broadcast sent when the currently playing track metadata changes. */
        const val ACTION_METADATA_CHANGED = "com.spotify.music.metadatachanged"

        /** Spotify broadcast sent when the playback state (playing/paused) changes. */
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"

        // Intent extra keys (as documented by Spotify)
        const val EXTRA_ID = "id"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_TRACK = "track"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_LENGTH = "length"
        const val EXTRA_PLAYING = "playing"

        // Local action strings used to communicate with MuterService
        const val ACTION_AD_DETECTED = "com.admuter.ACTION_AD_DETECTED"
        const val ACTION_MUSIC_DETECTED = "com.admuter.ACTION_MUSIC_DETECTED"
        const val ACTION_NO_METADATA = "com.admuter.ACTION_NO_METADATA"
    }

    // ---- Debounce cache ----
    @Volatile
    private var lastTrackId: String? = null
    @Volatile
    private var lastPlaybackState: Boolean? = null
    @Volatile
    private var lastProcessTimestamp: Long = 0L

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_METADATA_CHANGED -> handleMetadataChanged(context, intent)
            ACTION_PLAYBACK_STATE_CHANGED -> handlePlaybackStateChanged(context, intent)
        }
    }

    /**
     * Returns true if this event should be skipped due to the debounce window.
     * Updates the cache if the event is different from the last processed one.
     */
    private fun isDuplicate(id: String, playing: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (id == lastTrackId && playing == lastPlaybackState && (now - lastProcessTimestamp) < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced duplicate track: id=$id playing=$playing")
            return true
        }
        lastTrackId = id
        lastPlaybackState = playing
        lastProcessTimestamp = now
        return false
    }

    // ---------------------------------------------------------------
    //  Metadata Changed handler  (primary ad-detection path)
    // ---------------------------------------------------------------

    private fun handleMetadataChanged(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        val track = intent.getStringExtra(EXTRA_TRACK) ?: ""
        val album = intent.getStringExtra(EXTRA_ALBUM) ?: ""
        val length = intent.getIntExtra(EXTRA_LENGTH, -1)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        // ---- Diagnostic logging (logcat + in-app) ----
        val logLine = "METADATA_CHANGED | ID=$id | Artist=$artist | Track=$track | Album=$album | Length=$length | Playing=$playing"
        Log.d("AdMuterDebug", "Action: $ACTION_METADATA_CHANGED | ID: $id | Artist: $artist | Track: $track | Album: $album | Length: $length | Playing: $playing")
        Log.d(TAG, "Metadata received — $logLine")
        DebugEventLog.add("[SpotifyReceiver] $logLine")

        // Empty metadata  →  playback stopped
        if (id.isEmpty() && artist.isEmpty() && track.isEmpty()) {
            Log.d(TAG, "→ Empty metadata (playback stopped), sending NO_METADATA")
            DebugEventLog.add("[SpotifyReceiver] → No metadata — sending NO_METADATA")
            sendLocalBroadcast(context, ACTION_NO_METADATA)
            lastTrackId = null
            return
        }

        // Debounce: skip if same track within window
        if (isDuplicate(id, playing)) return

        // Classify as ad or music
        val isAd = isAdMetadata(id, artist, track, playing, length)

        val localAction = if (isAd) {
            Log.d(TAG, "→ AD detected, sending AD_DETECTED")
            DebugEventLog.add("[SpotifyReceiver] → AD classified as ad")
            ACTION_AD_DETECTED
        } else {
            Log.d(TAG, "→ Normal music track detected, sending MUSIC_DETECTED")
            DebugEventLog.add("[SpotifyReceiver] → Track classified as music")
            ACTION_MUSIC_DETECTED
        }

        sendLocalBroadcast(context, localAction)
    }

    // ---------------------------------------------------------------
    //  Playback State Changed handler  (diagnostics + stop detection)
    // ---------------------------------------------------------------

    private fun handlePlaybackStateChanged(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        val track = intent.getStringExtra(EXTRA_TRACK) ?: ""
        val album = intent.getStringExtra(EXTRA_ALBUM) ?: ""
        val length = intent.getIntExtra(EXTRA_LENGTH, -1)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        // ---- Diagnostic logging (logcat + in-app) ----
        val logLine = "PLAYBACK_STATE_CHANGED | ID=$id | Artist=$artist | Track=$track | Album=$album | Length=$length | Playing=$playing"
        Log.d("AdMuterDebug", "Action: $ACTION_PLAYBACK_STATE_CHANGED | ID: $id | Artist: $artist | Track: $track | Album: $album | Length: $length | Playing: $playing")
        Log.d(TAG, "Playback state changed — $logLine")
        DebugEventLog.add("[SpotifyReceiver] $logLine")

        // When playback stops (e.g. user exits Spotify or ad finishes), send NO_METADATA.
        if (!playing) {
            Log.d(TAG, "→ Playback stopped, sending NO_METADATA")
            DebugEventLog.add("[SpotifyReceiver] → Playback stopped — sending NO_METADATA")
            sendLocalBroadcast(context, ACTION_NO_METADATA)
            lastTrackId = null
            return
        }

        // Only classify as ad if we have enough metadata to make a determination.
        // Otherwise let the next METADATA_CHANGED broadcast handle it.
        if (id.isNotEmpty() || artist.isNotEmpty() || track.isNotEmpty()) {
            // Debounce: skip if same track within window
            if (isDuplicate(id, playing)) return

            val isAd = isAdMetadata(id, artist, track, playing, length)
            val localAction = if (isAd) {
                Log.d(TAG, "→ AD detected from playback state, sending AD_DETECTED")
                DebugEventLog.add("[SpotifyReceiver] → Playback state: AD")
                ACTION_AD_DETECTED
            } else {
                Log.d(TAG, "→ Music detected from playback state, sending MUSIC_DETECTED")
                DebugEventLog.add("[SpotifyReceiver] → Playback state: Music")
                ACTION_MUSIC_DETECTED
            }
            sendLocalBroadcast(context, localAction)
        } else {
            Log.d(TAG, "→ Playing, but no metadata yet — waiting for METADATA_CHANGED")
            DebugEventLog.add("[SpotifyReceiver] → Playing, no metadata yet")
        }
    }

    // ---------------------------------------------------------------
    //  Ad-classification logic
    // ---------------------------------------------------------------

    /**
     * Determines whether the given metadata corresponds to a Spotify ad.
     *
     * Detection rules (any **one** being true classifies as an ad):
     *
     * 1. **ID-based** — `id` starts with `"spotify:ad:"` or contains `":ad:"` as a URI segment.
     * 2. **Track-based** — `track` equals `"Advertisement"` or `"Spotify"` (case-insensitive).
     * 3. **Artist-based** — `artist` is null / empty / equals `"Spotify"` while `playing` is true.
     * 4. **Duration-based** — `length` is in (0..30 000] ms AND artist is empty or `"Spotify"`.
     */
    internal fun isAdMetadata(
        id: String,
        artist: String,
        track: String,
        playing: Boolean,
        length: Int = -1
    ): Boolean {
        // --- Rule 1: ID-based ---
        if (id.startsWith("spotify:ad") || id.contains(":ad:")) {
            DebugEventLog.add("[isAdMetadata] Rule 1 matched: id='$id'")
            return true
        }

        // --- Rule 2: Track-based ---
        if (track.equals("Advertisement", ignoreCase = true) ||
            track.equals("Spotify", ignoreCase = true)
        ) {
            DebugEventLog.add("[isAdMetadata] Rule 2 matched: track='$track'")
            return true
        }

        // --- Rule 3: Artist-based while playing ---
        val artistIsGeneric = artist.isBlank() || artist.equals("Spotify", ignoreCase = true)
        if (artistIsGeneric && playing) {
            DebugEventLog.add("[isAdMetadata] Rule 3 matched: artist='$artist', playing=$playing")
            return true
        }

        // --- Rule 4: Duration-based with generic artist ---
        if (length in 1..30_000 && artistIsGeneric) {
            DebugEventLog.add("[isAdMetadata] Rule 4 matched: length=${length}ms, artist='$artist'")
            return true
        }

        return false
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private fun sendLocalBroadcast(context: Context, action: String) {
        val localIntent = Intent().apply {
            `package` = context.packageName
            this.action = action
        }
        context.sendBroadcast(localIntent)
    }
}

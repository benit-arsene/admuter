package com.admuter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for Spotify's "com.spotify.music.metadatachanged"
 * intent to detect when an ad is playing.
 *
 * Detection logic:
 *  - "id" starts with "spotify:ad"  OR
 *  - "artist" is empty or null       OR
 *  - "track" equals "Advertisement"
 *
 * On ad detection, sends a local broadcast/command to [MuterService] to mute.
 * On normal track detection, sends a command to restore volume.
 */
class SpotifyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SpotifyReceiver"
        const val ACTION_METADATA_CHANGED = "com.spotify.music.metadatachanged"

        const val EXTRA_ID = "id"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_TRACK = "track"

        // Local action strings used to communicate with MuterService
        const val ACTION_AD_DETECTED = "com.admuter.ACTION_AD_DETECTED"
        const val ACTION_MUSIC_DETECTED = "com.admuter.ACTION_MUSIC_DETECTED"
        const val ACTION_NO_METADATA = "com.admuter.ACTION_NO_METADATA"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_METADATA_CHANGED) return

        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        val track = intent.getStringExtra(EXTRA_TRACK) ?: ""

        // Debug log — visible in logcat for troubleshooting
        Log.d("AdMuter", "Received track: $track by $artist, ID: $id")

        Log.d(TAG, "Metadata received — id=$id, artist=$artist, track=$track")

        // Check for empty metadata (playback stopped) BEFORE ad detection,
        // because isAdMetadata returns true for blank artist even when all
        // fields are empty.
        if (id.isEmpty() && artist.isEmpty() && track.isEmpty()) {
            Log.d(TAG, "→ Empty metadata (playback stopped), sending NO_METADATA")
            val localIntent = Intent().apply {
                `package` = context.packageName
                action = ACTION_NO_METADATA
            }
            context.sendBroadcast(localIntent)
            return
        }

        val isAd = isAdMetadata(id, artist, track, intent)

        val localIntent = Intent().apply {
            `package` = context.packageName
            action = if (isAd) {
                Log.d(TAG, "→ Ad detected, sending AD_DETECTED")
                ACTION_AD_DETECTED
            } else {
                Log.d(TAG, "→ Normal music track detected, sending MUSIC_DETECTED")
                ACTION_MUSIC_DETECTED
            }
        }

        // Send local broadcast to MuterService via the package-specific broadcast
        context.sendBroadcast(localIntent)
    }

    /**
     * Determines whether the given metadata corresponds to a Spotify ad.
     *
     * Detection signals:
     *  - id starts with "spotify:ad" or contains "ad"
     *  - artist is blank or null while a track is active
     *  - track equals "Advertisement" (case-insensitive)
     *  - playbackPosition is very short (ad-length, e.g. < 60s)
     */
    internal fun isAdMetadata(
        id: String,
        artist: String,
        track: String,
        intent: Intent? = null
    ): Boolean {
        // Primary heuristics
        if (id.startsWith("spotify:ad") ||
            artist.isBlank() ||
            track.equals("Advertisement", ignoreCase = true)
        ) return true

        // Extra: check track length — ads are typically short (< 60 seconds)
        if (intent != null && intent.hasExtra("length")) {
            val lengthMs = intent.getLongExtra("length", -1)
            if (lengthMs in 1..60_000) {
                Log.d(TAG, "→ Short length (${lengthMs}ms) suggests an ad")
                return true
            }
        }

        return false
    }
}

package com.admuter

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NotificationListenerService that monitors Spotify's notifications to detect ads.
 *
 * This is **more reliable** than the Spotify broadcast method because:
 *  - It does NOT require the user to enable "Device Broadcast Status" inside Spotify.
 *  - Spotify always posts a notification when playing (required by Android).
 *  - It works on all Android versions including Android 14+.
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
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected — processing existing notifications")
        for (sbn in activeNotifications) {
            processSpotifyNotification(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return
        processSpotifyNotification(sbn)
    }

    private fun processSpotifyNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val info = extras.getString(Notification.EXTRA_INFO_TEXT) ?: ""

        Log.d(TAG, "Spotify notification — title=\"$title\", info=\"$info\"")

        val isAd = isAdNotification(title, info)

        val intentAction = if (isAd) {
            Log.d(TAG, "→ Ad detected from notification")
            SpotifyReceiver.ACTION_AD_DETECTED
        } else if (title.isNotEmpty()) {
            Log.d(TAG, "→ Music track detected from notification")
            SpotifyReceiver.ACTION_MUSIC_DETECTED
        } else {
            Log.d(TAG, "→ Empty notification, skipping")
            return
        }

        val localIntent = Intent().apply {
            `package` = packageName
            action = intentAction
        }
        sendBroadcast(localIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return

        Log.d(TAG, "→ Spotify notification removed (playback stopped), sending NO_METADATA")
        val localIntent = Intent().apply {
            `package` = packageName
            action = SpotifyReceiver.ACTION_NO_METADATA
        }
        sendBroadcast(localIntent)
    }

    private fun isAdNotification(title: String, info: String): Boolean {
        if (title.equals("Advertisement", ignoreCase = true)) return true
        if (info.equals("Advertisement", ignoreCase = true)) return true
        if (title.equals("Ad", ignoreCase = true)) return true
        if (info.equals("Ad", ignoreCase = true)) return true
        return false
    }
}

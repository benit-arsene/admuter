package com.admuter

import android.app.Notification
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class AdNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AdNotificationListener"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
    }

    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var lastId: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Connected")
        DebugEventLog.add("[AdNotificationListener] Connected")
        for (sbn in activeNotifications) {
            if (sbn.packageName == SPOTIFY_PACKAGE) {
                connectToMediaSession(sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return
        DebugEventLog.add("[AdNotificationListener] Notification posted")
        connectToMediaSession(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return
        DebugEventLog.add("[AdNotificationListener] Notification removed")
        releaseController()
        val intent = Intent()
        intent.setPackage(packageName)
        intent.action = SpotifyReceiver.ACTION_NO_METADATA
        sendBroadcast(intent)
    }

    private fun connectToMediaSession(sbn: StatusBarNotification) {
        val extras: Bundle = sbn.notification.extras ?: return
        val title: String = extras.getString(Notification.EXTRA_TITLE, "")
        val subText: String = extras.getString(Notification.EXTRA_SUB_TEXT, "")
        val text: String = extras.getString(Notification.EXTRA_TEXT, "")
        val info: String = extras.getString(Notification.EXTRA_INFO_TEXT, "")
        DebugEventLog.add("[AdNotificationListener] title=\"$title\" subText=\"$subText\"")

        // Get MediaSession.Token from notification
        val rawToken: Any? = extras.get(Notification.EXTRA_MEDIA_SESSION)
        if (rawToken !is MediaSession.Token) {
            Log.w(TAG, "No MediaSession.Token")
            return
        }

        releaseController()

        val ctl: MediaController = MediaController(this, rawToken)
        controller = ctl
        DebugEventLog.add("[AdNotificationListener] Connected to MediaSession")

        val state: PlaybackState? = ctl.playbackState
        val meta: android.media.MediaMetadata? = ctl.metadata
        if (meta != null) {
            classify(meta, state)
        }

        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                if (metadata != null) {
                    classify(metadata, controller?.playbackState)
                }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state != null && state.state == PlaybackState.STATE_STOPPED) {
                    sendNoMetadata()
                    return
                }
                val currentMeta = controller?.metadata
                if (currentMeta != null) {
                    val id = currentMeta.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
                    if (id != null && id.isNotEmpty() && id == lastId) return
                    classify(currentMeta, state)
                }
            }
        }
        callback = cb
        ctl.registerCallback(cb)
    }

    private fun classify(metadata: android.media.MediaMetadata, state: PlaybackState?) {
        val mediaId = metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val track = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val playing = state?.state == PlaybackState.STATE_PLAYING

        if (mediaId.isNotEmpty() && mediaId == lastId) return
        if (mediaId.isNotEmpty()) lastId = mediaId

        DebugEventLog.add("[AdNotificationListener] ID=$mediaId Artist=$artist Track=$track Duration=${duration}ms Playing=$playing")

        if (mediaId.startsWith("spotify:ad") || mediaId.contains(":ad:")) {
            DebugEventLog.add("[AdNotificationListener] AD detected (rule 1)")
            sendAd()
            return
        }
        if (track.equals("Advertisement", ignoreCase = true) || track.equals("Spotify", ignoreCase = true)) {
            DebugEventLog.add("[AdNotificationListener] AD detected (rule 2)")
            sendAd()
            return
        }
        val genericArtist = artist.isBlank() || artist.equals("Spotify", ignoreCase = true)
        if (genericArtist && playing) {
            DebugEventLog.add("[AdNotificationListener] AD detected (rule 3)")
            sendAd()
            return
        }
        if (duration in 1..30000 && genericArtist) {
            DebugEventLog.add("[AdNotificationListener] AD detected (rule 4)")
            sendAd()
            return
        }

        if (mediaId.isNotEmpty() || track.isNotEmpty()) {
            DebugEventLog.add("[AdNotificationListener] Music detected")
            sendMusic()
        } else {
            DebugEventLog.add("[AdNotificationListener] Empty metadata")
            sendNoMetadata()
        }
    }

    private fun sendAd() {
        val intent = Intent()
        intent.setPackage(packageName)
        intent.action = SpotifyReceiver.ACTION_AD_DETECTED
        sendBroadcast(intent)
    }

    private fun sendMusic() {
        val intent = Intent()
        intent.setPackage(packageName)
        intent.action = SpotifyReceiver.ACTION_MUSIC_DETECTED
        sendBroadcast(intent)
    }

    private fun sendNoMetadata() {
        val intent = Intent()
        intent.setPackage(packageName)
        intent.action = SpotifyReceiver.ACTION_NO_METADATA
        sendBroadcast(intent)
    }

    private fun releaseController() {
        val c = controller
        val cb = callback
        if (c != null && cb != null) {
            c.unregisterCallback(cb)
        }
        callback = null
        controller = null
    }
}

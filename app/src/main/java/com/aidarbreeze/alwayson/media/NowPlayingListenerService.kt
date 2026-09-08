package com.aidarbreeze.alwayson.media

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the currently-playing track from media ("MediaStyle") notifications.
 *
 * A regular app cannot reliably list another app's media session via
 * [android.media.session.MediaSessionManager.getActiveSessions] on modern
 * Android (it only sees sessions the app is allowed to control). The reliable
 * cross-app way is a notification listener: every music player posts a media
 * notification carrying the track (in its fields) and its [MediaSession.Token].
 *
 * Requires the user to enable "Notification access" for this app in the system
 * settings (we open that screen from the app). Without it the card simply
 * stays hidden and the clock/calendar keep working.
 */
class NowPlayingListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        refresh()
    }

    override fun onListenerDisconnected() {
        NowPlayingCache.current = null
        NowPlayingCache.updatedAt = 0L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        NowPlayingCache.current = null
        NowPlayingCache.updatedAt = 0L
    }

    /** Scan current notifications and publish the first one that is really
     *  PLAYING audio. A notification is only accepted when it carries a
     *  MediaSession token AND its playback state is actually STATE_PLAYING;
     *  anything we cannot positively confirm as playing (paused, stopped,
     *  stale, no readable state) is ignored so the card never shows while no
     *  music is playing.
     *
     *  Every [MediaController] we create is released right after the read:
     *  the framework allows only a handful of live controllers per app
     *  (Android 13+ throws once the limit is exceeded), and refresh() runs on
     *  every notification post/remove, so an un-released controller would
     *  exhaust the limit and break the card permanently. */
    private fun refresh() {
        val notifs = try {
            activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }

        var found: NowPlaying? = null
        for (sbn in notifs) {
            val n = sbn.notification
            val extras = n.extras

            // Must carry a MediaSession token; a bare CATEGORY_TRANSPORT with no
            // confirmable session is not enough (avoids stale/placeholder cards).
            val token = mediaToken(extras)
            if (token == null) continue

            val controller = try {
                MediaController(this, token)
            } catch (_: Exception) {
                null
            }
            if (controller == null) continue

            var playing = false
            var title: String? = null
            var artist = ""
            try {
                // Positively confirm it is playing right now.
                val state = try {
                    controller.playbackState?.state ?: -1
                } catch (_: Exception) {
                    -1
                }
                if (state == PlaybackState.STATE_PLAYING) {
                    // Title must be present and non-blank.
                    val meta = try {
                        controller.metadata
                    } catch (_: Exception) {
                        null
                    }
                    val t = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?: extras.getString(Notification.EXTRA_TITLE)
                    if (t != null && t.isNotBlank()) {
                        title = t
                        artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                            ?: extras.getString(Notification.EXTRA_TEXT)
                            ?: ""
                        playing = true
                    }
                }
            } catch (_: Exception) {
                // unreadable session; ignore
            } finally {
                try {
                    controller.release()
                } catch (_: Exception) {
                    // already gone
                }
            }

            if (playing && title != null) {
                found = NowPlaying(title, artist, playing = true)
                break
            }
        }

        NowPlayingCache.current = found
        NowPlayingCache.updatedAt = System.currentTimeMillis()
    }

    private fun mediaToken(extras: Bundle): MediaSession.Token? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            }
        } catch (_: Exception) {
            null
        }
    }
}

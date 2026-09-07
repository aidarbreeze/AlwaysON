package com.aidarbreeze.alwayson.media

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the currently-playing track from media ("MediaStyle") notifications.
 *
 * A regular app cannot reliably list another app's media session via
 * [android.media.session.MediaSessionManager.getActiveSessions] on modern
 * Android (it only sees sessions the app is allowed to control). The reliable
 * cross-app way is a notification listener: every music player posts a media
 * notification that carries its [MediaSession.Token], which we use to read the
 * title / artist / play state.
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

    /** Scan current notifications and pick the first one that is actually
     *  playing, then publish it to [NowPlayingCache]. */
    private fun refresh() {
        val notifs = try {
            activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }

        var found: NowPlaying? = null
        for (sbn in notifs) {
            val n = sbn?.notification ?: continue
            val extras = n.extras ?: continue
            val token = mediaToken(extras) ?: continue
            val controller = try {
                MediaController(this, token)
            } catch (_: Exception) {
                continue
            }
            val meta = try {
                controller.metadata
            } catch (_: Exception) {
                null
            } ?: continue
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: continue
            if (title.isBlank()) continue
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""

            val state = try {
                controller.playbackState?.state
            } catch (_: Exception) {
                null
            }
            val playing = state == null ||
                state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING

            // Only show the card while audio is actually playing.
            if (playing) {
                found = NowPlaying(title, artist, playing = true)
                break
            }
        }

        NowPlayingCache.current = found
        NowPlayingCache.updatedAt = System.currentTimeMillis()
    }

    private fun mediaToken(extras: android.os.Bundle): MediaSession.Token? {
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

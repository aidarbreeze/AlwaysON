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
import com.aidarbreeze.alwayson.CallState
import com.aidarbreeze.alwayson.notif.NotifCache
import com.aidarbreeze.alwayson.notif.NotifItem
import com.aidarbreeze.alwayson.service.OverlayService

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
        // We no longer see any notifications: the messenger list and the
        // call flag must not outlive the listener, or inCall() would stay
        // true forever and the StandBy clock would never show again.
        NotifCache.publish(emptyList())
        if (CallState.callNotificationActive) {
            CallState.callNotificationActive = false
            OverlayService.requestReevaluate(this)
        }
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
        // Same "stuck call flag" hazard as onListenerDisconnected: a
        // destroyed listener never posts another refresh() to clear it.
        CallState.callNotificationActive = false
    }

    /** Scan current notifications and publish the first one that is really
     *  PLAYING audio. A notification is only accepted when it carries a
     *  MediaSession token AND its playback state is actually STATE_PLAYING;
     *  anything we cannot positively confirm as playing (paused, stopped,
     *  stale, no readable state) is ignored so the card never shows while no
     *  music is playing.
     *
     *  The same scan also feeds two shared flags:
   *   - messenger messages (Telegram / Max) -> [NotifCache] for the swipe
   *     notifications page of the StandBy screen;
   *   - call notifications (phone / VoIP) -> [CallState], so the StandBy
   *     windows step aside and never cover an incoming-call dialog.
     *
     *  Note: MediaController has no release() API — the controller is created
     *  per scan as a local that we never store, so it stays collectable. */
    private fun refresh() {
        val notifs = try {
            activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }

        val msgs = ArrayList<NotifItem>()
        var callActive = false
        for (sbn in notifs) {
            val n = sbn.notification
            if (CallState.isCallNotification(n)) {
                callActive = true
                continue
            }
            if (!NotifCache.isMessenger(sbn.packageName) || !sbn.isClearable) continue
            val extras = n.extras
            // Skip the "app is running in the background" style entries: only
            // real messages (a title and/or text) are listed.
            val title = (
                extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                )?.toString()?.trim().orEmpty()
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                )?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) continue
            msgs.add(
                NotifItem(
                    title = if (title.isEmpty()) text else title,
                    text = if (title.isEmpty()) "" else text,
                    timeMs = sbn.postTime
                )
            )
        }

        // Newest first; identical (title, text) collapsed to the newest copy;
        // bounded so a chatty chat can never grow the list without limit.
        msgs.sortByDescending { it.timeMs }
        val seen = HashSet<String>()
        val out = ArrayList<NotifItem>()
        for (m in msgs) {
            if (seen.add(m.title + '\u0000' + m.text)) out.add(m)
            if (out.size >= NotifCache.MAX_KEEP) break
        }
        NotifCache.publish(out)

        // Publish the call flag; if it FLIPPED, the standby windows must
        // re-evaluate right now (hide on call start, restore on call end).
        if (CallState.callNotificationActive != callActive) {
            CallState.callNotificationActive = callActive
            OverlayService.requestReevaluate(this)
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

            // Positively confirm it is playing right now.
            val state = try {
                controller.playbackState?.state ?: -1
            } catch (_: Exception) {
                -1
            }
            if (state != PlaybackState.STATE_PLAYING) continue

            // Title must be present and non-blank.
            val meta = try {
                controller.metadata
            } catch (_: Exception) {
                null
            }
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: extras.getString(Notification.EXTRA_TITLE)
                ?: continue
            if (title.isBlank()) continue

            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: extras.getString(Notification.EXTRA_TEXT)
                ?: ""

            // Album art (small, grayscale-drawn later), best-effort.
            val artBmp = try {
                NowPlayingCache.downscale(meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
            } catch (_: Exception) {
                null
            }

            found = NowPlaying(title, artist, playing = true, art = artBmp)
            break
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

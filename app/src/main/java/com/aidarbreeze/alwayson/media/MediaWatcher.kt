package com.aidarbreeze.alwayson.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Supplies the currently playing track to the StandBy screens.
 *
 * It prefers the fresh snapshot written by [NowPlayingListenerService] (the
 * reliable cross-app path). As a fallback it polls active media sessions
 * directly; on many Android/OEM builds that returns nothing unless the app is
 * the active media-button handler, which is exactly why the notification
 * listener exists. Every call is guarded so the card simply stays hidden when
 * it cannot see any music.
 */
class MediaWatcher(private val context: Context) {

    // The ticker calls current() EVERY second; without a gate a missing or
    // revoked notification access re-attempted (and re-threw) the binder
    // call once per second, forever. One fallback attempt per 3 s is plenty.
    private var lastFallbackAttempt = 0L

    // Last positively-confirmed "playing" snapshot and when it was seen.
    // This is the hysteresis that keeps the card from BLINKING: the cache
    // from the listener ages out between notification events while the
    // fallback poll may only run once per 3 s, so the old logic returned
    // null for two seconds out of every three — the card showed for a
    // second, vanished for two, and repeated. Within [GRACE_MS] of the last
    // positive sighting the card is held stable; music that really stops
    // hides the card at most [GRACE_MS] later (an explicit fresh "paused"
    // from the listener hides it immediately).
    private var lastPlaying: NowPlaying? = null
    private var lastPlayingAt = 0L

    fun current(): NowPlaying? {
        val now = System.currentTimeMillis()
        // Preferred path: what the notification listener just saw. The
        // listener only rewrites the snapshot on notification events, so a
        // quietly playing track lets it age — trust it for 10 s now (5 s
        // before, which is what started the blinking). A fresh, explicit
        // "not playing" still hides the card immediately.
        val cached = NowPlayingCache.current
        if (cached != null && now - NowPlayingCache.updatedAt < 10_000) {
            if (!cached.playing) {
                lastPlaying = null
                return null
            }
            lastPlaying = cached
            lastPlayingAt = now
            return cached
        }
        // Fallback: poll media sessions directly (playing ones only), at
        // most once per 3 s. While the gate blocks — or the poll comes up
        // empty — the grace anchor keeps the card on screen instead of
        // hiding it for two seconds out of every three.
        var polled: NowPlaying? = null
        if (now - lastFallbackAttempt >= 3_000) {
            lastFallbackAttempt = now
            polled = pollSessions()
        }
        if (polled != null) {
            lastPlaying = polled
            lastPlayingAt = now
            return polled
        }
        if (lastPlaying != null && now - lastPlayingAt < GRACE_MS) return lastPlaying
        lastPlaying = null
        return null
    }

    /** One direct poll of the active media sessions; null when nothing is
     *  positively playing (or the access is missing/revoked). */
    private fun pollSessions(): NowPlaying? {
        return try {
            val manager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            // getActiveSessions() must receive the component of the app's
            // NOTIFICATION LISTENER — that registration is what grants the
            // access. The media-button receiver component used here before
            // made the system throw SecurityException on every build, so
            // this fallback never actually worked.
            val listenerComponent =
                ComponentName(context, NowPlayingListenerService::class.java)
            val controllers = manager.getActiveSessions(listenerComponent)

            var best: MediaController? = null
            for (c in controllers) {
                val ps = c.playbackState ?: continue
                if (c.metadata == null) continue
                if (ps.state == PlaybackState.STATE_PLAYING) {
                    best = c
                    break
                }
            }
            val controller = best ?: return null
            val md = controller.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: return null
            if (title.isBlank()) return null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val art = try {
                NowPlayingCache.downscale(md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
            } catch (_: Exception) {
                null
            }
            NowPlaying(title, artist, playing = true, art = art)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /** How long the card is held stable after the last positive
         *  "playing" sighting before it may hide (polls miss, caches age —
         *  12 s = four consecutive failed fallback polls). */
        const val GRACE_MS = 12_000L
    }
}

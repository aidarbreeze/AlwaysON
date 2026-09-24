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

    fun current(): NowPlaying? {
        // Preferred path: what the notification listener just saw. Only trust
        // it while it is fresh AND reported as actually playing.
        val cached = NowPlayingCache.current
        if (cached != null && cached.playing &&
            System.currentTimeMillis() - NowPlayingCache.updatedAt < 5_000
        ) {
            return cached
        }
        // Fallback: poll media sessions directly (playing ones only).
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
}

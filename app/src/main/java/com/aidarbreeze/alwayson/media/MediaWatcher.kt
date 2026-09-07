package com.aidarbreeze.alwayson.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Best-effort reader of whatever music is currently playing. It is polled
 * periodically by [com.aidarbreeze.alwayson.StandbyController]. On some OEM
 * builds reading active media sessions requires extra system permissions, so
 * every call is guarded — if it is not allowed the media card simply stays
 * hidden and the clock/calendar keep working.
 */
class MediaWatcher(private val context: Context) {

    data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

    fun current(): NowPlaying? {
        return try {
            val manager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val receiver = ComponentName(context, MediaButtonReceiver::class.java)
            val controllers = manager.getActiveSessions(receiver)

            var best: MediaController? = null
            var bestPlaying = false
            for (c in controllers) {
                val ps = c.playbackState ?: continue
                if (c.metadata == null) continue
                val playing = ps.state == PlaybackState.STATE_PLAYING
                if (playing && !bestPlaying) {
                    best = c
                    bestPlaying = true
                } else if (best == null) {
                    best = c
                }
            }
            val controller = best ?: return null
            val md = controller.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (title.isNullOrBlank()) return null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            NowPlaying(title, artist, playing)
        } catch (_: Exception) {
            null
        }
    }
}

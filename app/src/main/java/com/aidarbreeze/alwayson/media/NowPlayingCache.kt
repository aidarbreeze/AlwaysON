package com.aidarbreeze.alwayson.media

/** What is playing right now (title, artist and whether audio is playing). */
data class NowPlaying(
    val title: String,
    val artist: String,
    val playing: Boolean
)

/**
 * Single global snapshot of the current media, shared between the
 * notification listener (which fills it) and every StandBy screen (which
 * reads it). Using one cache means any overlay / preview / screen-saver
 * instance sees the same now-playing card.
 */
object NowPlayingCache {
    @Volatile
    var current: NowPlaying? = null

    @Volatile
    var updatedAt: Long = 0L
}

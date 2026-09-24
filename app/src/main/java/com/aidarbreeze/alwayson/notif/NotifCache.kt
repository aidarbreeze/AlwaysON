package com.aidarbreeze.alwayson.notif

/** One messenger notification shown on the StandBy notifications page. */
data class NotifItem(
    val title: String,
    val text: String,
    val timeMs: Long
)

/**
 * In-memory cache of recent messenger notifications (Telegram + Max) for the
 * swipe notifications page of the StandBy screen. Filled by the notification
 * listener ([com.aidarbreeze.alwayson.media.NowPlayingListenerService]) and
 * read by [com.aidarbreeze.alwayson.StandbyController] on its 1-second tick —
 * the whole app runs in one process, so a plain volatile field is enough.
 */
object NotifCache {

    /** How many items are kept in the cache (the UI shows Prefs.notifMax). */
    const val MAX_KEEP = 30

    @Volatile
    var items: List<NotifItem> = emptyList()

    @Volatile
    var updatedAt = 0L

    /**
     * True when the notification is from a supported messenger:
     * Max (ru.oneme.app) and Telegram plus its common clients.
     */
    fun isMessenger(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return p == "ru.oneme.app" ||                 // MAX messenger
            p.startsWith("org.telegram") ||           // Telegram official/forks
            p.startsWith("org.thunderdog.challegram") || // Telegram X
            p.contains("telegram")                    // other Telegram clients
    }

    fun publish(list: List<NotifItem>) {
        items = list
        updatedAt = System.currentTimeMillis()
    }
}

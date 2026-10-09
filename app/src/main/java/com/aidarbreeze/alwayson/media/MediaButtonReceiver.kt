package com.aidarbreeze.alwayson.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declaring a media-button receiver lets [MediaWatcher] list the currently
 * active media sessions on Android. It does nothing by itself.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty.
    }
}

package com.aidarbreeze.alwayson.ui

/**
 * Tiny shared runtime flags so the auto overlay service, the system screen
 * saver (DreamService) and the full-screen preview do not all draw the StandBy
 * screen at the same time (which would stack two black windows and look
 * broken).
 */
object StandbyUiState {
    /** True while the full-screen StandbyActivity preview is on screen. */
    @Volatile
    var previewVisible: Boolean = false

    /** True while the system daydream (ClockDreamService) exists — raised
     *  in its onCreate (before the window is up, to close the boot race with
     *  the overlay service), cleared in onDestroy / onDreamingStopped /
     *  onDetachedFromWindow. The charging overlay must not stack on top of
     *  an active or starting dream. */
    @Volatile
    var dreaming: Boolean = false

    /** SystemClock.elapsedRealtime() of the moment the user tapped the
     *  dream away, or 0. The overlay service holds off re-hosting the
     *  clock while this is set — otherwise its per-minute/sensor
     *  re-evaluations bring the clock back within a minute and the
     *  dismissal looks ignored. Cleared on the next SCREEN_OFF. */
    @Volatile
    var dreamDismissedAt: Long = 0L
}

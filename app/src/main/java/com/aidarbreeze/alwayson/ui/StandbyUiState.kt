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

    /** True while the system daydream (ClockDreamService) is showing. The
     *  charging overlay must not stack on top of an active dream. */
    @Volatile
    var dreaming: Boolean = false
}

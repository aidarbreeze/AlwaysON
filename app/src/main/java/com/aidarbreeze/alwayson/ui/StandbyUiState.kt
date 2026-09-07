package com.aidarbreeze.alwayson.ui

/**
 * Tiny shared runtime flags so the auto overlay service and the full-screen
 * preview do not both draw the StandBy screen at the same time (which would
 * stack two black windows and look broken).
 */
object StandbyUiState {
    /** True while the full-screen StandbyActivity preview is on screen. */
    @Volatile
    var previewVisible: Boolean = false
}

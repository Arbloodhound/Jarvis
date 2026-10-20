package com.agent.ultra.agent

/**
 * When Jarvis's own injected gesture may start while the takeover overlay is up.
 *
 * The overlay is made non-touchable with WindowManager.updateViewLayout, which only schedules a
 * traversal (AOSP ViewRootImpl.setLayoutParams: `scheduleTraversals()`); the window server and
 * then the input dispatcher see the new flag a frame or more later. A gesture's first touch is
 * injected at once (AOSP MotionEventInjector.injectEventsMainThread: delay = event time - now, 0
 * for a stroke starting at 0), so a gesture sent straight after the flag change lands on the
 * overlay and is swallowed, while the system still reports it completed. The gesture therefore
 * waits until the flag has had [SETTLE_MS] to reach the input system.
 */
object TakeoverPass {
    /**
     * Next frame (up to 16.7 ms at 60 Hz) + relayout + the window server's input update + the
     * compositor's next frame is about 50 ms; more than twice that for a busy phone.
     * Not measured on a device.
     */
    const val SETTLE_MS = 120L

    /** How long to hold the gesture: the overlay stopped taking touches at [touchableOffAt] (0 = it has not). */
    @JvmStatic
    fun waitMs(touchableOffAt: Long, now: Long): Long =
        if (touchableOffAt <= 0L) SETTLE_MS else (touchableOffAt + SETTLE_MS - now).coerceIn(0L, SETTLE_MS)
}

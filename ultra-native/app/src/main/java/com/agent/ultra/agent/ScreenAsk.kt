package com.agent.ultra.agent

/**
 * A request to drive another app on the screen ("use the phone screen to search", "go on Google and
 * search ..."). Only the cloud brain can do that (ReActNavigator); the on-device model has no tool for
 * it, and left alone it narrates screen actions it never took (Pixel 2026-10-18). Pure, JVM-tested.
 */
object ScreenAsk {
    private val RE = Regex(
        "\\b((use|using|on|from|with) (the |my )?(phone'?s? )?screen|go (on|to) (google|chrome|the browser|the web)|" +
            "(open|launch) (google|chrome|the browser)\\b.*\\b(search|type|tap|click))",
        RegexOption.IGNORE_CASE,
    )

    fun matches(request: String): Boolean = RE.containsMatchIn(IntentRouter.requestPart(request))

    const val NEEDS_CLOUD =
        "I couldn't work out which app to drive on-device, so I did not touch the screen. Name the app " +
            "(\"open Chrome and search ...\"), or pick a cloud model with the chip at the top of the chat."
}

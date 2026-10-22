package com.hermesandroid.bridge.executor

import com.hermesandroid.bridge.model.ActionResult

/**
 * Stable facade for bridge actions: one public object, one public method per
 * action, unchanged signatures — defaults included. Call sites
 * ([com.hermesandroid.bridge.server.CommandDispatcher], the accessibility
 * service, and the tests) only ever talk to this object; the implementations
 * live in the focused domain executors in this package —
 * [GestureExecutor], [NodeLookup], [TextEntryExecutor], [SystemKeyExecutor],
 * [ScreenshotExecutor], [AppIntentExecutor], [DeviceServicesExecutor],
 * [ScreenQueryExecutor] and [TtsExecutor].
 *
 * The two `internal` members are not actions but test seams, kept here at the
 * names their callers already use while their storage lives beside the code
 * they control.
 */
object ActionExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        GestureExecutor.tap(x, y, nodeId)

    suspend fun tapText(text: String, exact: Boolean = false): ActionResult =
        GestureExecutor.tapText(text, exact)

    suspend fun typeText(text: String, clearFirst: Boolean = false): ActionResult =
        TextEntryExecutor.typeText(text, clearFirst)

    suspend fun swipe(direction: String, distance: String = "medium"): ActionResult =
        GestureExecutor.swipe(direction, distance)

    fun openApp(packageName: String): ActionResult =
        AppIntentExecutor.openApp(packageName)

    fun pressKey(key: String): ActionResult =
        SystemKeyExecutor.pressKey(key)

    suspend fun waitForElement(
        text: String? = null,
        className: String? = null,
        timeoutMs: Int = 5000
    ): ActionResult =
        ScreenQueryExecutor.waitForElement(text, className, timeoutMs)

    suspend fun takeScreenshot(): ActionResult =
        ScreenshotExecutor.takeScreenshot()

    fun getInstalledApps(): List<Map<String, String>> =
        AppIntentExecutor.getInstalledApps()

    suspend fun scroll(direction: String, nodeId: String? = null): ActionResult =
        GestureExecutor.scroll(direction, nodeId)

    fun clipboardRead(): ActionResult =
        TextEntryExecutor.clipboardRead()

    fun clipboardWrite(text: String): ActionResult =
        TextEntryExecutor.clipboardWrite(text)

    suspend fun longPress(x: Int? = null, y: Int? = null, nodeId: String? = null, duration: Long = 500): ActionResult =
        GestureExecutor.longPress(x, y, nodeId, duration)

    suspend fun drag(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500): ActionResult =
        GestureExecutor.drag(startX, startY, endX, endY, duration)

    fun findNodes(text: String? = null, className: String? = null, clickable: Boolean? = null, limit: Int = 20): ActionResult =
        ScreenQueryExecutor.findNodes(text, className, clickable, limit)

    fun diffScreen(previousHash: String): ActionResult =
        ScreenQueryExecutor.diffScreen(previousHash)

    suspend fun pinch(x: Int, y: Int, scale: Float = 1.5f, duration: Long = 300): ActionResult =
        GestureExecutor.pinch(x, y, scale, duration)

    fun pinchScaleError(scale: Float): String? =
        GestureExecutor.pinchScaleError(scale)

    fun describeNode(nodeId: String): ActionResult =
        ScreenQueryExecutor.describeNode(nodeId)

    fun location(): ActionResult =
        DeviceServicesExecutor.location()

    /**
     * How long [DeviceServicesExecutor.sendSms] waits for the radio's send
     * report. `internal` so tests can shorten it — the same seam idea as
     * [com.hermesandroid.bridge.server.CommandDispatcher.batteryContext]. The
     * storage lives beside the send it bounds; this accessor keeps the seam at
     * the name its callers already use.
     */
    internal var smsSendReportTimeoutMs: Long
        get() = DeviceServicesExecutor.smsSendReportTimeoutMs
        set(value) {
            DeviceServicesExecutor.smsSendReportTimeoutMs = value
        }

    fun sendSms(to: String, body: String): ActionResult =
        DeviceServicesExecutor.sendSms(to, body)

    /**
     * The broadcast action one send's reports arrive on (review F15) — see
     * [DeviceServicesExecutor.smsSentReportAction] for why it carries a per-call
     * UUID. Internal for `ActionExecutorSendSmsTest`, which asserts the
     * uniqueness and the shape.
     */
    internal fun smsSentReportAction(callId: String): String =
        DeviceServicesExecutor.smsSentReportAction(callId)

    fun makeCall(number: String): ActionResult =
        DeviceServicesExecutor.makeCall(number)

    fun mediaControl(action: String): ActionResult =
        SystemKeyExecutor.mediaControl(action)

    fun searchContacts(query: String, limit: Int = 20): ActionResult =
        DeviceServicesExecutor.searchContacts(query, limit)

    fun sendIntent(action: String, dataUri: String? = null, extras: Map<String, String>? = null, packageOverride: String? = null): ActionResult =
        AppIntentExecutor.sendIntent(action, dataUri, extras, packageOverride)

    fun sendBroadcast(action: String, extras: Map<String, String>? = null): ActionResult =
        AppIntentExecutor.sendBroadcast(action, extras)

    fun screenHash(): ActionResult =
        ScreenQueryExecutor.screenHash()

    suspend fun readWidgets(): ActionResult =
        ScreenQueryExecutor.readWidgets()

    suspend fun speak(text: String, queue: Int = android.speech.tts.TextToSpeech.QUEUE_ADD): ActionResult =
        TtsExecutor.speak(text, queue)

    fun stopSpeaking(): ActionResult =
        TtsExecutor.stopSpeaking()

    fun shutdownTts() = TtsExecutor.shutdownTts()
}

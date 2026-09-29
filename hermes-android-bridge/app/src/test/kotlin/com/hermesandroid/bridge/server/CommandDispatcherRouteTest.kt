package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.bridgeTest
import com.hermesandroid.bridge.event.EventStore
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.notification.NotificationEntry
import com.hermesandroid.bridge.notification.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [CommandDispatcher.dispatch] routing, captured
 * before the dispatcher is split into domain handlers. They pin endpoint
 * contracts: status codes, response shapes, error messages, and the
 * notification-store privacy gating (removed notifications are only served
 * on explicit opt-in).
 *
 * /ping's response shape is pinned without a "version" key on purpose:
 * /ping is unauthenticated and version info would fingerprint the
 * deployment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherRouteTest {

    @Before
    fun setup() {
        // Routes such as /screen wrap work in withContext(Dispatchers.Main).
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NotificationStore.clear()
        EventStore.clear()
        EventStore.setStreaming(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        NotificationStore.clear()
        EventStore.clear()
        EventStore.setStreaming(false)
    }

    private suspend fun dispatch(
        method: String,
        path: String,
        params: JsonObject = JsonObject(),
        body: JsonObject = JsonObject(),
        authenticated: Boolean = true
    ): Pair<Any, Int> = CommandDispatcher.dispatch(method, path, params, body, authenticated)

    @Suppress("UNCHECKED_CAST")
    private fun map(result: Any): Map<String, Any> = result as Map<String, Any>

    @Test
    fun `ping reports status without a version key`() = bridgeTest {
        val (rawResult, status) = dispatch("GET", "/ping", authenticated = false)

        assertEquals(200, status)
        val result = map(rawResult)
        assertEquals("ok", result["status"])
        assertEquals(false, result["accessibilityService"])
        assertEquals(false, result["authenticated"])
        // No version fingerprinting on the unauthenticated endpoint.
        assertEquals(setOf("status", "accessibilityService", "authenticated"), result.keys)
    }

    @Test
    fun `ping echoes the authenticated flag`() = bridgeTest {
        val (rawResult, status) = dispatch("GET", "/ping", authenticated = true)

        assertEquals(200, status)
        assertEquals(true, map(rawResult)["authenticated"])
    }

    @Test
    fun `unknown command returns 404 with the method and path`() = bridgeTest {
        val (rawResult, status) = dispatch("GET", "/frobnicate")

        assertEquals(404, status)
        assertEquals("Unknown command: GET /frobnicate", map(rawResult)["error"])
    }

    @Test
    fun `open_app without a package is a 400`() = bridgeTest {
        val (rawResult, status) = dispatch("POST", "/open_app")

        assertEquals(400, status)
        assertEquals("Missing package", map(rawResult)["error"])
    }

    @Test
    fun `open_app without the accessibility service returns the failure result`() = bridgeTest {
        val body = JsonObject().apply { addProperty("package", "com.x") }

        val (rawResult, status) = dispatch("POST", "/open_app", body = body)

        assertEquals(200, status)
        val result = rawResult as ActionResult
        assertFalse(result.success)
        assertEquals("Accessibility service not running", result.message)
    }

    @Test
    fun `tap without the accessibility service returns the failure result`() = bridgeTest {
        val (rawResult, status) = dispatch("POST", "/tap")

        assertEquals(200, status)
        val result = rawResult as ActionResult
        assertFalse(result.success)
        assertEquals("Accessibility service not running", result.message)
    }

    @Test
    fun `screen returns an empty tree without the accessibility service`() = bridgeTest {
        val (rawResult, status) = dispatch("GET", "/screen")

        assertEquals(200, status)
        val result = map(rawResult)
        assertEquals(emptyList<Any>(), result["tree"])
        assertEquals(0, result["count"])
    }

    @Test
    fun `mic_start rejects a non-numeric duration`() = bridgeTest {
        val body = JsonObject().apply { addProperty("duration", "abc") }

        val (rawResult, status) = dispatch("POST", "/mic_start", body = body)

        assertEquals(400, status)
        assertEquals("duration must be an integer number of seconds", map(rawResult)["error"])
    }

    @Test
    fun `events returns an empty list by default`() = bridgeTest {
        val (rawResult, status) = dispatch("GET", "/events")

        assertEquals(200, status)
        val result = map(rawResult)
        assertEquals(0, result["count"])
        assertEquals(false, result["streaming"])
    }

    @Test
    fun `events stream toggle updates the streaming flag`() = bridgeTest {
        val body = JsonObject().apply { addProperty("enabled", true) }

        val (rawResult, status) = dispatch("POST", "/events/stream", body = body)

        assertEquals(200, status)
        val result = map(rawResult)
        assertEquals(true, result["success"])
        assertEquals(true, result["streaming"])

        val (listRaw, listStatus) = dispatch("GET", "/events")
        assertEquals(200, listStatus)
        assertEquals(true, map(listRaw)["streaming"])
    }

    private fun notificationEntry(key: String, timestamp: Long) = NotificationEntry(
        key = key,
        packageName = "com.example.app",
        title = "title-$key",
        text = "text-$key",
        subText = null,
        bigText = null,
        summaryText = null,
        category = null,
        timestamp = timestamp,
        isOngoing = false,
        isClearable = true,
    )

    private fun seedNotifications() {
        NotificationStore.clear()
        NotificationStore.add(notificationEntry("kept", 1000L))
        NotificationStore.add(notificationEntry("cleared", 2000L))
        NotificationStore.markRemoved("cleared")
    }

    @Test
    fun `notifications excludes removed entries by default`() = bridgeTest {
        seedNotifications()

        val (rawResult, status) = dispatch("GET", "/notifications")

        assertEquals(200, status)
        val result = map(rawResult)
        assertEquals(1, result["count"])
        assertEquals(false, result["listenerActive"])
    }

    @Test
    fun `notifications includes removed entries only on explicit opt-in`() = bridgeTest {
        seedNotifications()
        val params = JsonObject().apply { addProperty("include_removed", "true") }

        val (rawResult, status) = dispatch("GET", "/notifications", params = params)

        assertEquals(200, status)
        assertEquals(2, map(rawResult)["count"])
    }
}

package com.hermesandroid.bridge.executor

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.hermesandroid.bridge.bridgeTest
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
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
 * Characterization tests for [ActionExecutor] app launching
 * (openApp / getInstalledApps), captured before the executor split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorAppLaunchTest {

    private lateinit var mockService: BridgeAccessibilityService
    private lateinit var mockPm: PackageManager

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        mockPm = mockk(relaxed = true)
        every { mockService.packageManager } returns mockPm
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `openApp reports unknown package`() {
        every { mockPm.getLaunchIntentForPackage("com.unknown.app") } returns null

        val result = ActionExecutor.openApp("com.unknown.app")

        assertFalse(result.success)
        assertEquals("App not found: com.unknown.app", result.message)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `openApp launches with the new-task flag`() {
        every { mockPm.getLaunchIntentForPackage("com.foo") } returns Intent(Intent.ACTION_MAIN)

        val result = ActionExecutor.openApp("com.foo")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Opening com.foo", result.message)
        val intent = slot<Intent>()
        verify(exactly = 1) { mockService.startActivity(capture(intent)) }
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            intent.captured.flags and Intent.FLAG_ACTIVITY_NEW_TASK
        )
    }

    private fun launchable(pkg: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    @Test
    fun `getInstalledApps deduplicates by package and sorts by label`() {
        // "com.b" appears twice (two launcher activities) — must collapse to one.
        every { mockPm.queryIntentActivities(any(), any<Int>()) } returns listOf(
            launchable("com.b"), launchable("com.a"), launchable("com.b")
        )
        val labels = mapOf("com.b" to "Beta", "com.a" to "Alpha")
        every { mockPm.getApplicationLabel(any()) } answers {
            labels[(it.invocation.args[0] as ApplicationInfo).packageName] ?: "?"
        }

        val apps = ActionExecutor.getInstalledApps()

        assertEquals(listOf("com.a", "com.b"), apps.map { it["packageName"] })
        assertEquals(listOf("Alpha", "Beta"), apps.map { it["label"] })
    }

    @Test
    fun `getInstalledApps returns an empty list without the service`() {
        every { BridgeAccessibilityService.instance } returns null

        assertTrue(ActionExecutor.getInstalledApps().isEmpty())
    }
}

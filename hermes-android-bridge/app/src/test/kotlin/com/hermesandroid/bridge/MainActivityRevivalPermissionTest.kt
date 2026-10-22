package com.hermesandroid.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Switch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `com.termux.permission.RUN_COMMAND` is a dangerous permission Termux defines,
 * so the manifest entry alone never grants it. The revival switch used to just
 * write the pref, leaving it ON while every RUN_COMMAND was rejected (ported
 * from fix/review-relay-client e2ce41d).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityRevivalPermissionTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun prefs() = app.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)

    private fun revivalEnabled() = prefs().getBoolean("termux_revival_enabled", false)

    @Before
    fun setUp() {
        prefs().edit().putBoolean("termux_revival_enabled", false).commit()
    }

    @After
    fun tearDown() {
        prefs().edit().putBoolean("termux_revival_enabled", false).commit()
    }

    @Test
    fun `turning revival on without the permission requests it and does not enable yet`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val switch = activity.findViewById<Switch>(R.id.switchRevival)

        switch.isChecked = true

        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("enabling revival must request RUN_COMMAND", request)
        assertEquals(MainActivity.REQUEST_CODE_RUN_COMMAND, request!!.requestCode)
        assertArrayEquals(arrayOf(MainActivity.RUN_COMMAND_PERMISSION), request.requestedPermissions)
        assertFalse("not enabled before the user answers", revivalEnabled())
    }

    @Test
    fun `a denial turns the switch back off`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val switch = activity.findViewById<Switch>(R.id.switchRevival)
        switch.isChecked = true

        activity.onRequestPermissionsResult(
            MainActivity.REQUEST_CODE_RUN_COMMAND,
            arrayOf(MainActivity.RUN_COMMAND_PERMISSION),
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )

        assertFalse(switch.isChecked)
        assertFalse(revivalEnabled())
    }

    @Test
    fun `a grant enables revival`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<Switch>(R.id.switchRevival).isChecked = true

        activity.onRequestPermissionsResult(
            MainActivity.REQUEST_CODE_RUN_COMMAND,
            arrayOf(MainActivity.RUN_COMMAND_PERMISSION),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        assertTrue(revivalEnabled())
    }

    @Test
    fun `an already granted permission enables without asking`() {
        shadowOf(app).grantPermissions(MainActivity.RUN_COMMAND_PERMISSION)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<Switch>(R.id.switchRevival).isChecked = true

        assertNull(shadowOf(activity).lastRequestedPermission)
        assertTrue(revivalEnabled())
    }
}

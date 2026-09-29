package com.hermesandroid.bridge.client

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests for the Termux revival intent (review F4).
 *
 * `maybeFireTermuxRevival`/`fireTermuxRestart` — the prefs read, the intent
 * construction and the script path itself — had no coverage at all; only the
 * pure [RevivalPolicy] did. That is how a private deployment path shipped in a
 * public APK: nothing asserted anything about the intent.
 *
 * The intent is what Termux acts on, so a wrong action, component or extra key
 * fails silently on-device (Termux logs it, the bridge reports "firing Termux
 * revival…", nothing restarts). These pin all four extras and the target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TermuxRevivalIntentTest {

    @Test
    fun `the intent targets the Termux RUN_COMMAND service`() {
        val intent = RelayClient.termuxRevivalIntent()

        assertEquals("com.termux.RUN_COMMAND", intent.action)
        assertEquals(
            ComponentName("com.termux", "com.termux.app.RunCommandService"),
            intent.component,
        )
    }

    @Test
    fun `the script path is passed as an absolute path under the Termux home`() {
        val intent = RelayClient.termuxRevivalIntent()

        val path = intent.getStringExtra(RelayClient.EXTRA_RUN_COMMAND_PATH)
        assertTrue("RUN_COMMAND_PATH must be set", path != null)
        assertTrue("Termux requires an absolute path, got '$path'", path!!.startsWith("/"))
        assertTrue(
            "the path must live inside Termux's own home, got '$path'",
            path.startsWith("/data/data/com.termux/files/home/"),
        )
    }

    @Test
    fun `the default script name carries no personal deployment detail`() {
        val path = RelayClient.DEFAULT_REVIVAL_SCRIPT

        // The shipped value used to be `.termux/boot/start-zee.sh` — one
        // contributor's private stack launcher, in a public repo and a public
        // APK. Any other user enabling the switch fired RUN_COMMAND at a script
        // that did not exist on their device.
        assertFalse("the private stack name must not come back: $path", path.contains("zee"))
        assertEquals(
            "/data/data/com.termux/files/home/.termux/boot/start-hermes.sh",
            path,
        )
    }

    @Test
    fun `the intent runs in the background and opens no session`() {
        val intent = RelayClient.termuxRevivalIntent()

        // BACKGROUND=true: a revival must not steal the foreground from whatever
        // the user is doing. SESSION_ACTION=0: leave no Termux notification
        // behind, since nothing is watching for one.
        assertTrue(intent.getBooleanExtra(RelayClient.EXTRA_RUN_COMMAND_BACKGROUND, false))
        assertEquals(0, intent.getIntExtra(RelayClient.EXTRA_RUN_COMMAND_SESSION_ACTION, -1))
    }

    @Test
    fun `an explicit script path overrides the default`() {
        val intent = RelayClient.termuxRevivalIntent("/data/data/com.termux/files/home/restart.sh")

        assertEquals(
            "/data/data/com.termux/files/home/restart.sh",
            intent.getStringExtra(RelayClient.EXTRA_RUN_COMMAND_PATH),
        )
    }

    @Test
    fun `the default is what fireTermuxRestart would send`() {
        // Guards the seam: if the default argument ever stops being the constant,
        // the documented path and the real one diverge.
        assertEquals(
            RelayClient.DEFAULT_REVIVAL_SCRIPT,
            RelayClient.termuxRevivalIntent().getStringExtra(RelayClient.EXTRA_RUN_COMMAND_PATH),
        )
    }
}

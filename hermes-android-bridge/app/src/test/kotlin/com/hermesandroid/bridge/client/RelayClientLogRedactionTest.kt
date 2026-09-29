package com.hermesandroid.bridge.client

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Review F35: `handleMessage`'s catch logged `${e.message}`.
 *
 * That catch wraps the JSON parse, the `request_id` check and the `/mic_file`
 * stream — the failures `CommandDispatcher.dispatch` never sees, and so the ones
 * `errorBody()` was not already covering. A `JsonSyntaxException` from a malformed
 * command can quote the command, and for `/send_sms` the command contains the
 * recipient; a failure under `/mic_file` can carry a file path. AGENTS.md keeps
 * phone numbers, recipients and location out of logs as well as out of responses,
 * and logcat is not private: a bug report captures it, and any app holding
 * READ_LOGS on an older device can read it.
 *
 * F26 fixed the *response*; this fixes the log line beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientLogRedactionTest {

    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    private fun onlyLog(): ShadowLog.LogItem {
        val logs = ShadowLog.getLogsForTag("RelayClient")
        assertEquals("expected exactly one RelayClient log line, got $logs", 1, logs.size)
        return logs[0]
    }

    @Test
    fun `a message-handling failure logs the class and not the message`() {
        RelayClient.logMessageFailure(
            IllegalArgumentException("to=+15551234567 body=meet me at the usual place"),
        )

        val entry = onlyLog()
        assertEquals(Log.ERROR, entry.type)
        assertTrue(
            "the class name is what diagnoses it: ${entry.msg}",
            entry.msg.contains("IllegalArgumentException"),
        )
        assertFalse("the recipient leaked into logcat: ${entry.msg}", entry.msg.contains("5551234567"))
        assertFalse("the body leaked into logcat: ${entry.msg}", entry.msg.contains("usual place"))
    }

    @Test
    fun `the throwable is not attached, because its stack trace leads with the message`() {
        // `Log.e(tag, msg, tr)` appends `Log.getStackTraceString(tr)`, whose first
        // line is `class: message`. Passing the throwable would put the message back
        // into logcat no matter how the text is worded.
        RelayClient.logMessageFailure(
            java.io.FileNotFoundException("/storage/emulated/0/recording_+15551234567.wav"),
        )

        val entry = onlyLog()
        assertNull("the throwable's toString() would print the message", entry.throwable)
        assertFalse(entry.msg.contains("5551234567"))
        assertTrue(entry.msg.contains("FileNotFoundException"))
    }

    @Test
    fun `an exception with no message still logs something usable`() {
        RelayClient.logMessageFailure(RuntimeException())

        val entry = onlyLog()
        assertTrue(entry.msg.contains("RuntimeException"))
        assertFalse("no 'null' in the log line: ${entry.msg}", entry.msg.contains("null"))
    }

    @Test
    fun `a class with no simple name falls back without echoing the message`() {
        // errorBody()'s ifBlank branch: an anonymous class has an empty simpleName,
        // so the fully-qualified name is used. Neither may carry the message.
        val anonymous = object : IllegalStateException("clipboard held 5551234567") {}

        RelayClient.logMessageFailure(anonymous)

        val entry = onlyLog()
        assertFalse("the message leaked: ${entry.msg}", entry.msg.contains("5551234567"))
        assertFalse("the message leaked: ${entry.msg}", entry.msg.contains("clipboard"))
        assertTrue(
            "the fallback must still identify the class: ${entry.msg}",
            entry.msg.contains("RelayClientLogRedactionTest"),
        )
    }

    @Test
    fun `the logged name is the same one the caller is told`() {
        // F26 made the response body use CommandDispatcher.errorBody; the log must
        // not drift to a different rendering of the same cause.
        val cause = IllegalArgumentException("to=+15551234567")

        RelayClient.logMessageFailure(cause)

        val expected = com.hermesandroid.bridge.server.CommandDispatcher
            .errorBody(cause)
            .getValue("error")
        assertTrue(onlyLog().msg.contains(expected))
    }
}

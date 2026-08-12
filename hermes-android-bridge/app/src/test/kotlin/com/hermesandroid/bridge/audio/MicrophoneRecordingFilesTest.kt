package com.hermesandroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MicrophoneRecordingFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `retention keeps newest WAVs and ignores incomplete or unrelated files`() {
        val directory = temporaryFolder.newFolder("recordings")
        val recordings = (1..4).map { index ->
            directory.resolve("recording_$index.wav").apply {
                writeBytes(byteArrayOf(index.toByte()))
                assertTrue(setLastModified(index * 1_000L))
            }
        }
        val incomplete = directory.resolve("recording_5.wav.part").apply { writeText("partial") }
        val unrelated = directory.resolve("notes.txt").apply { writeText("keep") }

        val deleted = MicrophoneRecordingFiles.enforceRetention(directory, keepLast = 2)

        assertEquals(2, deleted)
        assertFalse(recordings[0].exists())
        assertFalse(recordings[1].exists())
        assertTrue(recordings[2].exists())
        assertTrue(recordings[3].exists())
        assertTrue(incomplete.exists())
        assertTrue(unrelated.exists())
    }
}

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

    @Test
    fun `interrupted part files are recovered as WAVs instead of deleted`() {
        val directory = temporaryFolder.newFolder("recover")
        val pending = PendingMicrophoneRecording(
            temporaryFile = directory.resolve("recording_20260101_000000_000.wav.part"),
            completedFile = directory.resolve("recording_20260101_000000_000.wav"),
        )
        // Process death: audio on disk, header still says 0 data bytes.
        WavFileWriter(pending, sampleRate = 16_000).apply {
            write(shortArrayOf(10, 20, 30, 40), 4)
            close()
        }
        assertTrue(pending.temporaryFile.setLastModified(5_000L))
        val headerOnly = directory.resolve("recording_20260102_000000_000.wav.part")
        WavFileWriter(
            PendingMicrophoneRecording(headerOnly, directory.resolve("recording_20260102_000000_000.wav")),
            sampleRate = 16_000,
        ).close()
        val garbage = directory.resolve("recording_20260103_000000_000.wav.part")
            .apply { writeText("not a wav") }

        val recovered = MicrophoneRecordingFiles.recoverIncomplete(directory)

        assertEquals(listOf(pending.completedFile), recovered)
        assertFalse(pending.temporaryFile.exists())
        assertTrue(pending.completedFile.exists())
        assertEquals(44L + 8L, pending.completedFile.length())
        assertEquals(5_000L, pending.completedFile.lastModified())
        val bytes = pending.completedFile.readBytes()
        assertEquals(8, (bytes[40].toInt() and 0xff) or ((bytes[41].toInt() and 0xff) shl 8))
        // Nothing salvageable: removed rather than left to accumulate.
        assertFalse(headerOnly.exists())
        assertFalse(garbage.exists())
        assertFalse(directory.resolve("recording_20260102_000000_000.wav").exists())
        assertFalse(directory.resolve("recording_20260103_000000_000.wav").exists())
    }

    @Test
    fun `recovery never overwrites an existing completed recording`() {
        val directory = temporaryFolder.newFolder("recover_collision")
        val completed = directory.resolve("recording_1.wav").apply { writeText("keep me") }
        val part = directory.resolve("recording_1.wav.part")
        WavFileWriter(PendingMicrophoneRecording(part, directory.resolve("unused.wav")), 16_000).apply {
            write(shortArrayOf(1, 2), 2)
            close()
        }

        val recovered = MicrophoneRecordingFiles.recoverIncomplete(directory)

        assertTrue(recovered.isEmpty())
        assertEquals("keep me", completed.readText())
        assertFalse(part.exists())
    }
}

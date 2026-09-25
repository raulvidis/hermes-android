package com.hermesandroid.bridge.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finish publishes valid PCM16 WAV and removes temporary file`() {
        val directory = temporaryFolder.newFolder("audio")
        val pending = PendingMicrophoneRecording(
            temporaryFile = directory.resolve("recording.wav.part"),
            completedFile = directory.resolve("recording.wav"),
        )
        val samples = shortArrayOf(0, 32767, -32768, -1)

        val completed = WavFileWriter(pending, sampleRate = 16_000).use { writer ->
            writer.write(samples, samples.size)
            writer.finish()
        }

        val bytes = completed.readBytes()
        assertFalse(pending.temporaryFile.exists())
        assertTrue(completed.exists())
        assertEquals(44 + samples.size * 2, bytes.size)
        assertArrayEquals("RIFF".toByteArray(), bytes.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), bytes.copyOfRange(8, 12))
        assertArrayEquals("data".toByteArray(), bytes.copyOfRange(36, 40))
        assertEquals(bytes.size - 8, littleEndianInt(bytes, 4))
        assertEquals(samples.size * 2, littleEndianInt(bytes, 40))
        assertArrayEquals(
            byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1),
            bytes.copyOfRange(44, bytes.size),
        )
    }

    @Test
    fun `abort removes incomplete recording`() {
        val directory = temporaryFolder.newFolder("aborted")
        val pending = PendingMicrophoneRecording(
            temporaryFile = directory.resolve("recording.wav.part"),
            completedFile = directory.resolve("recording.wav"),
        )
        val writer = WavFileWriter(pending, sampleRate = 16_000)
        writer.write(shortArrayOf(1, 2), 2)

        writer.abort()

        assertFalse(pending.temporaryFile.exists())
        assertFalse(pending.completedFile.exists())
    }

    @Test
    fun `repairHeader fixes sizes of an unfinalized WAV and drops a torn sample`() {
        val directory = temporaryFolder.newFolder("repair")
        val pending = PendingMicrophoneRecording(
            temporaryFile = directory.resolve("recording.wav.part"),
            completedFile = directory.resolve("recording.wav"),
        )
        // Simulate process death: samples flushed, header never finalized.
        val writer = WavFileWriter(pending, sampleRate = 16_000)
        writer.write(shortArrayOf(1, 2, 3), 3)
        writer.close()
        pending.temporaryFile.appendBytes(byteArrayOf(0x7f)) // half a sample
        assertEquals(0, littleEndianInt(pending.temporaryFile.readBytes(), 40))

        val dataSize = WavFileWriter.repairHeader(pending.temporaryFile)

        val bytes = pending.temporaryFile.readBytes()
        assertEquals(6L, dataSize)
        assertEquals(44 + 6, bytes.size)
        assertEquals(bytes.size - 8, littleEndianInt(bytes, 4))
        assertEquals(6, littleEndianInt(bytes, 40))
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), bytes.copyOfRange(44, bytes.size))
    }

    @Test
    fun `repairHeader rejects files that are not our WAV layout`() {
        val directory = temporaryFolder.newFolder("repair_invalid")
        val tooShort = directory.resolve("short.wav.part").apply { writeBytes(ByteArray(10)) }
        val notWav = directory.resolve("junk.wav.part").apply { writeBytes(ByteArray(100) { 7 }) }

        assertEquals(-1L, WavFileWriter.repairHeader(tooShort))
        assertEquals(-1L, WavFileWriter.repairHeader(notWav))
        assertEquals(100L, notWav.length())
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

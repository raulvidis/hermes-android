package com.hermesandroid.bridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoiseVideoFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `retention keeps only newest completed noise videos`() {
        val directory = temporaryFolder.newFolder("noise-videos")
        val videos = (1..4).map { index ->
            directory.resolve("noise_$index.mp4").apply {
                writeBytes(byteArrayOf(index.toByte()))
                assertTrue(setLastModified(index * 1_000L))
            }
        }
        val pending = directory.resolve("noise_partial.mp4.part").apply { writeText("partial") }
        val unrelated = directory.resolve("notes.txt").apply { writeText("keep") }

        val deleted = NoiseVideoFiles.enforceRetention(directory, keepLast = 2)

        assertEquals(2, deleted)
        assertFalse(videos[0].exists())
        assertFalse(videos[1].exists())
        assertTrue(videos[2].exists())
        assertTrue(videos[3].exists())
        assertTrue(pending.exists())
        assertTrue(unrelated.exists())
    }
}

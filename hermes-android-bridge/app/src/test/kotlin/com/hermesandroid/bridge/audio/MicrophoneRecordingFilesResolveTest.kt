package com.hermesandroid.bridge.audio

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Security regression tests for MicrophoneRecordingFiles.resolve() (#99):
 * the server-side traversal/symlink defense backing /mic_file. Only
 * generated WAV basenames inside the recordings directory may resolve;
 * every escape vector must return null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MicrophoneRecordingFilesResolveTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val directory: File
        get() = MicrophoneRecordingFiles.directory(context)

    @Before
    fun cleanDirectory() {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun createRecording(name: String, lastModified: Long): File =
        File(directory, name).apply {
            writeBytes("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray())
            setLastModified(lastModified)
        }

    @Test
    fun `blank name resolves to the latest completed recording`() {
        val older = createRecording("recording_20260101_000000_000.wav", 1_000L)
        val newer = createRecording("recording_20260102_000000_000.wav", 2_000L)

        assertEquals(newer.canonicalFile, MicrophoneRecordingFiles.resolve(context, null))
        assertEquals(newer.canonicalFile, MicrophoneRecordingFiles.resolve(context, ""))
        assertEquals(newer.canonicalFile, MicrophoneRecordingFiles.resolve(context, "   "))
        assertNotNull(older) // silence unused warning; kept for ordering setup
    }

    @Test
    fun `existing generated name resolves`() {
        val recording = createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertEquals(
            recording.canonicalFile,
            MicrophoneRecordingFiles.resolve(context, "recording_20260102_120000_000.wav"),
        )
    }

    @Test
    fun `unknown but valid-looking name returns null`() {
        createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertNull(MicrophoneRecordingFiles.resolve(context, "recording_19990101_000000_000.wav"))
    }

    @Test
    fun `traversal sequences are rejected`() {
        createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertNull(MicrophoneRecordingFiles.resolve(context, "../x.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "../../etc/passwd.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "a/../b.wav"))
    }

    @Test
    fun `url-encoded traversal variants are rejected`() {
        // % is outside the safe-name alphabet, so encoded traversal never
        // reaches the filesystem layer.
        assertNull(MicrophoneRecordingFiles.resolve(context, "%2e%2e.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "%2e%2e%2fx.wav"))
    }

    @Test
    fun `absolute paths are rejected`() {
        assertNull(MicrophoneRecordingFiles.resolve(context, "/etc/passwd.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, directory.absolutePath + "/x.wav"))
    }

    @Test
    fun `non-wav and malformed names are rejected`() {
        createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertNull(MicrophoneRecordingFiles.resolve(context, "notes.txt"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "recording.wav.part"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "REC.WAV"))
        assertNull(MicrophoneRecordingFiles.resolve(context, ".wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "naïve.wav"))
    }

    @Test
    fun `symlink escaping the recordings directory is rejected`() {
        val outside = File(context.cacheDir, "outside.wav").apply { writeText("secret") }
        val link = File(directory, "evil.wav")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertNull(MicrophoneRecordingFiles.resolve(context, "evil.wav"))
    }

    @Test
    fun `symlink with a generated-looking name escaping the directory is rejected`() {
        // The name passes the regex and is a plain basename, so only the
        // canonical-path parent check stands between it and the target.
        val outsideDir = File(context.cacheDir, "elsewhere").apply { mkdirs() }
        val outside = File(outsideDir, "recording_20260102_120000_000.wav").apply { writeText("secret") }
        val link = File(directory, "recording_20260102_120000_000.wav")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertNull(MicrophoneRecordingFiles.resolve(context, "recording_20260102_120000_000.wav"))
    }

    @Test
    fun `symlink into a subdirectory of the recordings directory is rejected`() {
        val nested = File(directory, "nested").apply { mkdirs() }
        val target = createRecording("recording_20260102_120000_000.wav", 1_000L)
        val moved = File(nested, target.name)
        assertTrue(target.renameTo(moved))
        val link = File(directory, "nested_alias.wav")
        Files.createSymbolicLink(link.toPath(), moved.toPath())

        assertNull(MicrophoneRecordingFiles.resolve(context, "nested_alias.wav"))
        moved.delete()
    }

    @Test
    fun `symlink to an incomplete part file is rejected`() {
        // Canonical name ends in .wav.part, so the post-canonicalization name
        // check must refuse it even though the requested name is valid.
        val part = File(directory, "recording_20260102_120000_000.wav.part").apply { writeText("partial") }
        val link = File(directory, "sneaky.wav")
        Files.createSymbolicLink(link.toPath(), part.toPath())

        assertNull(MicrophoneRecordingFiles.resolve(context, "sneaky.wav"))
    }

    @Test
    fun `directory with a wav name is rejected`() {
        File(directory, "folder.wav").mkdirs()

        assertNull(MicrophoneRecordingFiles.resolve(context, "folder.wav"))
    }

    @Test
    fun `dot-prefixed names that pass the regex do not traverse`() {
        // "..wav" and "...wav" match the safe alphabet but are literal
        // basenames, never parent references.
        File(context.cacheDir, "x.wav").writeText("outside")

        assertNull(MicrophoneRecordingFiles.resolve(context, "..wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "...wav"))
    }

    @Test
    fun `symlink staying inside the recordings directory resolves`() {
        val recording = createRecording("recording_20260102_120000_000.wav", 1_000L)
        val link = File(directory, "alias.wav")
        Files.createSymbolicLink(link.toPath(), recording.toPath())

        assertEquals(recording.canonicalFile, MicrophoneRecordingFiles.resolve(context, "alias.wav"))
    }
}

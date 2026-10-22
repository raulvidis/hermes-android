package com.hermesandroid.bridge.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class PendingMicrophoneRecording(
    val temporaryFile: File,
    val completedFile: File,
)

/** Owns the one canonical location and naming policy for microphone recordings. */
internal object MicrophoneRecordingFiles {
    internal const val MAX_COMPLETED_RECORDINGS = 10
    private const val TAG = "MicRecordingFiles"
    private const val DIRECTORY_NAME = "cradata_audio"
    private val safeName = Regex("[A-Za-z0-9._-]+\\.wav")
    private val safeIncompleteName = Regex("[A-Za-z0-9._-]+\\.wav\\.part")

    fun directory(context: Context): File {
        val parent = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(parent, DIRECTORY_NAME).also { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Could not create microphone recording directory")
            }
        }
    }

    fun createPending(context: Context, now: Date = Date()): PendingMicrophoneRecording {
        val directory = directory(context)
        // A leftover .part means the process died mid-recording. Salvage the
        // audio instead of deleting it; only one session records at a time,
        // so no .part can belong to a live writer here.
        val recovered = recoverIncomplete(directory)
        if (recovered.isNotEmpty()) {
            Log.i(TAG, "Recovered ${recovered.size} interrupted microphone recording(s)")
            enforceRetention(directory, MAX_COMPLETED_RECORDINGS)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(now)
        var suffix = 0
        var completed: File
        var temporary: File
        do {
            val suffixText = if (suffix == 0) "" else "_$suffix"
            completed = File(directory, "recording_${timestamp}${suffixText}.wav")
            temporary = File(directory, "${completed.name}.part")
            suffix += 1
        } while (completed.exists() || temporary.exists())

        return PendingMicrophoneRecording(
            temporaryFile = temporary,
            completedFile = completed,
        )
    }

    /**
     * Turns interrupted `.part` recordings in [directory] into playable WAVs:
     * the header sizes are rewritten from the file length (whole samples
     * only) and the file is renamed to its final name, keeping its original
     * modification time so retention order stays chronological. Parts that
     * are empty, not our WAV layout, symlinks, or whose final name already
     * exists are deleted. Returns the recovered files.
     */
    internal fun recoverIncomplete(directory: File): List<File> {
        val recovered = mutableListOf<File>()
        directory.listFiles()
            ?.filter { safeIncompleteName.matches(it.name) }
            ?.forEach { part ->
                if (Files.isSymbolicLink(part.toPath()) || !part.isFile) {
                    part.delete()
                    return@forEach
                }
                val target = File(directory, part.name.removeSuffix(".part"))
                val modifiedAt = part.lastModified()
                val dataSize = runCatching { WavFileWriter.repairHeader(part) }.getOrDefault(-1L)
                if (dataSize > 0L && !target.exists() && part.renameTo(target)) {
                    if (modifiedAt > 0L) target.setLastModified(modifiedAt)
                    recovered += target
                } else {
                    part.delete()
                }
            }
        return recovered
    }

    fun listCompleted(context: Context): List<File> = listCompleted(directory(context))

    fun latest(context: Context): File? = listCompleted(context).firstOrNull()

    /** Keep storage bounded while never touching incomplete or unrelated files. */
    fun enforceRetention(context: Context): Int =
        enforceRetention(directory(context), MAX_COMPLETED_RECORDINGS)

    internal fun enforceRetention(directory: File, keepLast: Int): Int {
        require(keepLast >= 1) { "keepLast must be positive" }
        return listCompleted(directory)
            .drop(keepLast)
            .count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    /** Resolve only generated WAV basenames; paths and traversal sequences are rejected. */
    fun resolve(context: Context, requestedName: String?): File? {
        if (requestedName.isNullOrBlank()) return latest(context)
        if (!safeName.matches(requestedName) || File(requestedName).name != requestedName) return null

        val directory = directory(context).canonicalFile
        val candidate = File(directory, requestedName).canonicalFile
        return candidate.takeIf {
            it.parentFile == directory && it.isFile && safeName.matches(it.name)
        }
    }

    private fun listCompleted(directory: File): List<File> =
        directory
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && safeName.matches(it.name) }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.name },
            )
            ?.toList()
            ?: emptyList()
}

package com.hermesandroid.bridge.media

import android.content.Context
import android.os.Environment
import java.io.File

/** Private, bounded storage for clips produced by the opt-in noise watcher. */
object NoiseVideoFiles {
    const val MAX_COMPLETED_VIDEOS = 10
    private const val DIRECTORY_NAME = "cradata_noise_videos"

    fun directory(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(base, DIRECTORY_NAME).apply {
            mkdirs()
            setReadable(false, false)
            setWritable(false, false)
            setReadable(true, true)
            setWritable(true, true)
        }
    }

    fun createPending(context: Context): PendingVideo {
        val dir = directory(context)
        val stem = "noise_${System.currentTimeMillis()}"
        return PendingVideo(
            pendingFile = File(dir, "$stem.mp4.part"),
            completedFile = File(dir, "$stem.mp4"),
        )
    }

    fun listCompleted(context: Context): List<File> =
        listCompleted(directory(context))

    internal fun listCompleted(directory: File): List<File> =
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("noise_") && file.extension == "mp4"
        }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun resolve(context: Context, requestedName: String?): File? {
        val files = listCompleted(context)
        val file = if (requestedName.isNullOrBlank()) {
            files.firstOrNull()
        } else if (
            requestedName != File(requestedName).name ||
            !requestedName.startsWith("noise_") ||
            !requestedName.endsWith(".mp4")
        ) {
            null
        } else {
            files.firstOrNull { it.name == requestedName }
        }
        return file?.takeIf { it.canonicalFile.parentFile == directory(context).canonicalFile }
    }

    fun enforceRetention(context: Context) {
        enforceRetention(directory(context), MAX_COMPLETED_VIDEOS)
    }

    internal fun enforceRetention(directory: File, keepLast: Int): Int {
        require(keepLast >= 0)
        val stale = listCompleted(directory).drop(keepLast)
        stale.forEach { it.delete() }
        directory.listFiles { file -> file.name.endsWith(".mp4.part") }
            ?.forEach { pending ->
                if (System.currentTimeMillis() - pending.lastModified() > 60 * 60 * 1000L) {
                    pending.delete()
                }
            }
        return stale.count { !it.exists() }
    }

    data class PendingVideo(
        val pendingFile: File,
        val completedFile: File,
    )
}

package com.hermesandroid.bridge.audio

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Streams PCM16 samples into a temporary WAV and atomically publishes it on finish. */
internal class WavFileWriter(
    private val pending: PendingMicrophoneRecording,
    private val sampleRate: Int,
) : Closeable {
    companion object {
        internal const val HEADER_SIZE = 44

        /** Largest even PCM16 data size that still fits the 32-bit RIFF size field. */
        private const val MAX_DATA_SIZE = 4_294_967_258L // (0xffff_ffff - 36) rounded down to even

        /**
         * Repairs a WAV this writer started but never finalized (e.g. the
         * process died mid-recording): the header still claims a 0-byte data
         * chunk. Truncates the payload to whole PCM16 samples and rewrites the
         * RIFF and data sizes from the file length.
         *
         * Returns the repaired data size in bytes, or -1 if [file] does not
         * start with the header layout this writer produces.
         */
        internal fun repairHeader(file: File): Long {
            return RandomAccessFile(file, "rw").use { raf ->
                val length = raf.length()
                if (length < HEADER_SIZE) return -1L
                val header = ByteArray(HEADER_SIZE)
                raf.seek(0L)
                raf.readFully(header)
                if (!header.hasAscii(0, "RIFF") ||
                    !header.hasAscii(8, "WAVE") ||
                    !header.hasAscii(36, "data")
                ) {
                    return -1L
                }
                val payload = length - HEADER_SIZE
                val dataSize = minOf(payload - (payload % 2L), MAX_DATA_SIZE)
                raf.setLength(HEADER_SIZE + dataSize)
                raf.seek(4L)
                writeIntLittleEndian(raf, 36L + dataSize)
                raf.seek(40L)
                writeIntLittleEndian(raf, dataSize)
                raf.fd.sync()
                dataSize
            }
        }

        private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
            text.indices.all { index -> this[offset + index] == text[index].code.toByte() }

        private fun writeIntLittleEndian(file: RandomAccessFile, value: Long) {
            for (byteIndex in 0 until 4) {
                file.write(((value ushr (byteIndex * 8)) and 0xff).toInt())
            }
        }
    }

    private var output: FileOutputStream? = FileOutputStream(pending.temporaryFile)
    private var finished = false
    private var byteBuffer = ByteArray(0)

    var totalSamples: Long = 0L
        private set

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        output?.write(buildHeader(dataSize = 0L))
    }

    fun write(samples: ShortArray, count: Int) {
        require(count in 0..samples.size) { "Invalid sample count" }
        check(!finished) { "WAV is already finalized" }

        val byteCount = count * 2
        if (byteBuffer.size < byteCount) byteBuffer = ByteArray(byteCount)
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            byteBuffer[index * 2] = (sample and 0xff).toByte()
            byteBuffer[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
        output?.write(byteBuffer, 0, byteCount)
        totalSamples += count
    }

    fun finish(): File {
        if (finished) return pending.completedFile
        val dataSize = totalSamples * 2L
        require(dataSize <= 0xffff_ffffL - 36L) { "Recording exceeds WAV size limit" }

        output?.flush()
        output?.fd?.sync()
        output?.close()
        output = null

        RandomAccessFile(pending.temporaryFile, "rw").use { file ->
            file.seek(4L)
            writeLittleEndianInt(file, 36L + dataSize)
            file.seek(40L)
            writeLittleEndianInt(file, dataSize)
            file.fd.sync()
        }

        if (pending.completedFile.exists()) {
            throw IllegalStateException("Completed recording already exists")
        }
        if (!pending.temporaryFile.renameTo(pending.completedFile)) {
            throw IllegalStateException("Could not finalize microphone recording")
        }
        finished = true
        return pending.completedFile
    }

    fun abort() {
        close()
        pending.temporaryFile.delete()
    }

    override fun close() {
        runCatching { output?.close() }
        output = null
    }

    private fun buildHeader(dataSize: Long): ByteArray = ByteArray(44).also { header ->
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, destinationOffset = 0)
        writeLittleEndianInt(header, 4, 36L + dataSize)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, destinationOffset = 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, destinationOffset = 12)
        writeLittleEndianInt(header, 16, 16L)
        writeLittleEndianShort(header, 20, 1)
        writeLittleEndianShort(header, 22, 1)
        writeLittleEndianInt(header, 24, sampleRate.toLong())
        writeLittleEndianInt(header, 28, sampleRate.toLong() * 2L)
        writeLittleEndianShort(header, 32, 2)
        writeLittleEndianShort(header, 34, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, destinationOffset = 36)
        writeLittleEndianInt(header, 40, dataSize)
    }

    private fun writeLittleEndianInt(buffer: ByteArray, offset: Int, value: Long) {
        for (byteIndex in 0 until 4) {
            buffer[offset + byteIndex] = ((value ushr (byteIndex * 8)) and 0xff).toByte()
        }
    }

    private fun writeLittleEndianShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeLittleEndianInt(file: RandomAccessFile, value: Long) {
        for (byteIndex in 0 until 4) {
            file.write(((value ushr (byteIndex * 8)) and 0xff).toInt())
        }
    }
}

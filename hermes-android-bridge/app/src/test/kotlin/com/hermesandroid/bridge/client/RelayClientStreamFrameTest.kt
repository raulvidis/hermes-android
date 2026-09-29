package com.hermesandroid.bridge.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the binary framing RelayClient streams microphone
 * recordings over the WebSocket with (GET /mic_file).
 *
 * Frame layout: [2-byte big-endian length of request_id][request_id UTF-8
 * bytes][payload bytes]. The Python relay parses this prefix to route each
 * chunk to the right waiting download, so:
 *  - the length prefix must be BIG-ENDIAN (a little-endian prefix decodes as
 *    a garbage length on any other platform);
 *  - the length must be the UTF-8 BYTE count, not the Kotlin String length —
 *    multibyte ids differ between the two;
 *  - request ids beyond 128 bytes are refused, so the prefix can never
 *    describe a frame it cannot address and a hostile id cannot grow frames
 *    without bound.
 */
class RelayClientStreamFrameTest {

    private fun frame(requestId: String, payload: ByteArray, length: Int = payload.size) =
        RelayClient.buildStreamFrame(requestId, payload, length)

    /** Parses a frame back the way the receiving side does: prefix, id, payload. */
    private fun parse(bytes: ByteArray): Pair<String, ByteArray> {
        val idLen = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        val id = String(bytes, 2, idLen, Charsets.UTF_8)
        val payload = bytes.copyOfRange(2 + idLen, bytes.size)
        return id to payload
    }

    @Test
    fun `frame carries the request id behind a big-endian length prefix`() {
        val bytes = frame("req-42", "hello".toByteArray()).toByteArray()

        // 0x00 0x06 — big-endian: the high byte is first.
        assertEquals(0x00, bytes[0].toInt() and 0xff)
        assertEquals(0x06, bytes[1].toInt() and 0xff)

        val (id, payload) = parse(bytes)
        assertEquals("req-42", id)
        assertArrayEquals("hello".toByteArray(), payload)
    }

    @Test
    fun `the length prefix counts UTF-8 bytes, not characters`() {
        // 5 characters, 8 UTF-8 bytes (three two-byte chars).
        val id = "ünïcö"
        assertEquals(8, id.toByteArray(Charsets.UTF_8).size)
        assertEquals(5, id.length)

        val (parsedId, _) = parse(frame(id, ByteArray(0)).toByteArray())
        assertEquals(id, parsedId)
    }

    @Test
    fun `a 128-byte request id is the accepted maximum`() {
        val id = "a".repeat(128)
        val bytes = frame(id, "x".toByteArray()).toByteArray()

        assertEquals(0x00, bytes[0].toInt() and 0xff)
        assertEquals(0x80, bytes[1].toInt() and 0xff)
        assertEquals(id, parse(bytes).first)
    }

    @Test
    fun `request ids beyond 128 bytes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            frame("a".repeat(129), ByteArray(0))
        }
    }

    @Test
    fun `an empty payload frames as header and id only`() {
        val bytes = frame("r", ByteArray(0)).toByteArray()
        assertEquals("2 header bytes + 1 id byte", 3, bytes.size)
        assertArrayEquals(ByteArray(0), parse(bytes).second)
    }

    @Test
    fun `a payload length must fit the buffer`() {
        val payload = ByteArray(4)

        assertThrows(IllegalArgumentException::class.java) { frame("r", payload, 5) }
        assertThrows(IllegalArgumentException::class.java) { frame("r", payload, -1) }
    }

    @Test
    fun `a shorter declared length frames only the payload prefix`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = frame("r", payload, 2).toByteArray()

        assertArrayEquals(byteArrayOf(1, 2), parse(bytes).second)
    }

    @Test
    fun `large payloads round-trip byte for byte`() {
        // The stream loop sends 64 KiB chunks; pin that a full chunk (and
        // then some) survives framing without truncation or padding.
        val payload = ByteArray(100_000) { (it % 251).toByte() }

        val (id, framed) = parse(frame("chunk-1", payload).toByteArray())
        assertEquals("chunk-1", id)
        assertArrayEquals(payload, framed)
        assertTrue("framed payload must keep its full length", framed.size == 100_000)
    }
}

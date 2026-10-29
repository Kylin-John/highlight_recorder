package com.highlightrecorder.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingBufferTest {

    private fun feed(buffer: AudioRingBuffer, seconds: Int, startPtsUs: Long = 0L) {
        // AAC 帧约 21.3ms (1024 samples @ 48kHz)
        val frameUs = 21_333L
        val total = (seconds * 1_000_000L) / frameUs
        for (i in 0 until total) {
            buffer.onPacket(EncodedPacket(ByteArray(200), startPtsUs + i * frameUs))
        }
    }

    @Test
    fun `超容量音频帧被逐出`() {
        val buf = AudioRingBuffer(capacityUs = 3_000_000)
        feed(buf, seconds = 10)
        // 10s @ ~21.3ms/帧 ≈ 469 帧;保留 3s 应 ≈ 141 帧
        assertTrue("帧数 ${buf.packetCount}", buf.packetCount in 100..200)
    }

    @Test
    fun `按起点截断`() {
        val buf = AudioRingBuffer(capacityUs = 10_000_000)
        feed(buf, seconds = 5)
        val snap = buf.snapshot(fromPtsUs = 3_000_000)
        assertTrue(snap.isNotEmpty())
        assertTrue(snap.all { it.ptsUs >= 3_000_000 })
        assertTrue(snap.last().ptsUs - snap.first().ptsUs <= 2_000_000)
    }

    @Test
    fun `按视频尾部截断且快照不随追加或清空改变`() {
        val buf = AudioRingBuffer(10_000_000)
        feed(buf, seconds = 5)
        val snap = buf.snapshot(fromPtsUs = 2_000_000, toPtsUs = 3_000_000)
        val pts = snap.map { it.ptsUs }
        feed(buf, seconds = 2, startPtsUs = 5_000_000)
        buf.clear()
        assertTrue(snap.isNotEmpty())
        assertTrue(snap.all { it.ptsUs in 2_000_000L..3_000_000L })
        assertEquals(pts, snap.map { it.ptsUs })
    }
}

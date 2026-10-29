package com.highlightrecorder.buffer

import com.highlightrecorder.data.RecordingSettings
import com.highlightrecorder.data.SaveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSaveSessionTest {
    private val second = 1_000_000L
    private val window = 180 * second
    private val video = RingSegmentBuffer(capacityUs = window + 2 * second)
    private val audio = AudioRingBuffer(capacityUs = window + 2 * second)
    private val session = ClipSaveSession(video, audio)

    // 每秒一个 IDR、每半秒一帧;包载荷很小,不模拟额外编码负载。
    private fun feed(fromHalfSecond: Int, throughHalfSecond: Int) {
        for (i in fromHalfSecond..throughHalfSecond) {
            val pts = i * second / 2
            video.onPacket(EncodedPacket(byteArrayOf(1), pts, isKeyFrame = i % 2 == 0))
            audio.onPacket(EncodedPacket(byteArrayOf(2), pts))
        }
    }

    private fun capture(mode: SaveMode = SaveMode.CONTINUATION) = session.snapshot(window, mode)!!

    @Test
    fun `默认模式每次仍保存最近窗口`() {
        assertEquals(SaveMode.LAST_WINDOW, RecordingSettings().saveMode)
        feed(0, 600) // 5:00
        val first = capture(RecordingSettings().saveMode)
        assertEquals(120 * second, first.video.first().startPtsUs)
        assertEquals(300 * second, first.endPtsUs)
        session.onSaved(first)
        feed(601, 720) // 6:00
        val next = capture(RecordingSettings().saveMode)
        assertEquals(180 * second, next.video.first().startPtsUs)
        assertEquals(360 * second, next.endPtsUs)
    }

    @Test
    fun `接续首次保存窗口再次保存从成功边界向前对齐 IDR`() {
        feed(0, 601) // 点击在 5:00.5,不在 IDR 上
        val first = capture()
        assertEquals(120 * second, first.video.first().startPtsUs)
        assertEquals(300 * second + second / 2, first.endPtsUs)
        session.onSaved(first)
        feed(602, 721)
        val next = capture()
        assertEquals(300 * second, next.video.first().startPtsUs)
        assertEquals(360 * second + second / 2, next.endPtsUs)
        assertTrue(next.video.first().packets.first().isKeyFrame)
        assertTrue(first.endPtsUs - next.video.first().startPtsUs <= second)
    }

    @Test
    fun `接续间隔超过窗口只保存仍保留的最近窗口`() {
        feed(0, 600)
        session.onSaved(capture())
        feed(601, 1200) // 10:00
        val next = capture()
        assertEquals(420 * second, next.video.first().startPtsUs)
        assertEquals(600 * second, next.endPtsUs)
    }

    @Test
    fun `导出期间写入不改变旧快照且下次包含这段画面和声音`() {
        feed(0, 601)
        val exporting = capture()
        val oldTail = exporting.video.last()
        val oldPackets = oldTail.packets.toList()
        val oldAudio = exporting.audio.toList()
        val openSegmentAppendPts = 300 * second + 3 * second / 4
        video.onPacket(EncodedPacket(byteArrayOf(3), openSegmentAppendPts))
        audio.onPacket(EncodedPacket(byteArrayOf(4), openSegmentAppendPts))
        feed(602, 617) // 模拟耗时 8 秒的导出,继续写入后续分片
        assertEquals(oldPackets, oldTail.packets)
        assertEquals(300 * second + second / 2, exporting.endPtsUs)
        assertEquals(exporting.endPtsUs, oldTail.endPtsUs)
        assertEquals(oldAudio, exporting.audio)

        session.onSaved(exporting) // 5:08.5 完成,提交的仍是 5:00.5
        val next = capture()
        val duringExport = listOf(openSegmentAppendPts) + (602..617).map { it * second / 2 }
        val videoPts = next.video.flatMap { it.packets }.map { it.ptsUs }
        val audioPts = next.audio.map { it.ptsUs }
        assertTrue(videoPts.containsAll(duringExport))
        assertTrue(audioPts.containsAll(duringExport))
        assertEquals(300 * second, next.video.first().startPtsUs)
        assertSame(oldTail.packets.first().data, next.video.first().packets.first().data)
    }

    @Test
    fun `失败重试保留上次成功边界而非失败请求的尾部`() {
        feed(0, 600)
        session.onSaved(capture())
        feed(601, 720)
        val failed = capture()
        // 写文件抛错时不会调用 onSaved;后续重试仍从 5:00 开始。
        feed(721, 736)
        val retry = capture()
        assertEquals(300 * second, retry.video.first().startPtsUs)
        assertEquals(368 * second, retry.endPtsUs)
        assertTrue(retry.video.flatMap { it.packets }.any { it.ptsUs == failed.endPtsUs })
    }

    @Test
    fun `首次失败可重试且失败内容不会突破保留窗口`() {
        feed(0, 600)
        assertNotNull(capture()) // 模拟首次导出失败,没有成功回调
        feed(601, 1200)
        val retry = capture()
        assertEquals(420 * second, retry.video.first().startPtsUs)
        assertEquals(600 * second, retry.endPtsUs)
    }

    @Test
    fun `没有新视频时不重复输出只有旧内容的接续片段`() {
        assertNull(session.snapshot(window, SaveMode.CONTINUATION))
        feed(0, 600)
        session.onSaved(capture())
        assertNull(session.snapshot(window, SaveMode.CONTINUATION))
        assertNotNull(session.snapshot(window, SaveMode.LAST_WINDOW))
    }

    @Test
    fun `重建编码器后旧导出不能推进新缓冲的保存边界`() {
        feed(0, 600)
        val exporting = capture()
        val rebuiltVideo = RingSegmentBuffer(window + 2 * second)
        val rebuilt = ClipSaveSession(rebuiltVideo, audio)
        rebuiltVideo.onPacket(EncodedPacket(byteArrayOf(3), 10 * second, true))
        rebuiltVideo.onPacket(EncodedPacket(byteArrayOf(4), 11 * second, true))
        rebuilt.onSaved(exporting)
        val first = rebuilt.snapshot(window, SaveMode.CONTINUATION)!!
        assertEquals(10 * second, first.video.first().startPtsUs)
        rebuilt.onSaved(first)
        // 在新缓冲也成功保存一次之后,旧回调不能更改它的边界。
        rebuilt.onSaved(exporting)
        rebuiltVideo.onPacket(EncodedPacket(byteArrayOf(5), 12 * second, true))
        assertEquals(11 * second, rebuilt.snapshot(window, SaveMode.CONTINUATION)!!.video.first().startPtsUs)
        assertEquals(300 * second, exporting.endPtsUs)
    }

    @Test
    fun `新录制会话允许重置媒体时间轴且不继承上次边界`() {
        feed(0, 600)
        val old = capture()
        session.onSaved(old)
        val freshVideo = RingSegmentBuffer(window + 2 * second)
        val fresh = ClipSaveSession(freshVideo, AudioRingBuffer(window + 2 * second))
        freshVideo.onPacket(EncodedPacket(byteArrayOf(1), 0, true))
        freshVideo.onPacket(EncodedPacket(byteArrayOf(2), second, true))
        fresh.onSaved(old)
        val first = fresh.snapshot(window, SaveMode.CONTINUATION)!!
        assertEquals(0L, first.video.first().startPtsUs)
        assertEquals(second, first.endPtsUs)
    }

    @Test
    fun `音频快照使用实际视频起尾且不会带上提前到达的未来音频`() {
        feed(0, 10)
        audio.onPacket(EncodedPacket(byteArrayOf(9), 5 * second + 10_000))
        val snap = capture()
        assertEquals(5 * second, snap.endPtsUs)
        assertFalse(snap.audio.any { it.ptsUs > snap.endPtsUs })
        assertTrue(PtsRebaser.rebaseAudio(snap.audio, snap.video).all { it.ptsUs <= 5 * second })
    }
}

package com.highlightrecorder.service

import com.highlightrecorder.buffer.AudioRingBuffer
import com.highlightrecorder.buffer.ClipSaveSession
import com.highlightrecorder.buffer.EncodedPacket
import com.highlightrecorder.buffer.RingSegmentBuffer
import com.highlightrecorder.data.SaveMode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSaveSessionTest {
    private class Media {
        val windowUs = 5_000_000L
        private val video = RingSegmentBuffer(windowUs + 2_000_000L)
        val clips = ClipSaveSession(video, AudioRingBuffer(windowUs + 2_000_000L))

        fun feed(from: Int, through: Int) {
            for (i in from..through) {
                video.onPacket(EncodedPacket(byteArrayOf(1), i * 500_000L, i % 2 == 0))
            }
        }

        fun snapshot(mode: SaveMode) = clips.snapshot(windowUs, mode)!!
        fun estimate(mode: SaveMode) = clips.estimatedDurationUs(windowUs, mode)
    }

    @Test
    fun `普通保存不停止且成功后预计时长仍包含导出期间数据`() = runTest {
        for (mode in SaveMode.entries) {
            val media = Media().apply { feed(0, 20) }
            val snapshot = media.snapshot(mode)
            val session = RecordingSaveSession()
            val written = CompletableDeferred<Int>()
            var stopped = false
            val job = launch {
                session.save(
                    stopAfterSave = false,
                    write = { written.await() },
                    onSaved = { assertEquals(7, it); media.clips.onSaved(snapshot) },
                    onFailed = { throw AssertionError(it) },
                    stopRecording = { stopped = true },
                )
            }
            runCurrent()
            assertTrue(session.saving)
            media.feed(21, 26) // 10 秒点击,13 秒完成。
            written.complete(7)
            job.join()
            assertFalse(session.saving)
            assertFalse(stopped)
            assertEquals(if (mode == SaveMode.CONTINUATION) 3_000_000L else 5_000_000L, media.estimate(mode))
        }
    }

    @Test
    fun `保存后停止在两种模式都等文件成功并提交边界之后才停止`() = runTest {
        for (mode in SaveMode.entries) {
            val media = Media().apply { feed(0, 20) }
            val snapshot = media.snapshot(mode)
            val session = RecordingSaveSession()
            val written = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var recording = true
            val job = launch {
                session.save(
                    stopAfterSave = true,
                    write = { written.await(); events.add("文件写出成功") },
                    onSaved = { media.clips.onSaved(snapshot); events.add("提交快照边界") },
                    onFailed = { throw AssertionError(it) },
                    stopRecording = { recording = false; session.close(); events.add("停止录制") },
                )
            }
            runCurrent()
            media.feed(21, 26)
            assertTrue(recording)
            assertTrue(events.isEmpty())
            assertEquals(10_000_000L, snapshot.endPtsUs)
            written.complete(Unit)
            job.join()
            assertFalse(recording)
            assertEquals(listOf("文件写出成功", "提交快照边界", "停止录制"), events)
        }
    }

    @Test
    fun `保存后停止失败时继续录制不推进边界并允许成功重试`() = runTest {
        for (mode in SaveMode.entries) {
            val media = Media().apply { feed(0, 20) }
            media.clips.onSaved(media.snapshot(mode))
            media.feed(21, 24)
            val snapshot = media.snapshot(mode)
            val session = RecordingSaveSession()
            val written = CompletableDeferred<Unit>()
            val failure = IOException("模拟文件发布失败")
            var failureCount = 0
            var stopped = false
            val job = launch {
                session.save(
                    stopAfterSave = true,
                    write = { written.await() },
                    onSaved = { media.clips.onSaved(snapshot) },
                    onFailed = {
                        assertTrue(it is IOException)
                        assertEquals(failure.message, it.message)
                        failureCount++
                    },
                    stopRecording = { stopped = true },
                )
            }
            runCurrent()
            media.feed(25, 28)
            written.completeExceptionally(failure)
            job.join()
            assertEquals(1, failureCount)
            assertFalse(stopped)
            assertFalse(session.saving)
            assertEquals(if (mode == SaveMode.CONTINUATION) 4_000_000L else 5_000_000L, media.estimate(mode))
            val retry = media.snapshot(mode)
            session.save(
                stopAfterSave = true,
                write = { Unit },
                onSaved = { media.clips.onSaved(retry) },
                onFailed = { throw AssertionError(it) },
                stopRecording = { stopped = true },
            )
            assertTrue(stopped)
        }
    }

    @Test
    fun `已有导出时拒绝第二次长按不并发不提前停止且完成后可重试`() = runTest {
        val session = RecordingSaveSession()
        val written = CompletableDeferred<Unit>()
        var writes = 0
        var stops = 0
        val job = launch {
            session.save(false, { writes++; written.await() }, {}, { throw AssertionError(it) }, { stops++ })
        }
        runCurrent()
        val accepted = session.save(true, { writes++ }, {}, { throw AssertionError(it) }, { stops++ })
        assertFalse(accepted)
        assertEquals(1, writes)
        assertEquals(0, stops)
        written.complete(Unit)
        job.join()
        assertEquals(0, stops)
        assertTrue(session.save(true, { writes++ }, {}, { throw AssertionError(it) }, { stops++ }))
        assertEquals(2, writes)
        assertEquals(1, stops)
    }

    @Test
    fun `直接停止重开后旧导出成功不能提交边界发通知或停止新会话`() = runTest {
        val old = RecordingSaveSession()
        val written = CompletableDeferred<Unit>()
        var oldCallbacks = 0
        val oldJob = launch {
            old.save(true, { written.await() }, { oldCallbacks++ }, { oldCallbacks++ }, { oldCallbacks++ })
        }
        runCurrent()
        old.close()
        val fresh = RecordingSaveSession()
        val freshWritten = CompletableDeferred<Unit>()
        var freshCallbacks = 0
        val freshJob = launch {
            fresh.save(false, { freshWritten.await() }, { freshCallbacks++ }, { freshCallbacks++ }, { freshCallbacks++ })
        }
        runCurrent()
        written.complete(Unit)
        oldJob.join()
        assertEquals(0, oldCallbacks)
        assertEquals(0, freshCallbacks)
        assertTrue(fresh.saving)
        freshWritten.complete(Unit)
        freshJob.join()
        assertEquals(1, freshCallbacks)
        assertFalse(fresh.saving)
    }

    @Test
    fun `关闭会话后旧导出失败不触发新会话的失败或停止处理`() = runTest {
        val session = RecordingSaveSession()
        val written = CompletableDeferred<Unit>()
        var callbacks = 0
        val job = launch {
            session.save(true, { written.await() }, { callbacks++ }, { callbacks++ }, { callbacks++ })
        }
        runCurrent()
        session.close()
        written.completeExceptionally(IOException("旧导出失败"))
        job.join()
        assertEquals(0, callbacks)
        assertFalse(session.save(true, { callbacks++ }, {}, {}, {}))
        assertEquals(0, callbacks)
    }

    @Test
    fun `协程取消不算保存成功不停止且释放导出占用`() = runTest {
        val session = RecordingSaveSession()
        var callbacks = 0
        var cancelled = false
        try {
            session.save(true, { throw CancellationException("取消") }, { callbacks++ }, { callbacks++ }, { callbacks++ })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, callbacks)
        assertFalse(session.saving)
    }
}

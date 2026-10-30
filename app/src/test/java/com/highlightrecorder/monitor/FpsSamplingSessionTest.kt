package com.highlightrecorder.monitor

import com.highlightrecorder.data.OverlayDisplay
import com.highlightrecorder.overlay.overlayContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FpsSamplingSessionTest {
    @Test fun `选择改变时丢弃正在返回的旧图层结果并发布已接收选择`() = runTest {
        var choice = FpsLayerChoice()
        val gate = CompletableDeferred<Unit>()
        val updates = mutableListOf<FpsLive>()
        var calls = 0
        val session = FpsSamplingSession("com.example.game", false, read = {
            calls++
            if (calls == 1) {
                gate.await()
                FpsReading("旧选择读取完成", "old", listOf(1, 2, 3), issue = "旧选择失败")
            } else FpsReading("目标不在前台", candidates = listOf("new"))
        }, onUpdate = updates::add, nanoTime = { testScheduler.currentTime * 1_000_000 },
            wallTime = { 1 }, selection = { choice })
        session.start(backgroundScope)
        runCurrent()
        choice = FpsLayerChoice("new", 1)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, updates.size)
        assertEquals(choice, updates.single().choice)
        assertNull(updates.single().lastIssue)
        assertEquals(0, session.report().summary.frameIntervals)
        session.close()
    }

    @Test fun `采样故障在返回后台提示后仍保留阶段时间和图层`() = runTest {
        var calls = 0
        val updates = mutableListOf<FpsLive>()
        val session = FpsSamplingSession("com.example.game", false, read = {
            calls++
            if (calls == 1) FpsReading("读取呈现失败", issue = "读取呈现时间戳: 命令退出码 7", issueLayer = "selected")
            else FpsReading("目标不在前台")
        }, onUpdate = updates::add, nanoTime = { testScheduler.currentTime * 1_000_000 }, wallTime = { 1 })
        session.start(backgroundScope)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("目标不在前台", updates.last().status)
        assertEquals("读取呈现时间戳: 命令退出码 7", updates.last().lastIssue)
        assertEquals("selected", updates.last().issueLayer)
        assertEquals(0.0, updates.last().issueAtSeconds!!, 0.0)
        session.close()
        assertEquals(updates.last().lastIssue, session.report().lastIssue)
        assertNull(updates.last().fps)
    }

    @Test fun `命令阻塞后旧FPS必须过期不能保持假实时数值`() {
        val live = FpsLive(active = true, fps = 60.0, sampledAtNs = 1_000_000_000)
        assertEquals(60.0, live.currentFps(2_500_000_000)!!, 0.0)
        assertNull(live.currentFps(2_500_000_001))
        assertNull(live.copy(active = false).currentFps(1_000_000_000))
    }
    @Test fun `录制与独立监测均只启动一次显示切换不重置采样或统计`() = runTest {
        for (recording in listOf(false, true)) {
            var calls = 0
            val updates = mutableListOf<FpsLive>()
            val base = 1_000_000_000L + testScheduler.currentTime * 1_000_000
            val times = mutableListOf(base)
            val session = FpsSamplingSession("com.game", recording, read = {
                calls++
                if (calls > 1) repeat(50) { times.add(times.last() + 10_000_000) }
                FpsReading("", "game", times.toList())
            }, onUpdate = updates::add, nanoTime = { 1_000_000_000L + testScheduler.currentTime * 1_000_000 }, wallTime = { 1 })
            session.start(backgroundScope)
            session.start(backgroundScope)
            runCurrent()
            assertEquals(1, calls)
            for (display in OverlayDisplay.entries) {
                overlayContent(display, updates.last().fps, 15)
                advanceTimeBy(500)
                runCurrent()
            }
            assertEquals(4, calls)
            assertEquals(150, session.report().summary.frameIntervals)
            assertEquals(recording, updates.last().recording)
            session.close()
            val count = calls
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(count, calls)
        }
    }

    @Test fun `旧会话不可取消读取完成后也不能发布或污染新会话`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val updates = mutableListOf<String>()
        val old = FpsSamplingSession("old", false, read = {
            withContext(NonCancellable) { gate.await() }
            FpsReading("old completed")
        }, onUpdate = { updates.add(it.targetPackage) }, nanoTime = { testScheduler.currentTime * 1_000_000 }, wallTime = { 1 })
        old.start(backgroundScope)
        runCurrent()
        old.close()
        val fresh = FpsSamplingSession("new", true, read = { FpsReading("new waiting") },
            onUpdate = { updates.add(it.targetPackage) }, nanoTime = { testScheduler.currentTime * 1_000_000 }, wallTime = { 2 })
        fresh.start(backgroundScope)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), updates)
        assertEquals(0, fresh.report().summary.frameIntervals)
        fresh.close()
    }

    @Test fun `无权限或目标后台的缺失不能补成零帧且恢复后重新建立边界`() = runTest {
        var calls = 0
        val updates = mutableListOf<FpsLive>()
        val session = FpsSamplingSession("game", false, read = {
            calls++
            if (calls == 1) throw IllegalStateException("connection failed")
            FpsReading("target background")
        }, onUpdate = updates::add, nanoTime = { testScheduler.currentTime * 1_000_000 }, wallTime = { 1 })
        session.start(backgroundScope)
        runCurrent()
        assertNull(updates.last().fps)
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        assertTrue(updates.all { it.fps == null && it.points.all { p -> p.fps == null } })
        assertNull(session.report().summary.meanFps)
        session.close()
    }

    @Test fun `结束冻结时长且同一实例不能重启`() = runTest {
        var calls = 0
        val session = FpsSamplingSession("game", false, read = { calls++; FpsReading("waiting") },
            onUpdate = {}, nanoTime = { testScheduler.currentTime * 1_000_000 }, wallTime = { 1 })
        session.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1234)
        session.close()
        val report = session.report()
        advanceTimeBy(3000)
        session.start(backgroundScope)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1.234, report.durationSeconds, 1e-9)
        assertEquals(report, session.report())
    }
}

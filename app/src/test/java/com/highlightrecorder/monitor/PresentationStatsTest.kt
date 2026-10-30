package com.highlightrecorder.monitor

import org.junit.Assert.*
import org.junit.Test

class PresentationStatsTest {
    @Test fun `没有逐帧数据格式不兼容待呈现和缺口分别给出原因而不提供替代FPS`() {
        val cases = listOf(
            "16666666\n" to "只返回刷新周期",
            "Permission Denial" to "系统拒绝",
            "unexpected header\n" to "格式不受支持",
            "16666666\n1 0 9\n2 ${Long.MAX_VALUE} 10\n" to "有效呈现 0 行",
            "16666666\n1 10 9\n2 0 19\n3 30 29\n" to "存在缺口",
        )
        cases.forEach { (text, expected) ->
            val batch = SurfaceFlingerData.presentationBatch(text)
            assertTrue(batch.times.isEmpty())
            assertTrue(batch.problem!!.contains(expected))
        }
    }

    @Test fun `刷新周期编码参数帧就绪时间和待呈现值不能充当实际呈现时间`() {
        assertTrue(SurfaceFlingerData.presentationTimes("16666666\n").isEmpty())
        assertEquals(listOf(10L, 20L), SurfaceFlingerData.presentationTimes(
            "16666666\n2 0 11\n1 10 9\n4 20 19\n4 20 19\n3 ${Long.MAX_VALUE} 12\n"))
        assertTrue(SurfaceFlingerData.presentationTimes("60\n1 pending 123\n1 2\n").isEmpty())
    }

    @Test fun `有效帧中间缺失时间戳时不能连接成假慢帧`() {
        assertTrue(SurfaceFlingerData.presentationTimes("16666666\n1 10 9\n2 0 19\n3 30 29\n").isEmpty())
        assertTrue(SurfaceFlingerData.presentationTimes("16666666\n1 10 9\nmalformed\n").isEmpty())
    }

    @Test fun `采样点缺失时曲线不跨缺口连线`() {
        val stats = PresentationStats(0)
        stats.point(1_000_000_000, true)
        stats.point(4_000_000_000, true)
        assertNotEquals(stats.curve()[0].segment, stats.curve()[1].segment)
    }

    @Test fun `目标包名精确匹配多个图层不猜测且支持Android16列表包装`() {
        val candidates = SurfaceFlingerData.layers("""
            RequestedLayerState{SurfaceView[com.game/.Main]#12 parentId=3 z=2}
            RequestedLayerState{SurfaceView[com.game/.Other]#15 mirrorId=1 !handle}
            com.game.fake/.Main#1
            Background for SurfaceView[com.game/.Main]
            ActivityRecord{com.game/.Main}
        """.trimIndent(), "com.game")
        assertEquals(listOf("SurfaceView[com.game/.Main]#12", "SurfaceView[com.game/.Other]#15"), candidates)
        assertNull(SurfaceFlingerData.automaticLayer(candidates))
        assertEquals(candidates.first(), SurfaceFlingerData.automaticLayer(candidates.take(1)))
        assertTrue(SurfaceFlingerData.layers("anything", "").isEmpty())
    }

    @Test fun `前台焦点缺失或多个显示目标不明确时不猜测`() {
        assertEquals("com.game", SurfaceFlingerData.foregroundPackage("mCurrentFocus=Window{123 u0 com.game/.Main}"))
        assertNull(SurfaceFlingerData.foregroundPackage("mCurrentFocus=null"))
        assertNull(SurfaceFlingerData.foregroundPackage("mCurrentFocus=Window{123 u0 com.game/.Main}\nmCurrentFocus=Window{124 u0 com.other/.Main}"))
    }

    @Test fun `首批仅建立基线忽略会话前与未来帧并去重`() {
        val stats = PresentationStats(100)
        stats.accept("game", listOf(1, 100, 110, 120, 200), 120)
        assertEquals(0, stats.summary(120).frameIntervals)
        stats.accept("game", listOf(100, 110, 120, 130, 130, 140), 140)
        assertEquals(2, stats.summary(140).frameIntervals)
        assertEquals(100_000_000.0, stats.liveFps(140)!!, 0.001)
        assertNull(stats.summary(140).onePercentLow)
    }

    @Test fun `均值与最慢百分之一间隔倒数有明确算法`() {
        val stats = PresentationStats(0)
        var end = 1L
        stats.accept("game", listOf(end), end)
        val times = mutableListOf(end)
        repeat(98) { end += 10_000_000L; times.add(end) }
        end += 100_000_000L; times.add(end)
        end += 200_000_000L; times.add(end)
        stats.accept("game", times, end)
        val summary = stats.summary(end)
        assertEquals(100, summary.frameIntervals)
        assertEquals(1.28, summary.validSeconds, 1e-9)
        assertEquals(100 / 1.28, summary.meanFps!!, 1e-9)
        assertEquals(5.0, summary.onePercentLow!!, 1e-9)
    }

    @Test fun `百分之一数量向上取整并先平均帧间隔`() {
        val stats = PresentationStats(0)
        var end = 1L
        stats.accept("game", listOf(end), end)
        val times = mutableListOf(end)
        repeat(199) { end += 10_000_000L; times.add(end) }
        end += 100_000_000L; times.add(end)
        end += 200_000_000L; times.add(end)
        stats.accept("game", times, end)
        // ceil(201 * 1%) = 3;取 200ms、100ms、10ms。
        assertEquals(3 / 0.31, stats.summary(end).onePercentLow!!, 1e-9)
    }

    @Test fun `缺失中断和图层更换不跨段计时也不显示上一段实时值`() {
        val stats = PresentationStats(0)
        stats.accept("a", listOf(1), 1)
        stats.accept("a", listOf(1, 10_000_001), 10_000_001)
        stats.point(10_000_001, true)
        stats.interrupt()
        stats.point(1_010_000_001, false)
        assertNull(stats.liveFps(1_010_000_001))
        stats.accept("a", listOf(2_000_000_001), 2_000_000_001)
        assertNull(stats.liveFps(2_000_000_001))
        stats.accept("a", listOf(2_000_000_001, 2_010_000_001), 2_010_000_001)
        stats.accept("b", listOf(2_020_000_001), 2_020_000_001)
        assertNull(stats.liveFps(2_020_000_001))
        assertEquals(2, stats.summary(2_020_000_001).frameIntervals)
        assertEquals(0.02, stats.summary(2_020_000_001).validSeconds, 1e-9)
        assertEquals(2, stats.interruptions)
        assertNull(stats.curve().last().fps)
    }

    @Test fun `历史环覆盖时只使用新批内部间隔且重复批不重复计数`() {
        val stats = PresentationStats(0)
        stats.accept("a", listOf(1), 1)
        val times = listOf(1_000_000_001L, 1_010_000_001, 1_020_000_001)
        stats.accept("a", times, times.last())
        stats.accept("a", times, times.last())
        assertEquals(2, stats.summary(times.last()).frameIntervals)
        assertEquals(1, stats.interruptions)
        assertNull(stats.liveFps(times.last() + 1_000_000_001))
    }

    @Test fun `无效批断开且停止重开的历史不进入新统计`() {
        val stats = PresentationStats(10_000_000_000)
        assertFalse(stats.accept("a", listOf(1, 2, 3), 10_000_000_100))
        assertEquals(0, stats.summary(10_000_000_100).frameIntervals)
        stats.accept("a", listOf(10_000_000_010, 10_000_000_020), 10_000_000_100)
        assertEquals(0, stats.summary(10_000_000_100).frameIntervals)
    }

    @Test fun `帧间隔和曲线均有界且过期数据不计入统计`() {
        val stats = PresentationStats(0)
        stats.accept("a", listOf(1), 1)
        val times = List(PresentationStats.CAPACITY + 10) { 1L + it * 1000L }
        stats.accept("a", times, times.last())
        assertEquals(PresentationStats.CAPACITY, stats.summary(times.last()).frameIntervals)
        repeat(400) { stats.point((it + 1) * 1_000_000_000L, true) }
        assertEquals(300, stats.curve().size)
        assertEquals(0, stats.summary(401_000_000_000).frameIntervals)
        assertNull(stats.summary(401_000_000_000).meanFps)
    }
}

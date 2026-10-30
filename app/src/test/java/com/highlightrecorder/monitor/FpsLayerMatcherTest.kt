package com.highlightrecorder.monitor

import org.junit.Assert.*
import org.junit.Test

class FpsLayerMatcherTest {
    private val host = "a123 SurfaceView[com.example.game/.Main] 10-29 23:09:48#12"
    private val blast = "a123 SurfaceView[com.example.game/.Main](BLAST) 10-29 23:09:48#13"
    private val reopened = "b456 SurfaceView[com.example.game/.Main](BLAST) 10-30 12:00:00#99"

    @Test fun `初次使用在宿主和BLAST同时出现时自动选唯一BLAST`() {
        assertEquals(blast, FpsLayerMatcher.automatic(listOf(host, blast, "com.example.game/.Main#14"), null))
        assertEquals(blast, SurfaceFlingerData.automaticLayer(listOf(host, blast)))
    }

    @Test fun `重开后的日期编号和前缀改变不影响成功偏好匹配`() {
        assertEquals("BLAST|com.example.game/.Main", FpsLayerMatcher.key(blast))
        assertEquals(FpsLayerMatcher.key(blast), FpsLayerMatcher.key(reopened))
        assertEquals(reopened, FpsLayerMatcher.match(listOf(host, reopened), FpsLayerMatcher.key(blast)))
        assertEquals(reopened, FpsLayerMatcher.automatic(listOf(host, reopened), FpsLayerMatcher.key(blast)))
    }

    @Test fun `曾成功的手动类型优先且不会匹配到其他应用或组件`() {
        val other = "SurfaceView[com.other.game/.Main](BLAST)#10"
        assertEquals(host, FpsLayerMatcher.automatic(listOf(host, blast), FpsLayerMatcher.key(host)))
        assertNull(FpsLayerMatcher.match(listOf(other), FpsLayerMatcher.key(blast)))
        assertNull(FpsLayerMatcher.match(listOf("SurfaceView[com.example.game/.Popup](BLAST)#11"), FpsLayerMatcher.key(blast)))
    }

    @Test fun `同一类型有多个窗口时保留手动选择不按编号猜测`() {
        assertNull(FpsLayerMatcher.automatic(listOf(host, blast, reopened), null))
        assertNull(FpsLayerMatcher.automatic(listOf(blast, reopened), FpsLayerMatcher.key(blast)))
        assertNull(FpsLayerMatcher.match(listOf(blast, reopened), FpsLayerMatcher.key(blast)))
    }

    @Test fun `旧偏好消失可重新自动匹配但无可靠唯一候选时不可用`() {
        assertEquals(blast, FpsLayerMatcher.automatic(listOf(host, blast), "BLAST|com.example.game/.Old"))
        assertEquals(host, FpsLayerMatcher.automatic(listOf(host), null))
        assertEquals("com.example.game/.Main", FpsLayerMatcher.automatic(listOf("com.example.game/.Main"), null))
        assertNull(FpsLayerMatcher.automatic(emptyList(), null))
        assertNull(FpsLayerMatcher.automatic(listOf("com.example.game/.Main", "com.example.game/.Popup"), null))
    }

    @Test fun `选中图层本身不产生任何FPS或合成时间戳`() {
        assertEquals(blast, FpsLayerMatcher.automatic(listOf(host, blast), null))
        assertTrue(SurfaceFlingerData.presentationTimes("16666666\n").isEmpty())
        assertNull(PresentationStats(0).liveFps(1_000_000_000))
    }
}

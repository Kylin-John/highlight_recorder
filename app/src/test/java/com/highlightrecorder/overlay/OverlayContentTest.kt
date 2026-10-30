package com.highlightrecorder.overlay

import com.highlightrecorder.data.OverlayDisplay
import org.junit.Assert.*
import org.junit.Test

class OverlayContentTest {
    @Test fun `三种显示各自只有所选内容且不混入单位`() {
        assertEquals("存", overlayContent(OverlayDisplay.SAVE, 59.6, 123))
        assertEquals("60", overlayContent(OverlayDisplay.FPS, 59.6, 123))
        assertEquals("123", overlayContent(OverlayDisplay.SAVE_SECONDS, 59.6, 123))
        assertEquals("0", overlayContent(OverlayDisplay.SAVE_SECONDS, null, 0))
    }
    @Test fun `无游戏数据无录制或无效数值明确不可用`() {
        assertEquals("—", overlayContent(OverlayDisplay.FPS, null, 30))
        assertEquals("—", overlayContent(OverlayDisplay.FPS, Double.NaN, 30))
        assertEquals("—", overlayContent(OverlayDisplay.FPS, Double.POSITIVE_INFINITY, 30))
        assertEquals("—", overlayContent(OverlayDisplay.SAVE_SECONDS, 60.0, null))
    }
}

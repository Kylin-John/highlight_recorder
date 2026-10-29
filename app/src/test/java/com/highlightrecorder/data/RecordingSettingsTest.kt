package com.highlightrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSettingsTest {
    @Test
    fun `新安装旧用户缺失配置及未知配置都使用原有保存模式`() {
        assertEquals(SaveMode.LAST_WINDOW, RecordingSettings().saveMode)
        assertEquals(SaveMode.LAST_WINDOW, SaveMode.fromPreference(null))
        assertEquals(SaveMode.LAST_WINDOW, SaveMode.fromPreference("unrecognized"))
        assertEquals(SaveMode.CONTINUATION, SaveMode.fromPreference("CONTINUATION"))
    }

    @Test
    fun `新安装旧用户及未知长按配置都默认直接停止`() {
        assertEquals(OverlayLongPressAction.STOP, RecordingSettings().overlayLongPressAction)
        assertEquals(OverlayLongPressAction.STOP, OverlayLongPressAction.fromPreference(null))
        assertEquals(OverlayLongPressAction.STOP, OverlayLongPressAction.fromPreference("unknown"))
        for (action in OverlayLongPressAction.entries) {
            assertEquals(action, OverlayLongPressAction.fromPreference(action.name))
        }
    }
}

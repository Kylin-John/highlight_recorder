package com.highlightrecorder.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class FpsAppPreferencesTest {
    @Test fun `旧用户没有新键时历史为空且当前选择优先不会伪造使用次数`() {
        val prefs = mutablePreferencesOf()
        val settings = RecordingSettings(fpsTargetPackage = "com.example.b")
        SettingsRepository.writeTo(prefs, settings)
        assertTrue(FpsAppPreferences.read(prefs).isEmpty())
        val sorted = FpsAppPreferences.sort(listOf(FpsTargetApp("com.example.a", "A"), FpsTargetApp("com.example.b", "B")),
            FpsAppPreferences.read(prefs), settings.fpsTargetPackage)
        assertEquals("com.example.b", sorted.first().packageName)
        assertTrue(FpsAppPreferences.read(prefs).isEmpty())
    }

    @Test fun `成功图层特征和会话次数可经真实Preferences映射往返`() {
        val prefs = mutablePreferencesOf()
        FpsAppPreferences.recordUse(prefs, "com.example.a", 100)
        FpsAppPreferences.rememberLayer(prefs, "com.example.a", "BLAST|com.example.a/.Main")
        FpsAppPreferences.recordUse(prefs, "com.example.a", 200)
        assertEquals(FpsAppPreference("BLAST|com.example.a/.Main", 2, 200),
            FpsAppPreferences.read(prefs.toPreferences())["com.example.a"])
    }

    @Test fun `记录成功图层不增加会话计数且应用之间互不覆盖`() {
        val prefs = mutablePreferencesOf()
        FpsAppPreferences.rememberLayer(prefs, "com.example.a", "SURFACE|com.example.a/.Main")
        FpsAppPreferences.recordUse(prefs, "com.example.b", 300)
        assertEquals(FpsAppPreference("SURFACE|com.example.a/.Main"), FpsAppPreferences.read(prefs)["com.example.a"])
        assertEquals(FpsAppPreference(sessions = 1, lastUsedAtMs = 300), FpsAppPreferences.read(prefs)["com.example.b"])
    }

    @Test fun `常用次数优先同次数最近使用优先并仅排序已安装列表`() {
        val history = mapOf(
            "a" to FpsAppPreference(sessions = 2, lastUsedAtMs = 100),
            "b" to FpsAppPreference(sessions = 1, lastUsedAtMs = 500),
            "c" to FpsAppPreference(sessions = 1, lastUsedAtMs = 600),
            "removed" to FpsAppPreference(sessions = 100, lastUsedAtMs = 999),
        )
        val apps = listOf(FpsTargetApp("b", "B"), FpsTargetApp("z", "Z"), FpsTargetApp("c", "C"), FpsTargetApp("a", "A"))
        assertEquals(listOf("a", "c", "b", "z"), FpsAppPreferences.sort(apps, history, "z").map { it.packageName })
    }

    @Test fun `录制设置和监测元数据共存更新任意一方不清除另一方`() {
        val prefs = mutablePreferencesOf()
        val settings = RecordingSettings(rewindSeconds = 120, saveMode = SaveMode.CONTINUATION,
            overlayDisplay = OverlayDisplay.SAVE_SECONDS, overlayLongPressAction = OverlayLongPressAction.SAVE_AND_STOP)
        SettingsRepository.writeTo(prefs, settings)
        FpsAppPreferences.recordUse(prefs, "com.example.a", 100)
        FpsAppPreferences.rememberLayer(prefs, "com.example.a", "BLAST|com.example.a/.Main")
        assertEquals(settings, SettingsRepository.fromPrefs(prefs))
        val history = FpsAppPreferences.read(prefs)
        SettingsRepository.writeTo(prefs, settings.copy(overlayDisplay = OverlayDisplay.FPS))
        assertEquals(history, FpsAppPreferences.read(prefs))
    }

    @Test fun `未选择目标的录制不写入空白监测历史`() {
        val prefs = mutablePreferencesOf()
        FpsAppPreferences.recordUse(prefs, "", 100)
        FpsAppPreferences.rememberLayer(prefs, "", "BLAST|")
        assertTrue(FpsAppPreferences.read(prefs).isEmpty())
    }
}

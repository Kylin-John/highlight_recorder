package com.highlightrecorder.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun `旧浮窗配置默认存三种显示与目标均可往返且保留保存设置`() {
        val original = RecordingSettings(saveMode = SaveMode.CONTINUATION,
            overlayLongPressAction = OverlayLongPressAction.SAVE_AND_STOP, rewindSeconds = 120)
        val prefs = mutablePreferencesOf()
        SettingsRepository.writeTo(prefs, original)
        prefs.remove(stringPreferencesKey("overlay_display"))
        prefs.remove(stringPreferencesKey("fps_target_package"))
        assertEquals(original, SettingsRepository.fromPrefs(prefs))
        prefs[stringPreferencesKey("overlay_display")] = "unknown"
        assertEquals(original, SettingsRepository.fromPrefs(prefs))
        for (display in OverlayDisplay.entries) {
            val settings = original.copy(overlayDisplay = display, fpsTargetPackage = "com.example.game")
            SettingsRepository.writeTo(prefs, settings)
            assertEquals(settings, SettingsRepository.fromPrefs(prefs))
        }
    }
    @Test
    fun `旧设置缺少长按键时保留原有参数并默认直接停止`() {
        val old = RecordingSettings(rewindSeconds = 120, saveMode = SaveMode.CONTINUATION)
        val prefs = mutablePreferencesOf()
        SettingsRepository.writeTo(prefs, old)
        prefs.remove(stringPreferencesKey("overlay_long_press_action"))
        assertEquals(old, SettingsRepository.fromPrefs(prefs))
        prefs[stringPreferencesKey("overlay_long_press_action")] = "unknown"
        assertEquals(old, SettingsRepository.fromPrefs(prefs))
    }

    @Test
    fun `两种长按行为与保存模式均可经持久化映射往返且保留其他设置`() {
        for (action in OverlayLongPressAction.entries) {
            for (mode in SaveMode.entries) {
                val settings = RecordingSettings(
                    rewindSeconds = 120, saveMode = mode, resolutionShortEdge = 1080,
                    frameRate = 60, audioSource = AudioSource.MUTE, overlayAlpha = 0.5f,
                    overlayHidden = true, overlayLongPressAction = action,
                )
                val prefs = mutablePreferencesOf()
                SettingsRepository.writeTo(prefs, settings)
                assertEquals(action.name, prefs[stringPreferencesKey("overlay_long_press_action")])
                assertEquals(settings, SettingsRepository.fromPrefs(prefs))
            }
        }
    }
}

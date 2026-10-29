package com.highlightrecorder.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
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

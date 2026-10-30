package com.highlightrecorder.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 设置持久化(DataStore)。服务启动时经 [SettingsHolder] 读取快照。 */
class SettingsRepository(context: Context) {
    private val context = context.applicationContext

    companion object {
        // 少量有限的偏好写入可在服务停止后完成,不持有 Activity/Service 或启动常驻任务。
        private val fpsWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private object Keys {
            val REWIND = intPreferencesKey("rewind_seconds")
            val SAVE_MODE = stringPreferencesKey("save_mode")
            val RESOLUTION = intPreferencesKey("resolution_short_edge")
            val FPS = intPreferencesKey("frame_rate")
            val BITRATE = intPreferencesKey("video_bitrate_bps")
            val MIME = stringPreferencesKey("video_mime")
            val AUDIO = stringPreferencesKey("audio_source")
            val OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
            val OVERLAY_SCALE = floatPreferencesKey("overlay_scale")
            val OVERLAY_EDGE = booleanPreferencesKey("overlay_edge_hide")
            val OVERLAY_HIDDEN = booleanPreferencesKey("overlay_hidden")
            val OVERLAY_LONG_PRESS = stringPreferencesKey("overlay_long_press_action")
            val OVERLAY_DISPLAY = stringPreferencesKey("overlay_display")
            val FPS_TARGET = stringPreferencesKey("fps_target_package")
            val TRASH_DAYS = intPreferencesKey("trash_auto_delete_days")
        }

        internal fun fromPrefs(p: Preferences) = RecordingSettings(
            rewindSeconds = p[Keys.REWIND] ?: 30,
            saveMode = SaveMode.fromPreference(p[Keys.SAVE_MODE]),
            resolutionShortEdge = p[Keys.RESOLUTION] ?: 720,
            frameRate = p[Keys.FPS] ?: 30,
            videoBitrateBps = p[Keys.BITRATE] ?: 8_000_000,
            videoMime = p[Keys.MIME] ?: "video/avc",
            audioSource = p[Keys.AUDIO]?.let {
                runCatching { AudioSource.valueOf(it) }.getOrNull()
            } ?: AudioSource.INTERNAL,
            overlayAlpha = p[Keys.OVERLAY_ALPHA] ?: 0.85f,
            overlayScale = p[Keys.OVERLAY_SCALE] ?: 1.0f,
            overlayEdgeHide = p[Keys.OVERLAY_EDGE] ?: false,
            overlayHidden = p[Keys.OVERLAY_HIDDEN] ?: false,
            overlayLongPressAction = OverlayLongPressAction.fromPreference(p[Keys.OVERLAY_LONG_PRESS]),
            overlayDisplay = OverlayDisplay.fromPreference(p[Keys.OVERLAY_DISPLAY]),
            fpsTargetPackage = p[Keys.FPS_TARGET] ?: "",
            trashAutoDeleteDays = p[Keys.TRASH_DAYS] ?: 30,
        )

        internal fun writeTo(p: MutablePreferences, s: RecordingSettings) {
            p[Keys.REWIND] = s.rewindSeconds
            p[Keys.SAVE_MODE] = s.saveMode.name
            p[Keys.RESOLUTION] = s.resolutionShortEdge
            p[Keys.FPS] = s.frameRate
            p[Keys.BITRATE] = s.videoBitrateBps
            p[Keys.MIME] = s.videoMime
            p[Keys.AUDIO] = s.audioSource.name
            p[Keys.OVERLAY_ALPHA] = s.overlayAlpha
            p[Keys.OVERLAY_SCALE] = s.overlayScale
            p[Keys.OVERLAY_EDGE] = s.overlayEdgeHide
            p[Keys.OVERLAY_HIDDEN] = s.overlayHidden
            p[Keys.OVERLAY_LONG_PRESS] = s.overlayLongPressAction.name
            p[Keys.OVERLAY_DISPLAY] = s.overlayDisplay.name
            p[Keys.FPS_TARGET] = s.fpsTargetPackage
            p[Keys.TRASH_DAYS] = s.trashAutoDeleteDays
        }
    }

    val settings: Flow<RecordingSettings> = context.dataStore.data.map { fromPrefs(it) }
    val fpsApps: Flow<Map<String, FpsAppPreference>> = context.dataStore.data.map(FpsAppPreferences::read)

    fun recordFpsUse(pkg: String) = writeFpsPreferences { p ->
        FpsAppPreferences.recordUse(p, pkg, System.currentTimeMillis())
    }

    fun rememberFpsLayer(pkg: String, key: String) = writeFpsPreferences { p ->
        FpsAppPreferences.rememberLayer(p, pkg, key)
    }

    private fun writeFpsPreferences(write: (MutablePreferences) -> Unit) {
        fpsWrites.launch {
            try { context.dataStore.edit { write(it) } }
            catch (_: IOException) { android.util.Log.w("SettingsRepository", "FPS preferences could not be saved") }
        }
    }

    suspend fun update(transform: (RecordingSettings) -> RecordingSettings): RecordingSettings =
        fromPrefs(context.dataStore.edit { p ->
            writeTo(p, transform(fromPrefs(p)))
        })
}

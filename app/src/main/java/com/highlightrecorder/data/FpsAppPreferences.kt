package com.highlightrecorder.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

data class FpsTargetApp(val packageName: String, val label: String)
data class FpsAppPreference(
    val successfulLayerKey: String? = null,
    val sessions: Int = 0,
    val lastUsedAtMs: Long = 0,
)

/** 仅记录本应用内的监测选择;不读取系统应用使用记录。 */
object FpsAppPreferences {
    private val packages = stringSetPreferencesKey("fps_history_packages")
    private fun layer(pkg: String) = stringPreferencesKey("fps_successful_layer:$pkg")
    private fun sessions(pkg: String) = intPreferencesKey("fps_sessions:$pkg")
    private fun lastUsed(pkg: String) = longPreferencesKey("fps_last_used:$pkg")

    fun read(p: Preferences): Map<String, FpsAppPreference> =
        (p[packages] ?: emptySet()).associateWith { pkg ->
            FpsAppPreference(p[layer(pkg)], p[sessions(pkg)] ?: 0, p[lastUsed(pkg)] ?: 0)
        }

    fun recordUse(p: MutablePreferences, pkg: String, nowMs: Long) {
        if (pkg.isBlank()) return
        p[packages] = (p[packages] ?: emptySet()) + pkg
        p[sessions(pkg)] = (p[sessions(pkg)] ?: 0) + 1
        p[lastUsed(pkg)] = nowMs
    }

    fun rememberLayer(p: MutablePreferences, pkg: String, key: String) {
        if (pkg.isBlank()) return
        p[packages] = (p[packages] ?: emptySet()) + pkg
        p[layer(pkg)] = key
    }

    fun sort(apps: List<FpsTargetApp>, history: Map<String, FpsAppPreference>, selected: String): List<FpsTargetApp> =
        apps.sortedWith(compareByDescending<FpsTargetApp> { history[it.packageName]?.sessions ?: 0 }
            .thenByDescending { history[it.packageName]?.lastUsedAtMs ?: 0 }
            .thenByDescending { it.packageName == selected }
            .thenBy { it.label }.thenBy { it.packageName })
}

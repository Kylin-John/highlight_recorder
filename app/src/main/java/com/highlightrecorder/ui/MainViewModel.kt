package com.highlightrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.highlightrecorder.data.RecordingSettings
import com.highlightrecorder.data.SettingsHolder
import com.highlightrecorder.data.SettingsRepository
import com.highlightrecorder.data.FpsTargetApp
import com.highlightrecorder.data.FpsAppPreferences
import com.highlightrecorder.service.RecordingService
import com.highlightrecorder.monitor.FpsPermission
import com.highlightrecorder.monitor.FpsReports
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)

    /** 设置项(持久化),同时桥接到 [SettingsHolder] 供服务读取。 */
    val settings: StateFlow<RecordingSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingSettings())

    private val launcherTargets = MutableStateFlow<List<FpsTargetApp>>(emptyList())
    val monitorTargets = combine(launcherTargets, settingsRepo.fpsApps, settings) { apps, history, settings ->
        FpsAppPreferences.sort(apps, history, settings.fpsTargetPackage)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val fpsPermissionProblem = MutableStateFlow(FpsPermission.problem())
    val fpsLive = FpsReports.live
    val currentFpsReport = FpsReports.current
    val latestFpsReport = FpsReports.latest
    val fpsStorageProblem = FpsReports.storageProblem
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshFpsPermission() }
    private val binderReady = Shizuku.OnBinderReceivedListener { refreshFpsPermission() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshFpsPermission() }

    init {
        viewModelScope.launch {
            settings.collect { SettingsHolder.current = it }
        }
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReady)
        Shizuku.addBinderDeadListener(binderDead)
        FpsReports.load(app)
        viewModelScope.launch {
            launcherTargets.value = withContext(Dispatchers.IO) {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                app.packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != app.packageName }
                    .map { FpsTargetApp(it.activityInfo.packageName, it.loadLabel(app.packageManager).toString()) }
                    .distinctBy { it.packageName }
            }
        }
    }

    fun updateSettings(transform: (RecordingSettings) -> RecordingSettings) {
        viewModelScope.launch {
            SettingsHolder.current = settingsRepo.update(transform)
            // 录制中时让服务重建悬浮窗(隐藏/透明度/大小实时生效);未录制时服务收到即自停
            val ctx = getApplication<Application>()
            ctx.startService(
                android.content.Intent(ctx, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_REFRESH_OVERLAY),
            )
        }
    }

    /** 权限快照,页面 onResume 时 [refreshPermissions]。 */
    data class PermissionSnapshot(
        val overlay: Boolean = false,
        val notification: Boolean = false,
        val storage: Boolean = false,
        val battery: Boolean = false,
        val mic: Boolean = false,
    ) {
        val mandatoryOk: Boolean get() = overlay && notification && storage
    }

    private val _permissions = MutableStateFlow(PermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions

    val recordingState = RecordingService.state
    val bufferedSeconds = RecordingService.bufferedSeconds
    val estimatedSaveSeconds = RecordingService.estimatedSaveSeconds
    val savedEvents = RecordingService.savedEvents

    /** 首次启动是否已完成引导。 */
    private val prefs = app.getSharedPreferences("app", Application.MODE_PRIVATE)
    private val _onboarded = MutableStateFlow(prefs.getBoolean("onboarded", false))
    val onboarded: StateFlow<Boolean> = _onboarded

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _permissions.value = PermissionSnapshot(
            overlay = PermissionHelper.overlayGranted(ctx),
            notification = PermissionHelper.notificationGranted(ctx),
            storage = PermissionHelper.storageGranted(ctx),
            battery = PermissionHelper.batteryWhitelistGranted(ctx),
            mic = PermissionHelper.micGranted(ctx),
        )
        refreshFpsPermission()
    }

    fun refreshFpsPermission() { fpsPermissionProblem.value = FpsPermission.problem() }

    fun requestFpsPermission() {
        if (Shizuku.pingBinder()) {
            runCatching { Shizuku.requestPermission(2301) }.onFailure { refreshFpsPermission() }
        } else refreshFpsPermission()
    }

    fun requestMonitoring() {
        if (fpsLive.value.active) return
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx,
            android.content.Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_MONITOR_START))
    }

    fun refreshFpsReport() {
        if (!fpsLive.value.active) return
        val ctx = getApplication<Application>()
        ctx.startService(android.content.Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_FPS_REPORT))
    }

    fun selectFpsLayer(layer: String?) {
        val ctx = getApplication<Application>()
        ctx.startService(android.content.Intent(ctx, RecordingService::class.java)
            .setAction(RecordingService.ACTION_FPS_LAYER).putExtra(RecordingService.EXTRA_FPS_LAYER, layer))
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        Shizuku.removeBinderReceivedListener(binderReady)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onCleared()
    }

    fun markOnboarded() {
        prefs.edit().putBoolean("onboarded", true).apply()
        _onboarded.value = true
    }

    fun requestStop() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            ctx.startService(
                android.content.Intent(ctx, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP),
            )
        }
    }

    fun requestSave() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            ctx.startService(
                android.content.Intent(ctx, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_SAVE),
            )
        }
    }
}

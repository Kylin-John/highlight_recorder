package com.highlightrecorder.monitor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import com.highlightrecorder.BuildConfig
import com.highlightrecorder.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku

object FpsPermission {
    fun problem(): String? = try {
        when {
            !Shizuku.pingBinder() -> "Shizuku 未运行;请先在手机上启动"
            Shizuku.isPreV11() || Shizuku.getVersion() < 12 -> "需要 Shizuku 12 或更新版本"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "未授予 Shizuku 权限"
            else -> null
        }
    } catch (_: RuntimeException) { "Shizuku 连接不可用" }
}

/** 一个会话一条连接;不依据浮窗显示选择启动或停止。 */
class ShizukuFrames(private val context: Context, private val target: String) {
    private val preferences = SettingsRepository(context)
    private var loadedPreference = false
    @Volatile private var preferredKey: String? = null

    /** 仅在统计已经产生有效 FPS 后调用,不把单纯点击或失败的图层记成成功。 */
    fun rememberSuccessfulLayer(layer: String) {
        val key = FpsLayerMatcher.key(layer)
        if (key != preferredKey) {
            preferredKey = key
            preferences.rememberFpsLayer(target, key)
        }
    }
    private val args = Shizuku.UserServiceArgs(ComponentName(context, FpsShellService::class.java))
        .tag("fps-monitor-${System.nanoTime()}").daemon(false).processNameSuffix("fps_monitor")
        .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    @Volatile private var remote: IFpsShell? = null
    private var binding = false
    private var bindingAtNs = 0L
    @Volatile private var closed = false
    @Volatile var choice = FpsLayerChoice()
        private set

    @Synchronized
    fun selectLayer(layer: String?): FpsLayerChoice {
        if (choice.layer != layer) choice = FpsLayerChoice(layer, choice.revision + 1)
        return choice
    }
    private var candidates = emptyList<String>()
    private var listedAtNs = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            remote = IFpsShell.Stub.asInterface(binder)
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
        }
    }

    suspend fun read(): FpsReading {
        if (target.isBlank()) return FpsReading("请先结束会话并选择目标游戏")
        if (!context.getSystemService(PowerManager::class.java).isInteractive) return FpsReading("屏幕已关闭")
        FpsPermission.problem()?.let {
            withContext(Dispatchers.Main) { disconnect() }
            return FpsReading(it)
        }
        var stage = "连接 Shizuku"
        var selected = choice.layer
        try {
            if (!loadedPreference) {
                try { preferredKey = preferences.fpsApps.first()[target]?.successfulLayerKey }
                catch (_: java.io.IOException) { android.util.Log.w("ShizukuFrames", "FPS preference unavailable; using automatic selection") }
                loadedPreference = true
            }
            withContext(Dispatchers.Main) {
                if (binding && System.nanoTime() - bindingAtNs > 5_000_000_000L) disconnect()
                if (!closed && remote == null && !binding) {
                    binding = true
                    bindingAtNs = System.nanoTime()
                    try { Shizuku.bindUserService(args, connection) }
                    catch (t: RuntimeException) { binding = false; throw t }
                }
            }
            val shell = remote ?: return FpsReading("正在连接 Shizuku 监测服务", candidates = candidates)
            stage = "读取前台焦点"
            val foreground = SurfaceFlingerData.foregroundPackage(result(shell.focus()))
            if (foreground != target) {
                listedAtNs = 0
                return FpsReading(if (foreground == null) "无法确认前台目标,暂停统计" else "目标游戏不在前台,暂停统计",
                    candidates = candidates)
            }
            val now = System.nanoTime()
            if (now - listedAtNs > 2_000_000_000L || listedAtNs == 0L) {
                stage = "读取图层列表"
                candidates = SurfaceFlingerData.layers(result(shell.layers()), target)
                listedAtNs = now
            }
            val requested = choice.layer
            val layer = if (requested != null) requested.takeIf { it in candidates }
                ?: FpsLayerMatcher.match(candidates, FpsLayerMatcher.key(requested))
                else FpsLayerMatcher.automatic(candidates, preferredKey)
            if (layer == null) return FpsReading(
                when {
                    candidates.isEmpty() -> "未找到目标呈现图层"
                    requested != null -> "所选图层已消失,请重新选择"
                    else -> "存在多个候选图层,请在详情中选择游戏画面图层"
                }, candidates = candidates,
            )
            selected = layer
            stage = "读取呈现时间戳"
            val batch = SurfaceFlingerData.presentationBatch(result(shell.latency(layer)))
            return if (batch.problem != null) FpsReading("没有有效呈现时间戳;详见下方诊断",
                candidates = candidates, issue = "$stage: ${batch.problem}", issueLayer = layer)
            else FpsReading("", layer, batch.times, candidates)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is android.os.DeadObjectException) withContext(Dispatchers.Main) { disconnect() }
            val detail = if (e is CommandFailure) e.detail else e.javaClass.simpleName
            return FpsReading("$stage 失败;详见下方诊断", candidates = candidates,
                issue = "$stage: $detail", issueLayer = selected)
        }
    }

    private class CommandFailure(val detail: String) : RuntimeException()

    private fun result(response: Array<String>): String {
        if (response.size != 2) throw CommandFailure("诊断返回格式不匹配,请结束后重开会话")
        if (response[0].isNotEmpty()) throw CommandFailure(response[0])
        return response[1]
    }

    private fun disconnect() {
        if (binding || remote != null) runCatching { Shizuku.unbindUserService(args, connection, true) }
        remote = null
        binding = false
    }

    fun close() {
        closed = true
        disconnect()
    }
}

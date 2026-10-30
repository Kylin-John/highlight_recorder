package com.highlightrecorder.monitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 这里的 times 只能来自 actualPresentTime;无效/不可用由 reason 表示。
data class FpsReading(
    val reason: String,
    val layer: String? = null,
    val times: List<Long> = emptyList(),
    val candidates: List<String> = emptyList(),
    val issue: String? = null,
    val issueLayer: String? = null,
)

data class FpsLayerChoice(val layer: String? = null, val revision: Long = 0)

data class FpsLive(
    val active: Boolean = false,
    val targetPackage: String = "",
    val recording: Boolean = false,
    val fps: Double? = null,
    val status: String = "尚未开始监测",
    val layer: String? = null,
    val candidates: List<String> = emptyList(),
    val points: List<FpsPoint> = emptyList(),
    val sampledAtNs: Long = 0L,
    val choice: FpsLayerChoice = FpsLayerChoice(),
    val lastIssue: String? = null,
    val issueAtSeconds: Double? = null,
    val issueLayer: String? = null,
) {
    fun currentFps(nowNs: Long): Double? = fps.takeIf {
        active && nowNs >= sampledAtNs && nowNs - sampledAtNs <= 1_500_000_000L
    }
}

/** 单一采样循环;显示设置不在输入里,重复 start 不改变统计,close 后的读取结果不能回调。 */
class FpsSamplingSession(
    private val target: String,
    private val recording: Boolean,
    private val read: suspend () -> FpsReading,
    private val onUpdate: (FpsLive) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val selection: () -> FpsLayerChoice = { FpsLayerChoice() },
) {
    private val startedNs = nanoTime()
    private val startedMs = wallTime()
    private val stats = PresentationStats(startedNs)
    private var job: Job? = null
    @Volatile private var closed = false
    private var stoppedNs: Long? = null
    private var status = "等待目标游戏"
    private var publishedNs = Long.MIN_VALUE
    private var lastChoice = selection()
    private var lastIssue: String? = null
    private var issueAtSeconds: Double? = null
    private var issueLayer: String? = null

    fun start(scope: CoroutineScope) {
        if (closed || job != null) return
        job = scope.launch {
            while (isActive && !closed) {
                val began = nanoTime()
                val choice = selection()
                val reading = try { read() }
                catch (t: CancellationException) { throw t }
                catch (e: Exception) { FpsReading("读取失败;详见下方诊断",
                    issue = "采样调用: ${e.javaClass.simpleName}") }
                if (!isActive || closed) break
                // 用户选择已改变时,旧读取即使刚完成也不能覆盖新选择或计入统计。
                if (choice != selection()) continue
                val now = nanoTime()
                val live = synchronized(this@FpsSamplingSession) {
                    if (closed) return@synchronized null
                    if (choice != lastChoice) { stats.interrupt(); lastChoice = choice }
                    if (reading.layer == null) stats.interrupt()
                    else stats.accept(reading.layer, reading.times, now)
                    val fps = if (reading.layer == null) null else stats.liveFps(now)
                    status = reading.reason.ifBlank { if (fps == null) "等待新的有效呈现帧;静止或数据未更新" else "正在读取实际呈现时间戳" }
                    if (reading.issue != null) {
                        lastIssue = reading.issue
                        issueAtSeconds = (now - startedNs) / 1e9
                        issueLayer = reading.issueLayer ?: reading.layer ?: choice.layer
                    }
                    stats.point(now, fps != null)
                    val previousStatus = lastLive?.status
                    val publish = publishedNs == Long.MIN_VALUE || now - publishedNs >= 1_000_000_000L ||
                        previousStatus != status || lastLive?.choice != choice
                    FpsLive(true, target, recording, fps, status, reading.layer, reading.candidates,
                        if (publish) stats.curve() else emptyList(), now, choice,
                        lastIssue, issueAtSeconds, issueLayer).takeIf { publish }?.also {
                        publishedNs = now
                        lastLive = it
                    }
                }
                if (!closed && live != null) onUpdate(live)
                // 前台有效时最多 2Hz;无目标/权限/关屏时 0.5Hz。不并发、不积压查询。
                val interval = if (reading.layer != null) 500L else 2_000L
                delay((interval - (nanoTime() - began) / 1_000_000L).coerceAtLeast(100L))
            }
        }
    }

    private var lastLive: FpsLive? = null

    @Synchronized
    fun report(): FpsReport {
        val now = stoppedNs ?: nanoTime()
        return FpsReport(target, recording, startedMs, (now - startedNs) / 1e9,
            stats.summary(now), stats.curve(), stats.interruptions, status, lastIssue, issueAtSeconds, issueLayer)
    }

    @Synchronized
    fun close() {
        if (closed) return
        stoppedNs = nanoTime()
        closed = true
        job?.cancel()
    }
}

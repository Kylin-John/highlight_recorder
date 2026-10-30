package com.highlightrecorder.monitor

import kotlin.math.ceil

/** 只接受 SurfaceFlinger 第二列 actualPresentTime;第一行是刷新周期,绝不是游戏 FPS。 */
object SurfaceFlingerData {
    private val whitespace = Regex("\\s+")
    data class PresentationBatch(val times: List<Long>, val problem: String? = null)

    fun presentationTimes(text: String): List<Long> = presentationBatch(text).times

    fun presentationBatch(text: String): PresentationBatch {
        fun failed(reason: String) = PresentationBatch(emptyList(), reason)
        if (text.contains("Permission Denial", ignoreCase = true)) return failed("系统拒绝读取呈现时间戳")
        if (text.lineSequence().firstOrNull()?.trim()?.toLongOrNull()?.let { it > 0 } != true) {
            return failed("没有有效的刷新周期头;输出为空或格式不受支持")
        }
        val result = mutableListOf<Long>()
        var missingAfterValid = false
        var rows = 0
        var empty = 0
        var pending = 0
        for (line in text.lineSequence().drop(1).filter { it.isNotBlank() }) {
            rows++
            val parts = line.trim().split(whitespace)
            if (parts.size != 3 || parts.any { it.toLongOrNull() == null }) return failed("第 $rows 行帧数据格式不受支持")
            val actual = parts[1].toLong()
            if (actual <= 0 || actual == Long.MAX_VALUE) {
                if (actual == Long.MAX_VALUE) pending++ else empty++
                if (result.isNotEmpty()) missingAfterValid = true
            } else {
                if (missingAfterValid) return failed("有效呈现时间戳中间存在缺口,本批不计入统计")
                result.add(actual)
            }
        }
        if (rows == 0) return failed("只返回刷新周期,没有逐帧呈现时间戳")
        if (result.isEmpty()) return failed("返回 $rows 行,有效呈现 0 行（空槽 $empty,待呈现 $pending）")
        return PresentationBatch(result.distinct().sorted())
    }

    fun foregroundPackage(text: String): String? =
        Regex("mCurrentFocus=Window\\{[^\\n]*?\\s([a-zA-Z0-9_.]+)/[^\\s}]+")
            .findAll(text).map { it.groupValues[1] }.distinct().toList().singleOrNull()

    fun layers(text: String, target: String): List<String> {
        if (target.isBlank()) return emptyList()
        val packageToken = Regex("(?<![a-zA-Z0-9_.])${Regex.escape(target)}(?=[/\\s)}#]|$)")
        return text.lineSequence().map { raw ->
            raw.trim().removePrefix("RequestedLayerState{")
                .substringBefore(" parentId=").substringBefore(" relativeParentId=")
                .substringBefore(" z=").substringBefore(" mirrorId=")
                .substringBefore(" !handle").substringBefore(" layerStack=").removeSuffix("}").trim()
        }.filter { name ->
            packageToken.containsMatchIn(name) && name.length <= 1024 &&
                listOf("ActivityRecord", "InputSink", "Background for", "Bounds for", "Dim Layer",
                    "StartingWindow", "Transition", "leash").none { name.contains(it, ignoreCase = true) }
        }.distinct().take(32).toList()
    }

    /** 优先匹配唯一 BLAST 画面图层,不在多个相同类型的窗口间猜选。 */
    fun automaticLayer(candidates: List<String>): String? {
        return FpsLayerMatcher.automatic(candidates, null)
    }
}

data class FpsPoint(val seconds: Double, val fps: Double?, val segment: Int)
data class FpsSummary(
    val frameIntervals: Int = 0,
    val validSeconds: Double = 0.0,
    val fromSeconds: Double? = null,
    val toSeconds: Double? = null,
    val meanFps: Double? = null,
    val onePercentLow: Double? = null,
)

data class FpsReport(
    val targetPackage: String,
    val recording: Boolean,
    val startedAtMs: Long,
    val durationSeconds: Double,
    val summary: FpsSummary,
    val points: List<FpsPoint>,
    val interruptions: Int,
    val lastStatus: String,
    val lastIssue: String? = null,
    val issueAtSeconds: Double? = null,
    val issueLayer: String? = null,
)

/** 最近 5 分钟/最多 72000 个真实帧间隔,约 1.1 MiB 原始数组;不保留视频帧。 */
class PresentationStats(private val startNs: Long) {
    companion object {
        const val WINDOW_NS = 300_000_000_000L
        const val CAPACITY = 72_000
    }
    private val ends = LongArray(CAPACITY)
    private val durations = LongArray(CAPACITY)
    private var head = 0
    private var size = 0
    private var lastPresent: Long? = null
    private var liveStartNs = startNs
    private var layer: String? = null
    private var segment = 0
    private var interrupted = false
    var interruptions = 0
        private set
    private val points = ArrayDeque<FpsPoint>()
    private var lastPointNs = Long.MIN_VALUE

    /** 无法确认目标/数据时断开桥接,下一次先建基线,不跨缺口制造帧间隔。 */
    fun interrupt() {
        if (!interrupted) {
            if (lastPresent != null) interruptions++
            segment++
            interrupted = true
        }
        lastPresent = null
        layer = null
    }

    fun accept(layerName: String, timestamps: List<Long>, nowNs: Long): Boolean {
        val times = timestamps.filter { it >= startNs && it <= nowNs }.distinct().sorted()
        if (times.isEmpty()) {
            interrupt()
            return false
        }
        if (layer != layerName) {
            if (layer != null) interrupt()
            layer = layerName
            // 首次/换图层/恢复只建基线,不计入读到的历史帧。
            lastPresent = times.last()
            liveStartNs = times.last()
            interrupted = false
            return false
        }
        val previous = lastPresent ?: times.last().also { lastPresent = it }
        if (times.last() < previous) {
            interrupt()
            return false
        }
        val newer = times.filter { it > previous }
        if (newer.isEmpty()) return false
        var from = previous
        if (previous !in times) {
            // 上次帧已被有限历史覆盖,缺口两端不相连;仅接收本批内部的真实间隔。
            interruptions++
            segment++
            from = newer.first()
            liveStartNs = from
        }
        for (end in newer) {
            if (end > from) append(end, end - from)
            from = end
        }
        lastPresent = times.last()
        prune(nowNs)
        return true
    }

    fun liveFps(nowNs: Long): Double? {
        // 已知中断时不能把上一段的数值继续显示成实时值。
        if (interrupted) return null
        var count = 0
        var sum = 0L
        for (i in size - 1 downTo 0) {
            val index = (head + i) % CAPACITY
            if (ends[index] < nowNs - 1_000_000_000L || ends[index] <= liveStartNs) break
            count++
            sum += durations[index]
        }
        return if (count > 0 && sum > 0) count * 1e9 / sum else null
    }

    fun point(nowNs: Long, available: Boolean) {
        if (lastPointNs != Long.MIN_VALUE && nowNs - lastPointNs < 1_000_000_000L) return
        if (lastPointNs != Long.MIN_VALUE && nowNs - lastPointNs > 1_500_000_000L) segment++
        lastPointNs = nowNs
        points.addLast(FpsPoint((nowNs - startNs) / 1e9, if (available) liveFps(nowNs) else null, segment))
        while (points.size > 300 || (points.firstOrNull()?.seconds ?: 0.0) < (nowNs - startNs - WINDOW_NS) / 1e9) {
            points.removeFirst()
        }
    }

    fun curve(): List<FpsPoint> = points.toList()

    /** 只在用户查看统计或结束会话时排序一次;不在采样热路径排序。 */
    fun summary(nowNs: Long): FpsSummary {
        prune(nowNs)
        if (size == 0) return FpsSummary()
        val values = LongArray(size) { durations[(head + it) % CAPACITY] }
        val sum = values.sum().toDouble()
        val firstStart = ends[head] - durations[head]
        val lastEnd = ends[(head + size - 1) % CAPACITY]
        values.sortDescending()
        val tailCount = ceil(size * 0.01).toInt()
        val low = if (size >= 100) tailCount * 1e9 / values.take(tailCount).sum().toDouble() else null
        return FpsSummary(size, sum / 1e9, (firstStart - startNs) / 1e9,
            (lastEnd - startNs) / 1e9, size * 1e9 / sum, low)
    }

    private fun append(end: Long, duration: Long) {
        if (size == CAPACITY) { head = (head + 1) % CAPACITY; size-- }
        val index = (head + size) % CAPACITY
        ends[index] = end
        durations[index] = duration
        size++
    }

    private fun prune(nowNs: Long) {
        while (size > 0 && ends[head] - durations[head] < nowNs - WINDOW_NS) {
            head = (head + 1) % CAPACITY
            size--
        }
    }
}

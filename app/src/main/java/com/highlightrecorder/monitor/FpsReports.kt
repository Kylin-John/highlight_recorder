package com.highlightrecorder.monitor

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 仅最近一次结束的报告落盘一次;无原始帧、视频、网络或持续写盘。 */
object FpsReports {
    val live = MutableStateFlow(FpsLive())
    val current = MutableStateFlow<FpsReport?>(null)
    val latest = MutableStateFlow<FpsReport?>(null)
    val storageProblem = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loaded = false

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "last-fps-session.json"))

    fun complete(context: Context, session: FpsSamplingSession) {
        scope.launch { save(context, session.report()) }
    }

    fun load(context: Context) {
        scope.launch { mutex.withLock { loadLocked(context) } }
    }

    private fun loadLocked(context: Context) {
        if (loaded) return
        loaded = true
        val file = file(context)
        if (!file.baseFile.exists()) return
        try {
            check(file.baseFile.length() <= 131_072)
            val o = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val s = o.getJSONObject("summary")
            val a = o.getJSONArray("points")
            fun nullable(o: JSONObject, key: String) = if (o.isNull(key)) null else o.getDouble(key)
            latest.value = FpsReport(o.getString("target"), o.getBoolean("recording"),
                o.getLong("started"), o.getDouble("duration"),
                FpsSummary(s.getInt("count"), s.getDouble("valid"), nullable(s, "from"), nullable(s, "to"),
                    nullable(s, "mean"), nullable(s, "low")),
                List(a.length()) { i -> a.getJSONObject(i).let {
                    FpsPoint(it.getDouble("seconds"), nullable(it, "fps"), it.getInt("segment"))
                } }, o.getInt("interruptions"), o.getString("status"),
                if (o.isNull("issue")) null else o.getString("issue"), nullable(o, "issueAt"),
                if (o.isNull("issueLayer")) null else o.getString("issueLayer"))
        } catch (_: Exception) { storageProblem.value = "上次监测报告无法读取" }
    }

    fun save(context: Context, report: FpsReport) {
        scope.launch { mutex.withLock {
            loadLocked(context)
            if ((latest.value?.startedAtMs ?: Long.MIN_VALUE) > report.startedAtMs) return@withLock
            latest.value = report
            val summary = report.summary
            val s = JSONObject().put("count", summary.frameIntervals).put("valid", summary.validSeconds)
                .put("from", summary.fromSeconds ?: JSONObject.NULL).put("to", summary.toSeconds ?: JSONObject.NULL)
                .put("mean", summary.meanFps ?: JSONObject.NULL).put("low", summary.onePercentLow ?: JSONObject.NULL)
            val a = JSONArray()
            report.points.forEach { a.put(JSONObject().put("seconds", it.seconds)
                .put("fps", it.fps ?: JSONObject.NULL).put("segment", it.segment)) }
            val o = JSONObject().put("target", report.targetPackage).put("recording", report.recording)
                .put("started", report.startedAtMs).put("duration", report.durationSeconds)
                .put("summary", s).put("points", a).put("interruptions", report.interruptions)
                .put("status", report.lastStatus)
                .put("issue", report.lastIssue ?: JSONObject.NULL)
                .put("issueAt", report.issueAtSeconds ?: JSONObject.NULL)
                .put("issueLayer", report.issueLayer ?: JSONObject.NULL)
            val file = file(context)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(o.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                storageProblem.value = null
            } catch (_: Exception) {
                file.failWrite(stream)
                storageProblem.value = "报告暂留内存,本次写入失败"
            }
        } }
    }
}

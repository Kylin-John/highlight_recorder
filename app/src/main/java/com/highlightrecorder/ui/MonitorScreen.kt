package com.highlightrecorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.highlightrecorder.monitor.FpsPoint
import com.highlightrecorder.monitor.FpsReport
import com.highlightrecorder.service.RecordingService
import java.text.DateFormat
import java.util.Date

@Composable
fun MonitorScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val live by viewModel.fpsLive.collectAsStateWithLifecycle()
    val current by viewModel.currentFpsReport.collectAsStateWithLifecycle()
    val latest by viewModel.latestFpsReport.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val targets by viewModel.monitorTargets.collectAsStateWithLifecycle()
    val permissionProblem by viewModel.fpsPermissionProblem.collectAsStateWithLifecycle()
    val storageProblem by viewModel.fpsStorageProblem.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    var showDetails by remember { mutableStateOf(false) }
    var showReportDetails by remember { mutableStateOf(false) }
    val targetPackage = if (live.active) live.targetPackage else settings.fpsTargetPackage
    val targetLabel = targets.firstOrNull { it.packageName == targetPackage }?.label
        ?: targetPackage.ifBlank { "尚未选择，请返回主页选择游戏" }
    LifecycleResumeEffect(live.active) {
        if (live.active) viewModel.refreshFpsReport()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("游戏帧率监测", style = MaterialTheme.typography.headlineSmall)
        Text("${if (live.active) "本次游戏" else "当前游戏"} · $targetLabel",
            style = MaterialTheme.typography.titleMedium)
        permissionProblem?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = viewModel::requestFpsPermission) { Text("请求 Shizuku 授权") }
        }
        if (live.active) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (live.recording) "录制与监测运行中" else "独立监测中 · 未录像")
                    Text("${number(live.fps)} FPS", style = MaterialTheme.typography.headlineMedium)
                    Text(monitorStatus(live.status))
                }
            }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "收起图层与诊断" else "图层与诊断")
            }
            if (showDetails) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (live.choice.layer == null) "自动选择" else "手动选择已接收")
                        live.layer?.let { Text("当前画面: $it", style = MaterialTheme.typography.bodySmall) }
                        FilterChip(selected = live.choice.layer == null,
                            onClick = { viewModel.selectFpsLayer(null) }, label = { Text("自动选择") })
                        live.candidates.forEach { layer ->
                            val selected = live.choice.layer == layer || (live.choice.layer != null && live.layer == layer)
                            FilterChip(selected = selected,
                                onClick = { viewModel.selectFpsLayer(layer) },
                                label = { Text((if (selected) "已选择 · " else "") + layer,
                                    style = MaterialTheme.typography.bodySmall) })
                        }
                        live.lastIssue?.let { issue ->
                            Text("最近问题 · 第 ${number(live.issueAtSeconds)} 秒", style = MaterialTheme.typography.titleSmall)
                            Text(issue, color = MaterialTheme.colorScheme.error)
                            live.issueLayer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            FpsCurve(live.points)
            OutlinedButton(onClick = viewModel::refreshFpsReport) { Text("更新统计") }
            current?.let { ReportSummary(it) }
            if (state == RecordingService.State.Monitoring) {
                Button(onClick = viewModel::requestStop, modifier = Modifier.fillMaxWidth()) { Text("结束监测并保留报告") }
            } else Text("录制与监测运行中", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onClick = viewModel::requestMonitoring,
                enabled = settings.fpsTargetPackage.isNotBlank() && permissionProblem == null &&
                    permissions.overlay && permissions.notification &&
                    state != RecordingService.State.Recording && state != RecordingService.State.Saving,
                modifier = Modifier.fillMaxWidth()) { Text("开始独立监测") }
            if (!permissions.overlay || !permissions.notification) Text("独立监测需要悬浮窗与通知权限;请从主页引导开启。")
        }
        latest?.let {
            Text("最近结束的会话", style = MaterialTheme.typography.titleMedium)
            Text(DateFormat.getDateTimeInstance().format(Date(it.startedAtMs)))
            Text(targets.firstOrNull { app -> app.packageName == it.targetPackage }?.label ?: it.targetPackage)
            ReportSummary(it)
            FpsCurve(it.points)
            if (it.lastIssue != null) {
                TextButton(onClick = { showReportDetails = !showReportDetails }) { Text("报告诊断") }
                if (showReportDetails) Text(it.lastIssue, style = MaterialTheme.typography.bodySmall)
            }
        }
        storageProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@Composable
private fun ReportSummary(report: FpsReport) {
    val summary = report.summary
    Text("会话 ${number(report.durationSeconds)} 秒 · 有效监测 ${number(summary.validSeconds)} 秒")
    Text("均值 ${number(summary.meanFps)} FPS · 1% low ${number(summary.onePercentLow)} FPS")
    Text("统计范围 ${number(summary.fromSeconds)}～${number(summary.toSeconds)} 秒 · 中断 ${report.interruptions} 次",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FpsCurve(points: List<FpsPoint>) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val maxFps = maxOf(60.0, points.mapNotNull { it.fps }.maxOrNull() ?: 60.0)
    val from = points.firstOrNull()?.seconds ?: 0.0
    val to = points.lastOrNull()?.seconds ?: from
    Text("FPS 曲线 · 第 ${number(from)}～${number(to)} 秒 · 纵轴 0～${number(maxFps)} FPS",
        style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
        val path = Path()
        var connected = false
        var previousSegment = -1
        points.forEach { point ->
            val fps = point.fps
            if (fps == null) connected = false else {
                val x = ((point.seconds - from) / (to - from).coerceAtLeast(1.0) * size.width).toFloat()
                val y = (size.height * (1 - fps / maxFps)).toFloat()
                if (!connected || previousSegment != point.segment) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color, 2.dp.toPx(), Offset(x, y))
                connected = true
            }
            previousSegment = point.segment
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
    if (points.none { it.fps != null }) Text("尚无有效曲线数据", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(2.dp))
}

private fun number(value: Double?): String = value?.let { "%.1f".format(it) } ?: "—"

/** 主页面仅显示短状态;原始诊断保留在折叠区。 */
private fun monitorStatus(status: String): String = when {
    status == "正在读取实际呈现时间戳" -> "监测中"
    status.contains("多个候选") || status.contains("所选图层已消失") -> "需要选择画面 · 打开图层与诊断"
    status.contains("目标游戏不在前台") -> "等待目标游戏"
    status.contains("有效呈现帧") -> "等待画面更新"
    status.contains("失败") || status.contains("诊断") || status.contains("过期") -> "暂无有效数据 · 查看诊断"
    else -> status
}

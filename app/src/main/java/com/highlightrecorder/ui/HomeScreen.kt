package com.highlightrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.highlightrecorder.data.SaveMode
import com.highlightrecorder.service.RecordingService

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestStartRecording: () -> Unit,
    onGoOnboarding: () -> Unit,
    onGoSettings: () -> Unit,
    onGoLibrary: () -> Unit,
    onGoMonitor: () -> Unit,
) {
    val state by viewModel.recordingState.collectAsState()
    val buffered by viewModel.bufferedSeconds.collectAsState()
    val estimated by viewModel.estimatedSaveSeconds.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val perms by viewModel.permissions.collectAsState()
    val targets by viewModel.monitorTargets.collectAsStateWithLifecycle()
    val live by viewModel.fpsLive.collectAsStateWithLifecycle()
    var choosingGame by remember { mutableStateOf(false) }
    val recording = state == RecordingService.State.Recording ||
        state == RecordingService.State.Saving
    val monitoring = state == RecordingService.State.Monitoring
    val targetLocked = recording || monitoring || live.active
    val targetPackage = if (live.active) live.targetPackage else settings.fpsTargetPackage
    val targetLabel = targets.firstOrNull { it.packageName == targetPackage }?.label
        ?: targetPackage.ifBlank { "选择游戏" }
    val rewind = settings.rewindSeconds
    val saveMode = settings.saveMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("高光回录", style = MaterialTheme.typography.headlineSmall)
            Text(
                "  by owen88ob",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
        }

        if (!perms.mandatoryOk) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("有必需权限未授予,录制不可用", color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onGoOnboarding) { Text("重新引导") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (state) {
                        is RecordingService.State.Recording -> "循环录制中"
                        is RecordingService.State.Saving -> "正在保存…"
                        is RecordingService.State.Monitoring -> "独立监测中(未录制)"
                        is RecordingService.State.Error ->
                            "出错: ${(state as RecordingService.State.Error).message}"
                        else -> "未在录制"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (recording) "已缓存 $buffered 秒 / 最多回录 $rewind 秒"
                    else if (monitoring) "不采集屏幕、不编码音视频、不建立录像缓冲"
                    else "开始后将在后台持续缓冲最近 $rewind 秒",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (recording) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "现在点击保存，预计生成 $estimated 秒视频",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Button(
            onClick = {
                if (recording) viewModel.requestStop() else onRequestStartRecording()
            },
            enabled = perms.mandatoryOk && !monitoring,
            colors = if (recording) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (recording) "停止录制" else "开始循环录制")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("监测游戏", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(
                        onClick = { choosingGame = true },
                        enabled = !targetLocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(targetLabel, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = choosingGame && !targetLocked,
                        onDismissRequest = { choosingGame = false },
                        modifier = Modifier.height(320.dp),
                    ) {
                        targets.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target.label) },
                                trailingIcon = if (target.packageName == targetPackage) {
                                    { Icon(Icons.Default.Check, contentDescription = "已选择") }
                                } else null,
                                onClick = {
                                    choosingGame = false
                                    viewModel.updateSettings { it.copy(fpsTargetPackage = target.packageName) }
                                },
                            )
                        }
                    }
                }
                if (targetLocked) Text("结束当前会话后可更换游戏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedButton(onClick = onGoMonitor, modifier = Modifier.fillMaxWidth()) {
            Text(if (monitoring) "查看 / 结束监测" else "游戏帧率监测 / 最近报告")
        }

        OutlinedButton(
            onClick = { viewModel.requestSave() },
            enabled = state == RecordingService.State.Recording,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saveMode == SaveMode.CONTINUATION) "接续保存(最多 $rewind 秒)" else "立即保存最近 $rewind 秒")
        }

        Text(
            "提示:录制中悬浮球会一直显示,单击悬浮球即可保存,无需回到本页。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "防杀提示:切到后台后,在最近任务页下滑本 App 卡片锁定后台;再长按桌面图标(或系统设置→应用管理)进入应用信息,把省电策略设为「无限制」,长时间录制更稳。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onGoSettings, modifier = Modifier.weight(1f)) { Text("设置") }
            OutlinedButton(onClick = onGoLibrary, modifier = Modifier.weight(1f)) { Text("视频库") }
        }
    }
}

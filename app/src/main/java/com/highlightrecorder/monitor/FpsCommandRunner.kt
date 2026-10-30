package com.highlightrecorder.monitor

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** UserService 内部的有界读取,结果经 Binder 明确传递,不把命令失败藏进异常。 */
internal class FpsCommandRunner(
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    private val timeouts = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var process: Process? = null

    @Synchronized
    fun run(args: List<String>, focusOnly: Boolean = false): Array<String> {
        val p = try { start(listOf("/system/bin/dumpsys", "-t", "1") + args) }
        catch (e: Exception) { return arrayOf("启动命令失败: ${e.javaClass.simpleName}", "") }
        process = p
        val timedOut = AtomicBoolean(false)
        val timeout = timeouts.schedule({ timedOut.set(true); p.destroyForcibly() }, 2, TimeUnit.SECONDS)
        try {
            val output = StringBuilder()
            var readChars = 0
            p.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    readChars += line.length
                    if (readChars > 1_048_576) return arrayOf("读取输出超过 1 MiB 上限", "")
                    if (!focusOnly || line.contains("mCurrentFocus=")) {
                        if (output.length + line.length >= 131_072) return arrayOf("返回输出超过 128 KiB 上限", "")
                        output.append(line).append('\n')
                    }
                }
            }
            // EOF 不保证进程已退出;原来的 100ms 检查可能把延迟退出误报成失败。
            if (!p.waitFor(2, TimeUnit.SECONDS) || timedOut.get()) return arrayOf("命令超时（2 秒）", "")
            if (p.exitValue() != 0) return arrayOf("命令退出码 ${p.exitValue()}", "")
            val text = output.toString()
            if (text.contains("Permission Denial", ignoreCase = true)) return arrayOf("系统拒绝读取（Permission Denial）", "")
            if (text.contains("DUMP TIMEOUT", ignoreCase = true)) return arrayOf("系统 dumpsys 超时（1 秒）", "")
            return arrayOf("", text)
        } catch (e: Exception) {
            return arrayOf(if (timedOut.get()) "命令超时（2 秒）" else "读取命令失败: ${e.javaClass.simpleName}", "")
        } finally {
            timeout.cancel(false)
            p.destroy()
            process = null
        }
    }

    fun close() {
        process?.destroyForcibly()
        timeouts.shutdownNow()
    }
}

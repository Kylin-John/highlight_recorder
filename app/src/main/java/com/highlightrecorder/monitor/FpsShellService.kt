package com.highlightrecorder.monitor

import kotlin.system.exitProcess

/** Shizuku UserService:独立 shell 进程,仅执行三个固定的 dumpsys 读取。 */
class FpsShellService : IFpsShell.Stub() {
    private val commands = FpsCommandRunner()
    override fun focus(): Array<String> = commands.run(listOf("window", "displays"), focusOnly = true)
    override fun layers(): Array<String> = commands.run(listOf("SurfaceFlinger", "--list"))
    override fun latency(layer: String): Array<String> {
        if (layer.isBlank() || layer.length > 1024 || '\n' in layer || '\u0000' in layer) {
            return arrayOf("图层名称无效", "")
        }
        return commands.run(listOf("SurfaceFlinger", "--latency", layer))
    }
    override fun destroy() {
        commands.close()
        exitProcess(0)
    }
}

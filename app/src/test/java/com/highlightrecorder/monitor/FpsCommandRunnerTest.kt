package com.highlightrecorder.monitor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FpsCommandRunnerTest {
    private class StubProcess(val text: String, val code: Int = 0, val exits: Boolean = true) : Process() {
        var destroyed = false
        var waitMillis = 0L
        override fun getInputStream(): InputStream = ByteArrayInputStream(text.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitMillis = unit.toMillis(timeout)
            return exits && waitMillis >= 500
        }
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
    }

    @Test fun `EOF后延迟退出不能被旧100ms阈值误报为失败且图层作为单独参数传递`() {
        val p = StubProcess("16666666\n1 2 3\n")
        val layer = "SurfaceView[com.example.game/.Main] (BLAST)#123"
        var command = emptyList<String>()
        val runner = FpsCommandRunner { command = it; p }
        try {
            assertArrayEquals(arrayOf("", p.text), runner.run(listOf("SurfaceFlinger", "--latency", layer)))
            assertEquals(listOf("/system/bin/dumpsys", "-t", "1", "SurfaceFlinger", "--latency", layer), command)
            assertEquals(2000, p.waitMillis)
            assertTrue(p.destroyed)
        } finally { runner.close() }
    }

    @Test fun `非零退出和超时返回具体失败而非空成功`() {
        for ((p, message) in listOf(StubProcess("", 7) to "命令退出码 7", StubProcess("", exits = false) to "命令超时（2 秒）")) {
            val runner = FpsCommandRunner { p }
            try {
                assertArrayEquals(arrayOf(message, ""), runner.run(listOf("SurfaceFlinger", "--list")))
                assertTrue(p.destroyed)
            } finally { runner.close() }
        }
    }

    @Test fun `权限拒绝与系统自身超时被明确区分`() {
        for ((text, message) in listOf("Permission Denial: cannot dump" to "系统拒绝读取（Permission Denial）",
            "*** SERVICE DUMP TIMEOUT EXPIRED ***" to "系统 dumpsys 超时（1 秒）")) {
            val runner = FpsCommandRunner { StubProcess(text) }
            try { assertArrayEquals(arrayOf(message, ""), runner.run(listOf("SurfaceFlinger", "--list"))) }
            finally { runner.close() }
        }
    }

    @Test fun `超过返回上限的输出被拒绝并释放进程`() {
        val p = StubProcess("x".repeat(131_072))
        val runner = FpsCommandRunner { p }
        try {
            assertEquals("返回输出超过 128 KiB 上限", runner.run(listOf("SurfaceFlinger", "--list"))[0])
            assertTrue(p.destroyed)
        } finally { runner.close() }
    }

    @Test fun `前台读取只返回焦点行并保留读取总量限制`() {
        val runner = FpsCommandRunner { StubProcess("other window data\nmCurrentFocus=Window{1 u0 com.example.game/.Main}\n") }
        try {
            assertEquals("mCurrentFocus=Window{1 u0 com.example.game/.Main}\n", runner.run(listOf("window", "displays"), true)[1])
        } finally { runner.close() }
        val capped = FpsCommandRunner { StubProcess(("x".repeat(1024) + "\n").repeat(1025)) }
        try { assertEquals("读取输出超过 1 MiB 上限", capped.run(listOf("window", "displays"), true)[0]) }
        finally { capped.close() }
    }

    @Test fun `无法启动命令时返回异常类型不发送未筛选异常正文`() {
        val runner = FpsCommandRunner { throw IOException("unstructured failure") }
        try { assertArrayEquals(arrayOf("启动命令失败: IOException", ""), runner.run(listOf("window", "displays"))) }
        finally { runner.close() }
    }
}

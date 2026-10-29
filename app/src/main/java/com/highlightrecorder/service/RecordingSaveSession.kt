package com.highlightrecorder.service

import kotlinx.coroutines.CancellationException

/** 单次录制的导出流程,仅在主线程使用;停止后旧导出不能再回调或停止新录制。 */
class RecordingSaveSession {
    private var active = true
    var saving = false
        private set

    /** 返回是否受理;导出失败调用 onFailed,取消则向上传递。 */
    suspend fun <T> save(
        stopAfterSave: Boolean,
        write: suspend () -> T,
        onSaved: (T) -> Unit,
        onFailed: (Throwable) -> Unit,
        stopRecording: () -> Unit,
    ): Boolean {
        if (!active || saving) return false
        saving = true
        try {
            val result = write()
            if (active) {
                onSaved(result)
                if (stopAfterSave) stopRecording()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (active) onFailed(t)
        } finally {
            saving = false
        }
        return true
    }

    fun close() {
        active = false
    }
}

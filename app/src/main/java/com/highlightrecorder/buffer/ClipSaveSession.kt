package com.highlightrecorder.buffer

import com.highlightrecorder.data.SaveMode

/** 一个视频编码器生命周期的保存边界。重建编码器/清空缓冲时替换整个实例。 */
class ClipSaveSession(
    val videoBuffer: RingSegmentBuffer,
    private val audioBuffer: AudioRingBuffer,
) {
    class Snapshot internal constructor(
        val video: List<VideoSegment>,
        val audio: List<EncodedPacket>,
        val endPtsUs: Long,
        internal val owner: ClipSaveSession,
    )

    private var lastSavedEndPtsUs: Long? = null

    /** 在接受请求时固定尾部;导出期间缓冲仍可继续接收编码包。 */
    @Synchronized
    fun snapshot(windowUs: Long, mode: SaveMode): Snapshot? {
        val from = if (mode == SaveMode.CONTINUATION) lastSavedEndPtsUs else null
        val video = videoBuffer.snapshot(windowUs, from)
        if (video.isEmpty()) return null
        val end = video.last().endPtsUs
        val audio = audioBuffer.snapshot(video.first().startPtsUs, end)
        return Snapshot(video, audio, end, this)
    }

    /** 仅在文件成功写出后调用;旧会话的完成回调不能推进当前会话。 */
    @Synchronized
    fun onSaved(snapshot: Snapshot) {
        if (snapshot.owner !== this) return
        lastSavedEndPtsUs = maxOf(lastSavedEndPtsUs ?: Long.MIN_VALUE, snapshot.endPtsUs)
    }
}

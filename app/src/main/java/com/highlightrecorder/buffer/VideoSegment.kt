package com.highlightrecorder.buffer

import java.util.Collections

/**
 * 一段以关键帧(IDR)开头、到下一个关键帧之前的连续编码数据。
 * 任何拼接都必须从 Segment 边界开始,否则解码会花屏。
 */
class VideoSegment(
    val startPtsUs: Long,
) {
    private val packetsInternal = ArrayList<EncodedPacket>(64)
    private var sealed = false

    /** 段内全部包(首包必为关键帧)。 */
    val packets: List<EncodedPacket> = Collections.unmodifiableList(packetsInternal)

    var endPtsUs: Long = startPtsUs
        private set

    val durationUs: Long get() = endPtsUs - startPtsUs

    val sizeBytes: Int get() = packetsInternal.sumOf { it.size }

    fun append(packet: EncodedPacket) {
        check(!sealed) { "segment is sealed" }
        packetsInternal.add(packet)
        if (packet.ptsUs > endPtsUs) endPtsUs = packet.ptsUs
    }

    internal fun seal(): VideoSegment {
        sealed = true
        return this
    }

    /** 已封闭分片直接共享;开放分片仅复制包引用,固定本次导出的尾部。 */
    internal fun snapshot(): VideoSegment {
        if (sealed) return this
        return VideoSegment(startPtsUs).also {
            it.packetsInternal.addAll(packetsInternal)
            it.endPtsUs = endPtsUs
            it.seal()
        }
    }
}

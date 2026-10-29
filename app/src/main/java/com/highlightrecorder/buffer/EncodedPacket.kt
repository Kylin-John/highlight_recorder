package com.highlightrecorder.buffer

/**
 * 一帧编码后的数据（视频 NAL 单元或 AAC 帧）。
 * [data] 为编码器输出的原始字节（不含 SPS/PPS 时由封装层另行处理）。
 * 构造后不再修改载荷,缓冲与导出快照共享同一份编码数据。
 */
class EncodedPacket(
    val data: ByteArray,
    val ptsUs: Long,
    val isKeyFrame: Boolean = false,
) {
    val size: Int get() = data.size
}

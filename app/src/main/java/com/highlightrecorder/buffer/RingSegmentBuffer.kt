package com.highlightrecorder.buffer

import java.util.ArrayDeque

/**
 * 视频环形分片缓冲:按关键帧切成 Segment,只保留最近 [capacityUs] 的画面。
 *
 * - 分片边界以编码器实际输出的关键帧为准,不假设 I 帧间隔配置一定生效;
 * - 首个包若不是关键帧会被丢弃,直到遇到第一个 IDR 才开始记录;
 * - [snapshot] 返回的分片起点必为 IDR,可直接拼接。
 *
 * 线程安全:所有公共方法加锁,编码器回调线程与保存线程可并发访问。
 */
class RingSegmentBuffer(
    /** 保留时长上限(微秒),通常取 回退时长N + 2s 富余。 */
    private val capacityUs: Long,
    /** 开放分片超过该时长未见到关键帧时,回调 [onSegmentOverrun] 请求编码器补 IDR。 */
    private val softSegmentUs: Long = 1_600_000L,
    /** 编码器始终不给关键帧时的硬上限:强制切段,防止内存无限增长(OOM)。 */
    private val hardSegmentUs: Long = 4_000_000L,
    /** 缓冲总字节安全阀:高码率+长回退时超出则从头部逐出(缩短实际可回退时长,防 OOM)。 */
    private val maxBytes: Int = 300 * 1024 * 1024,
) {
    /** 分片超时回调(由管线接到编码器 requestKeyFrame)。 */
    var onSegmentOverrun: (() -> Unit)? = null

    /** 字节安全阀逐出回调(诊断用,记录实际码率超过预估的场景)。 */
    var onByteBudgetEvict: ((totalBytes: Int) -> Unit)? = null

    private val lock = Any()
    private val segments = ArrayDeque<VideoSegment>()
    private var openSegment: VideoSegment? = null
    private var totalBytes: Int = 0
    private var lastOverrunNotifyUs = 0L

    /** 当前缓冲覆盖时长(微秒)。 */
    val bufferedDurationUs: Long
        get() = synchronized(lock) {
            val first = segments.peekFirst() ?: openSegment ?: return@synchronized 0L
            val last = openSegment ?: segments.peekLast() ?: return@synchronized 0L
            last.endPtsUs - first.startPtsUs
        }

    val bufferedBytes: Int get() = synchronized(lock) { totalBytes }

    val segmentCount: Int get() = synchronized(lock) { segments.size }

    /** 只读分片起尾元数据,不创建快照或复制编码包列表。 */
    fun estimatedDurationUs(windowUs: Long, fromPtsUs: Long? = null): Long = synchronized(lock) {
        val newestEnd = (openSegment ?: segments.peekLast())?.endPtsUs ?: return@synchronized 0L
        val start = snapshotStartLocked(newestEnd, windowUs, fromPtsUs) ?: return@synchronized 0L
        (newestEnd - start.startPtsUs).coerceAtLeast(0L)
    }

    fun onPacket(packet: EncodedPacket) {
        synchronized(lock) {
            if (packet.isKeyFrame) {
                openSegment?.let { segments.addLast(it.seal()) }
                openSegment = VideoSegment(packet.ptsUs)
                evictLocked()
            } else if (openSegment == null) {
                // 还没见到第一个关键帧,丢弃无法解码的头
                return
            } else {
                val seg = openSegment!!
                val span = packet.ptsUs - seg.startPtsUs
                if (span > hardSegmentUs) {
                    // 编码器迟迟不发 IDR:强制切段兜底,否则内存无限增长
                    segments.addLast(seg.seal())
                    openSegment = VideoSegment(packet.ptsUs)
                    evictLocked()
                } else if (span > softSegmentUs &&
                    packet.ptsUs - lastOverrunNotifyUs > 1_000_000L
                ) {
                    lastOverrunNotifyUs = packet.ptsUs
                    onSegmentOverrun?.invoke()
                }
            }
            val seg = openSegment!!
            seg.append(packet)
            totalBytes += packet.size
        }
    }

    /**
     * 取最近 [windowUs] 微秒的分片快照(含正在写入的开放分片)。
     * [fromPtsUs] 为上次成功保存的媒体尾部,起点向前对齐实际 IDR,允许少量重叠。
     * 返回的分片与包列表不再变化,编码载荷不复制。
     */
    fun snapshot(windowUs: Long, fromPtsUs: Long? = null): List<VideoSegment> = synchronized(lock) {
        val all = segments.toMutableList()
        openSegment?.takeIf { it.packets.isNotEmpty() }?.let { all.add(it) }
        if (all.isEmpty()) return@synchronized emptyList()

        val start = snapshotStartLocked(all.last().endPtsUs, windowUs, fromPtsUs)
            ?: return@synchronized emptyList()
        val idx = all.indexOf(start)
        all.subList(idx, all.size).map { it.snapshot() }
    }

    /** 预计时长与实际快照共用起点选择,包含逐出与非 IDR 强制切段后的实际可用范围。 */
    private fun snapshotStartLocked(newestEnd: Long, windowUs: Long, fromPtsUs: Long?): VideoSegment? {
        if (fromPtsUs != null && newestEnd <= fromPtsUs) return null
        val cutoff = maxOf(newestEnd - windowUs, fromPtsUs ?: Long.MIN_VALUE)
        var firstIdr: VideoSegment? = null
        var start: VideoSegment? = null
        for (segment in segments) {
            if (!segment.packets.first().isKeyFrame) continue
            if (firstIdr == null) firstIdr = segment
            // 截止点在两帧之间时仍向前找 IDR,避免漏掉期间画面。
            if (segment.startPtsUs <= cutoff) start = segment
        }
        openSegment?.takeIf { it.packets.firstOrNull()?.isKeyFrame == true }?.let {
            if (firstIdr == null) firstIdr = it
            if (it.startPtsUs <= cutoff) start = it
        }
        return start ?: firstIdr
    }

    fun clear() = synchronized(lock) {
        segments.clear()
        openSegment = null
        totalBytes = 0
    }

    private fun evictLocked() {
        // 新分片加入后,若总跨度超容量,从头部逐出(至少留 1 个已封闭分片)
        while (segments.size > 1) {
            val first = segments.peekFirst() ?: break
            val newestEnd = openSegment?.startPtsUs ?: segments.peekLast()?.endPtsUs ?: break
            if (newestEnd - first.startPtsUs <= capacityUs) break
            totalBytes -= segments.removeFirst().sizeBytes
        }
        // 字节安全阀:超出预算同样从头部逐出(至少留 1 个)
        while (segments.size > 1 && totalBytes > maxBytes) {
            totalBytes -= segments.removeFirst().sizeBytes
            onByteBudgetEvict?.invoke(totalBytes)
        }
    }
}

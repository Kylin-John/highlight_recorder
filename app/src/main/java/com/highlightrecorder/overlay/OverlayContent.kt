package com.highlightrecorder.overlay

import com.highlightrecorder.data.OverlayDisplay
import kotlin.math.roundToInt

/** 单一主体内容;未知值用破折号,绝不拿编码 FPS/刷新率补成游戏 FPS。 */
fun overlayContent(display: OverlayDisplay, fps: Double?, estimatedSeconds: Int?): String = when (display) {
    OverlayDisplay.SAVE -> "存"
    OverlayDisplay.FPS -> fps?.takeIf { it.isFinite() && it >= 0 }?.roundToInt()?.toString() ?: "—"
    OverlayDisplay.SAVE_SECONDS -> estimatedSeconds?.coerceAtLeast(0)?.toString() ?: "—"
}

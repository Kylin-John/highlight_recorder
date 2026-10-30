package com.highlightrecorder.data

/** 录制参数,由设置页持久化(DataStore),服务启动时读取。 */
data class RecordingSettings(
    /** 回退时长(秒)。 */
    val rewindSeconds: Int = 30,
    /** 缺省沿用每次保存最近 N 秒的行为。 */
    val saveMode: SaveMode = SaveMode.LAST_WINDOW,
    /** 0=跟随屏幕, 720/1080=短边像素。 */
    val resolutionShortEdge: Int = 720,
    val frameRate: Int = 30,
    /** 码率 bps。 */
    val videoBitrateBps: Int = 8_000_000,
    /** "video/avc" 或 "video/hevc"。 */
    val videoMime: String = "video/avc",
    /** 音频来源。 */
    val audioSource: AudioSource = AudioSource.INTERNAL,
    val overlayAlpha: Float = 0.85f,
    val overlayScale: Float = 1.0f,
    val overlayEdgeHide: Boolean = false,
    /** 彻底隐藏悬浮球(录制仍继续,从 App 设置恢复)。 */
    val overlayHidden: Boolean = false,
    /** 缺省沿用长按浮窗直接停止录制。 */
    val overlayLongPressAction: OverlayLongPressAction = OverlayLongPressAction.STOP,
    val overlayDisplay: OverlayDisplay = OverlayDisplay.SAVE,
    /** 在会话开始前选择目标,不随前台应用自动换成另一个游戏。 */
    val fpsTargetPackage: String = "",
    /** 回收站自动清理天数。 */
    val trashAutoDeleteDays: Int = 30,
)

enum class AudioSource { INTERNAL, MIC, INTERNAL_AND_MIC, MUTE }

enum class OverlayDisplay {
    SAVE, FPS, SAVE_SECONDS;

    companion object {
        fun fromPreference(value: String?): OverlayDisplay =
            entries.firstOrNull { it.name == value } ?: SAVE
    }
}

enum class SaveMode {
    LAST_WINDOW, CONTINUATION;

    companion object {
        fun fromPreference(value: String?): SaveMode =
            entries.firstOrNull { it.name == value } ?: LAST_WINDOW
    }
}

enum class OverlayLongPressAction {
    STOP, SAVE_AND_STOP;

    companion object {
        fun fromPreference(value: String?): OverlayLongPressAction =
            entries.firstOrNull { it.name == value } ?: STOP
    }
}

/** 该音源是否需要麦克风权限/前台服务 microphone 类型。 */
fun AudioSource.needsMic(): Boolean = this == AudioSource.MIC || this == AudioSource.INTERNAL_AND_MIC

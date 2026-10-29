# 高光回录(Highlight Recorder)

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)]()
[![Website](https://img.shields.io/badge/Website-宣传页-orange.svg)](https://owen88ob.github.io/highlight-recorder/)

Android「游戏高光回录」App,类似一加游戏相机:后台持续循环录制屏幕(环形缓冲),
默认按下悬浮球把**按下前 N 秒**的画面保存为 MP4,也可在设置中启用「接续保存」。

> 宣传页:https://owen88ob.github.io/highlight-recorder/

本 Fork 当前版本为 **v1.2.3.1**：[下载 app-debug.apk](https://github.com/Kylin-John/highlight_recorder/releases/download/v1.2.3.1/app-debug.apk)。

## 功能

- 悬浮球全局可用:单击 = 按所选模式保存回放;长按 = 停止录制(未录制时拉起 App 开始)。
- 保存模式:默认每次保存最近 N 秒;可选「接续保存」,从上次成功保存处接续,
  最多回退 N 秒(仍为 15–300s),按关键帧对齐,允许少量重叠。
- 环形回录:MediaProjection + VirtualDisplay → MediaCodec 硬件编码(H.264/H.265),
  按关键帧切 1 秒分片,只保留最近 N+2 秒,保存时从 IDR 边界拼接,无花屏。
- 音频:内录(AudioPlaybackCapture,Android 10+)/ 麦克风 / **内录+麦克风实时混音**(PCM 相加截幅,单音轨输出)/ 静音;麦克风自动兼容单声道设备。
- 设置:回退时长(15–300s)、分辨率(跟随屏幕/720p/1080p/2K/4K)、帧率(30/60/90/120fps)、
  码率(预设三档或自定义 1–60Mbps)、编码器、音频来源、悬浮窗外观。
- 亮暗主题跟随系统;设置页内含作者赞助入口(爱发电)。
- 视频库:缩略图、时长、大小,支持播放 / 分享 / 删除;长按进入多选,可全选、批量分享、批量删除。
- 前台服务 + 常驻通知(通知栏可直接"保存回放 / 停止");
  低电量 / 低内存自动降码率,内存危急时暂停录制并在通知提示。
- 性能优化:默认 720p 录制、编码器限速(`KEY_MAX_FPS_TO_ENCODER`)、
  best-effort 优先级为游戏让路、强制硬件编码器,高刷屏游戏场景掉帧极小。

## 构建

环境:JDK 17+(开发用 21),Gradle Wrapper 自带。

```bash
bash ./gradlew assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
bash ./gradlew testDebugUnitTest    # 环形缓冲 / PTS 重定基单元测试
```

本功能分支的 debug 包为「高光回录·接续测试」,包名增加 `.continuationtest`,
版本为 `1.2.3.1`(versionCode 11),不追加测试版本后缀。
本次发布附件沿用该 debug 构建及签名,用于更新此前的接续测试包,也可与原版并存。
首次安装需单独授权与设置,使用时只开启一个录制服务。

SDK 路径在 `local.properties`(本仓库使用项目内 `.android-sdk/`,已在 .gitignore 排除)。
在自己机器上构建:改成你的 SDK 路径或用 Android Studio 打开自动生成。

## 使用流程

1. 安装 APK,首次打开进入引导页,逐项授权:
   悬浮窗 → 了解屏幕录制机制 → 通知 → 存储(仅 Android 9-)→ 电池优化白名单(建议)。
2. 主页点「开始循环录制」,在系统弹窗中确认屏幕录制(Android 14+ 每次都要确认,正常)。
3. 切到游戏,正常游玩——App 在后台持续缓冲最近 N 秒。
4. 打出高光后,**单击悬浮球**(或通知栏「保存回放」),几秒后 Toast 提示保存成功。
5. 回 App「视频库」查看 / 播放 / 分享;文件在 `Movies/高光回录/`。
6. 长按悬浮球或主页「停止录制」结束循环录制。

### 接续保存

在设置页「保存模式」中选择「接续保存」;原有用户升级后仍默认每次保存最近 N 秒。
例如回退 180 秒,第 5 分钟首次保存约 2～5 分钟,第 6 分钟再次保存约 5～6 分钟;
若下次到第 10 分钟才保存,则只保存约 7～10 分钟。

点击时固定本次导出的媒体尾部,导出期间继续采集和编码,不清空实时缓冲。
例如 5:00 点击、5:08 才成功,下次接续仍以 5:00 的媒体位置为边界,包括导出期间仍在窗口内的画面。
保存失败不推进边界;正在保存时暂不接受新请求,悬浮球/通知栏重复点击会提示稍后再试。
新录制或屏幕旋转、看门狗恢复导致编码器重建后,按新缓冲重新开始,第一次保存仍取当前窗口。
视频从实际关键帧开始,片段之间可能有少量重叠,不保证精确到点击的那一帧。

## 权限说明

| 权限 | 用途 |
|------|------|
| SYSTEM_ALERT_WINDOW | 游戏全屏上显示悬浮球 |
| MediaProjection(每次会话同意) | 采集屏幕画面,系统强制每次确认 |
| POST_NOTIFICATIONS(13+) | 前台服务常驻通知 |
| RECORD_AUDIO(可选) | 音频来源选「麦克风」或「内录+麦克风」时 |
| WRITE_EXTERNAL_STORAGE(仅 ≤28) | 老系统保存视频 |
| 电池优化白名单(可选) | 防后台被杀 |

## 技术要点

- **环形缓冲**(`buffer/RingSegmentBuffer`):编码输出按实际到达的关键帧切片
  (不假设 `KEY_I_FRAME_INTERVAL=1` 一定生效),容量 N+2 秒,旧分片自动逐出;
  快照起点必为 IDR。
- **保存**(`buffer/ClipWriter`):已封闭分片直接共享,开放分片仅拷贝编码包引用以固定导出尾部,
  不复制视频载荷,录制不中断;音频按相同视频起尾裁剪,PTS 重定基为 0;
  MediaMuxer 封装,经 MediaStore 落盘 `Movies/高光回录/`。
- **接续边界**(`buffer/ClipSaveSession`):文件成功保存后提交快照尾部,不使用导出完成的墙钟时间;
  编码器重建时替换缓冲与保存会话,旧任务不能推进新会话的边界。
- **零原始帧**:VirtualDisplay 直送编码器 inputSurface,全程无 Bitmap/帧拷贝。
- 单元测试:`RingSegmentBufferTest`(逐出/稳定快照/IDR 对齐)、`AudioRingBufferTest`、
  `PtsRebaserTest`(重定基单调性、音视频裁剪)、`ClipSaveSessionTest`(接续/失败/会话隔离)、
  `RecordingSettingsTest`(保存模式兼容默认值)。

## 已知限制

- DRM 保护内容(部分视频 App)无法录制,画面为黑屏,这是系统限制。
- Android 14+ 每次开始录制都要点一次系统确认,无法跳过。
- 内录取决于目标 App 的音频捕获策略(`allowAudioPlaybackCapture`),被拒绝时自动静默为无音频;
  混音模式下内录不可用则自动退化为纯麦克风。
- 部分机型编码器不严格遵守 1 秒 I 帧间隔,此时分片略长于 1 秒(功能不受影响,
  因为分片边界按实际关键帧切)。
- 保存时长受关键帧对齐影响,可能比 N 略长;缓冲刚重建或触发内存安全阀时也可能更短。

## 目录结构

```
app/src/main/java/com/highlightrecorder/
├── capture/    采集编码:CapturePipeline / VideoEncoder / AudioCaptureEncoder
├── buffer/     环形缓冲:RingSegmentBuffer / AudioRingBuffer / PtsRebaser / ClipWriter
├── service/    前台服务:RecordingService(生命周期/通知/降级)
├── overlay/    悬浮窗:OverlayManager / FloatingButtonView
├── ui/         Compose 页面:引导/主页/设置/视频库 + MainViewModel
└── data/       设置(DataStore)/ 视频库(MediaStore)
```

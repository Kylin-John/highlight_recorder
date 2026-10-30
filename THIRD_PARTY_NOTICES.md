# 第三方许可与参考说明

## 原项目与本 Fork

高光回录原项目由 **[owen88ob](https://github.com/owen88ob/highlight-recorder)** 开发。本 Fork 的接续保存、预计时长、长按选项、游戏呈现帧率监测及相关交互改进由 **[Kylin / Kylin-John](https://github.com/Kylin-John)** 开发与维护，具体归属见 [README](README.md#kylin-在本-fork-中的贡献)。应用继续采用 [GPL-3.0](LICENSE)，保留原作者署名和许可。

## Shizuku API / Provider

游戏呈现监测使用以下开发库：

| 组件 | 版本 | 许可证与来源 |
| --- | --- | --- |
| `dev.rikka.shizuku:api` | 13.1.5 | MIT，[Shizuku-API](https://github.com/RikkaApps/Shizuku-API) |
| `dev.rikka.shizuku:provider` | 13.1.5 | 同上 |

版权声明：`Copyright (c) 2021 RikkaW`。完整 MIT 版权和许可文本保留于：

- 源码：[app/src/main/assets/licenses/Shizuku-API.txt](app/src/main/assets/licenses/Shizuku-API.txt)
- APK：`assets/licenses/Shizuku-API.txt`

Shizuku 应用需用户另行安装、启动并授权，不作为另一个 APK 打包进本应用。运行所需 Shizuku 版本及配置见 [使用教程](README.md#搭配-shizuku-使用帧率监测)；上述 13.1.5 是开发库版本。

## 监测方案参考

SurfaceFlinger 监测方案研究参考了：

- [AOSP SurfaceFlinger](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/SurfaceFlinger.cpp)
- [AOSP FrameTracker](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/FrameTracker.cpp)
- MIT 许可的 [FPS Meter Android](https://github.com/rdevz-ph/FPS-Meter-Android)

本 Fork 的监测实现未复制上述 FPS Meter Android 或 AOSP 的源码。参考方案与测量来源的说明不改变本应用的 GPL-3.0 许可；数据定义见 [FPS 数据与统计说明](docs/FPS_MONITORING.md)。

# MusicDL — Android YouTube 音乐下载器

集成 **yt-dlp** 与 **FFmpeg** 的 Android 应用：

- 关键词搜索（`ytsearch`）或直接粘贴视频链接解析
- 音频格式：M4A 原始流 / M4A(AAC) / MP3(192/320) / Opus（转码项依赖 FFmpeg）
- 下载队列：进度、速度、取消、重试、删除；前台服务通知
- 下载完成自动复制到系统音乐库（MediaStore，Android 10+），可直接在任意播放器播放
- 单线程队列下载，避免对站点造成压力

## 技术架构

| 组件 | 说明 |
|---|---|
| [Chaquopy](https://chaquo.com/chaquopy/) | 在 APK 内嵌入 Python 3.12，`pip install yt-dlp` 直接调用其 API |
| FFmpeg | CI 中用 Android NDK 交叉编译静态二进制（含 libmp3lame / libopus），以 `libffmpeg.so`、`libffprobe.so` 装入 `jniLibs`，配合 `useLegacyPackaging` 解压到 `nativeLibraryDir` 后通过软链接作为 `ffmpeg_location` 供 yt-dlp 使用 |
| UI | Kotlin + Jetpack Compose (Material 3) |

## 编译（GitHub Actions，无需本地环境）

推送代码后 Actions 自动构建，或手动触发 **Build Android APK** workflow。
在 Actions 任务页的 **Artifacts** 中下载 `MusicDL-debug-apk`，安装 `app-debug.apk` 即可。

构建流程：`scripts/build-ffmpeg.sh <abi>` 交叉编译 arm64-v8a / armeabi-v7a / x86_64 三种架构的 FFmpeg（失败不阻塞打包，仅禁用转码格式），随后 Gradle 输出 debug APK。

## 本地编译（可选）

需要 JDK 17、Android SDK 35、Chaquopy 16、Python 3.12：

```bash
gradle assembleDebug
```

## 免责声明

请仅下载你有权保存的内容，遵守 YouTube 服务条款与当地法律。本项目仅供个人学习与技术研究。

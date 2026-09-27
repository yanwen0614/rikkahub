# speech 模块说明

## 1. 模块职责
- TTS：将文本合成为语音并播放，支持多家云端 Provider + 系统引擎，含分片、预取、排队播放。
- ASR：实时语音识别，麦克风采集经 WebSocket 流式送云端，回调返回转写文本与状态。

## 2. 关键类
- `tts/provider/TTSProvider.kt`：TTS 统一接口，以 `Flow<AudioChunk>` 流式返回音频。
- `tts/provider/TTSManager.kt`：按 `TTSProviderSetting` 分发到 12 种 Provider 实现。
- `tts/controller/TtsController.kt`：分片调度、预取合成（prefetch=2）、重试与播放状态管理。
- `tts/controller/TextChunker.kt`：按标点分段、单片最长 160 字符的文本切分器。
- `tts/controller/TtsSynthesizer.kt`：把 Provider 的 `AudioChunk` 流合并为单个 `TTSResponse`。
- `tts/controller/AudioPlayer.kt`：基于 Media3 ExoPlayer 的播放器，处理 PCM→WAV 与倍速/进度。
- `tts/provider/providers/SystemTTSProvider.kt`：封装 `android.speech.tts.TextToSpeech`，合成到临时文件再读回。
- `asr/ASRController.kt`：ASR 统一接口，`start/stop/pauseCapture/dispose` 四件套。
- `asr/providers/VolcengineASRController.kt`：火山 ASR 实现，`AudioRecord` 采集 + OkHttp WebSocket 推流。
- `asr/ASRState.kt`：识别状态（Idle/Connecting/Listening/Stopping/Error）与转写、音量波形。

## 3. 权限 / 系统服务注意
- 系统 TTS：依赖设备 TTS 引擎与语种数据，每次合成新建 `TextToSpeech` 实例，用完必须 `shutdown()`。
- 录音权限：ASR 基于 `AudioRecord`，调用前须检查 `RECORD_AUDIO`，无权限直接失败不拉起录音。
- 生命周期：`TtsController.dispose()` / `AudioPlayer.release()` / `ASRController.dispose()` 必须在销毁时调用。
- 网络：云端 TTS/ASR 走 OkHttp（SSE/WebSocket），合成在 `Dispatchers.IO`，取消时关闭连接与录音。

## 4. 构建 / 测试注意
- 依赖：`common`、`media3(exoplayer/ui/common)`、`okhttp`、`serialization-json`，见 `build.gradle.kts`。
- 改 Provider 先看 `TTSProviderSetting` / `ASRProviderSetting` 密封类分支，`TTSManager` 需同步新增 `when` 分支。
- 单测在 `src/test`（如 `ASRVoiceTurnTest`、`TTSProviderSetting*Test`），运行：`./gradlew :speech:testDebugUnitTest`。
- 对外只暴露 Controller/Manager/Setting，保持 `me.rerere.tts.*` / `me.rerere.asr.*` 包名，不要新建顶层包。

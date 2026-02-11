## media_processing_kit（Part D 黑盒，Flutter）

这个包对应 TRD 里的 **做法 B**：\n
- **D（本包）**只负责媒体管线：任务管理、进度/取消、文件 I/O、（未来）解码/编码/音频保留。\n
- **B/C**通过“效果注册（effect registry）”把真正的处理逻辑注入进来。\n
- **A/E/其他团队**把它当黑盒调用即可。

### 现状（本仓库当前实现）
- 已实现 Flutter 侧 Dart API + Android/iOS 插件骨架
- 默认内置一个 `passthrough` effect：仅做文件 copy，用于端到端联调（不做真正人脸形变）

### 在你的 Flutter App 里怎么用（path 依赖）

在 app 的 `pubspec.yaml` 里加：

```yaml
dependencies:
  media_processing_kit:
    path: ../flutter_packages/media_processing_kit
```

然后在 Dart 里：

```dart
final kit = MediaProcessingKit();
await kit.initialize();

kit.events.listen((e) {
  // e.taskId / e.type / e.progress / e.outputPath / e.errorCode ...
});

final taskId = await kit.processVideo(
  inputPath: "/path/to/in.mp4",
  outputPath: "/path/to/out.mp4",
  effectId: "passthrough",
);
```

### B/C 怎么“注入效果”（注册 effectId）

#### Android
- B/C 在自己的插件（或 app 的原生层）里依赖本包的 Android 库
- 在 `onAttachedToEngine` 时调用：

```kotlin
EffectRegistry.register("bitter_face", BitterFaceEffectProcessor())
```

#### iOS
- B/C 在自己的插件（或 app 的原生层）里调用：

```swift
EffectRegistry.shared.register(effectId: "bitter_face", processor: BitterFaceEffectProcessor())
```

> D 不负责 BitterFaceEffectProcessor 的实现；D 只负责调用 registry 里对应 effectId 的 processor。


# Bitter Camera 团队协作指南

苦瓜脸特效相机应用 - 团队开发与测试指南

---

## 目录

1. [项目概述](#项目概述)
2. [环境要求](#环境要求)
3. [Windows 环境配置](#windows-环境配置)
4. [macOS 环境配置](#macos-环境配置)
5. [项目安装步骤](#项目安装步骤)
6. [Android 测试方法](#android-测试方法)
7. [iOS 测试方法](#ios-测试方法)
8. [常见问题排查](#常见问题排查)
9. [项目文件说明](#项目文件说明)

---

## 项目概述

Bitter Camera（苦瓜脸相机）是一款人脸形变滤镜应用，支持：

- **实时拍摄**：打开相机实时预览苦瓜脸特效，支持拍照和录像
- **照片/视频导入**：从相册导入媒体文件并应用特效
- **3种苦瓜脸样式**：不同的面部形变效果可选
- **强度调节**：可调整特效强度
- **保存到相册**：处理后的媒体可保存到系统相册

### 技术栈

- **Flutter 3.x** - 跨平台 UI 框架
- **ML Kit Face Detection** - Google 人脸检测
- **OpenCV** - 图像处理和形变算法
- **CameraX** - Android 相机框架
- **FFmpeg** - 视频处理

---

## 环境要求

### 必需软件

| 软件 | 最低版本 | 用途 |
|------|---------|------|
| Flutter SDK | 3.10.0+ | 开发框架 |
| Dart SDK | 3.0.0+ | 编程语言（随 Flutter 安装） |
| Git | 2.x | 版本控制 |
| Android Studio | 2023.x+ | Android 开发 IDE + SDK |
| Xcode | 15.0+ | iOS 开发（仅 macOS） |

### 硬件要求

- **Android 测试**：Android 7.0+ 设备或模拟器
- **iOS 测试**：iOS 12.0+ 设备或模拟器（需 macOS 电脑）

---

## Windows 环境配置

### 1. 安装 Flutter SDK

1. 访问 [Flutter 官方安装页面](https://docs.flutter.dev/get-started/install/windows)
2. 下载 **Stable channel** 的 Flutter SDK（Windows zip）
3. 解压到无空格、无中文的路径，例如：
   - `C:\src\flutter`
   - `D:\dev\flutter`

> 注意：不要解压到 `C:\Program Files\`（可能遇到权限问题）

### 2. 配置环境变量

将 Flutter bin 目录加入 PATH：

1. 开始菜单搜索「环境变量」
2. 打开「编辑系统环境变量」→「环境变量...」
3. 在「用户变量」找到 `Path` →「编辑」
4. 新增一条：`C:\src\flutter\bin`（按实际路径修改）
5. 确定保存
6. **重启** PowerShell / VSCode / Cursor

验证安装：

```powershell
flutter --version
```

### 3. 安装 Git

- 下载：[Git for Windows](https://git-scm.com/download/win)
- 或使用 winget：`winget install --id Git.Git -e`

验证：

```powershell
git --version
```

### 4. 安装 Android Studio

1. 下载安装 [Android Studio](https://developer.android.com/studio)
2. 打开 Android Studio → SDK Manager：
   - 安装 Android SDK Platform（建议 API 33+）
   - 安装 Android SDK Build-Tools
   - 安装 Android SDK Command-line Tools
3. 接受许可证：

```powershell
flutter doctor --android-licenses
```

### 5. 检查环境

```powershell
flutter doctor -v
```

确保以下项目打勾：
- [✓] Flutter
- [✓] Android toolchain
- [✓] Android Studio

---

## macOS 环境配置

### 1. 安装 Flutter SDK

```bash
# 使用 Homebrew 安装（推荐）
brew install --cask flutter

# 或手动下载解压
# https://docs.flutter.dev/get-started/install/macos
```

### 2. 安装 Xcode

1. 从 App Store 安装 Xcode（15.0+）
2. 安装命令行工具：

```bash
sudo xcode-select --install
```

3. 接受许可证：

```bash
sudo xcodebuild -license accept
```

### 3. 安装 CocoaPods

```bash
sudo gem install cocoapods
```

### 4. 安装 Android Studio

同 Windows 步骤，从官网下载安装。

### 5. 检查环境

```bash
flutter doctor -v
```

确保以下项目打勾：
- [✓] Flutter
- [✓] Android toolchain
- [✓] Xcode
- [✓] Android Studio

---

## 项目安装步骤

### 1. 获取项目文件

**方式 A：Git 克隆（如果有仓库）**

```bash
git clone <仓库地址>
cd bitter_camera
```

**方式 B：解压压缩包**

将收到的 `bitter_camera.zip` 解压到工作目录。

### 2. 安装依赖

```bash
cd bitter_camera
flutter pub get
```

### 3. 检查项目

```bash
flutter analyze
```

如果没有错误，环境配置成功。

---

## Android 测试方法

### 方式 1：模拟器调试

1. 打开 Android Studio → Virtual Device Manager
2. 创建或启动一个 AVD（推荐 Pixel 6 + API 33）
3. 在项目目录运行：

```bash
flutter run
```

> 注意：模拟器的相机功能有限，建议用真机测试完整功能。

### 方式 2：真机调试（USB）

1. 在手机上启用「开发者选项」和「USB 调试」
2. 用 USB 线连接电脑
3. 检查设备：

```bash
flutter devices
```

4. 运行：

```bash
flutter run
```

### 方式 3：真机调试（无线）

1. 先用 USB 连接一次
2. 启用无线调试：

```bashadb
adb tcpip 5555
adb connect <手机IP>:5555
```

3. 拔掉 USB，运行 `flutter run`

### 方式 4：生成 APK 分发

**Debug APK（快速测试）：**

```bash
flutter build apk --debug
```

输出位置：`build/app/outputs/flutter-apk/app-debug.apk`

**Release APK（正式分发）：**

```bash
flutter build apk --release
```

输出位置：`build/app/outputs/flutter-apk/app-release.apk`

将 APK 发送给测试人员，他们可以直接安装测试。

> 注意：安装时需要允许「未知来源」应用。

---

## iOS 测试方法

> iOS 开发需要 macOS 电脑和 Xcode。

### 方式 1：模拟器调试

1. 打开 Xcode → 启动模拟器
2. 在项目目录运行：

```bash
cd ios
pod install
cd ..
flutter run
```

### 方式 2：真机调试

**准备工作：**

1. 需要 Apple ID（免费）或 Apple Developer 账号（付费）
2. 在 Xcode 中配置签名：
   - 打开 `ios/Runner.xcworkspace`
   - 选择 Runner target → Signing & Capabilities
   - 选择你的 Team

**运行：**

```bash
flutter run
```

首次运行需要在 iPhone 上信任开发者证书：
设置 → 通用 → VPN与设备管理 → 信任

### 方式 3：TestFlight 分发

1. 加入 Apple Developer Program（99 美元/年）
2. 在 Xcode 中 Archive 并上传到 App Store Connect
3. 在 TestFlight 中添加测试人员

---

## 常见问题排查

### Q1: `flutter pub get` 失败

**症状：**
```
Could not find package xxx
```

**解决：**
1. 检查网络连接
2. 尝试设置镜像：

```bash
# Windows PowerShell
$env:PUB_HOSTED_URL="https://pub.flutter-io.cn"
$env:FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"
flutter pub get
```

### Q2: Android 编译失败 - Gradle 错误

**症状：**
```
Could not resolve all files for configuration
```

**解决：**
1. 确保 Android Studio 已安装
2. 检查代理设置
3. 尝试清理缓存：

```bash
cd android
./gradlew clean
cd ..
flutter clean
flutter pub get
```

### Q3: iOS 编译失败 - Pod 错误

**症状：**
```
CocoaPods could not find compatible versions
```

**解决：**

```bash
cd ios
pod deintegrate
pod install --repo-update
cd ..
flutter clean
flutter run
```

### Q4: 相机权限问题

**症状：** 打开相机时崩溃或黑屏

**解决：**
- Android：确保已授予相机和存储权限
- iOS：确保 Info.plist 包含相机权限描述

### Q5: 特效不生效

**症状：** 拍照/录像没有苦瓜脸效果

**可能原因：**
1. 样式设置为 0（无特效）
2. 人脸未被检测到（光线/角度问题）
3. iOS 端特效实现可能不完整

---

## 项目文件说明

```
bitter_camera/
│
├── lib/                          # Flutter Dart 源码
│   ├── main.dart                 # 应用入口和首页
│   ├── camera_screen.dart        # 拍摄页面
│   ├── import_screen.dart        # 导入页面
│   ├── result_screen.dart        # 结果页面
│   ├── native_camera_controller.dart  # 原生相机控制
│   ├── media_type.dart           # 媒体类型定义
│   └── test_effect_screen.dart   # 特效测试页（调试用）
│
├── android/                      # Android 原生代码
│   └── app/src/main/kotlin/.../faceeffect/
│       ├── NativeCameraPlugin.kt      # Flutter 插件入口
│       ├── NativeCameraHandler.kt     # CameraX 相机管理
│       ├── FaceProcessor.kt           # ML Kit 人脸检测
│       ├── FaceLandmarkMapper.kt      # 人脸特征点映射
│       └── BitterFaceWarp.kt          # 苦瓜脸形变算法
│
├── ios/                          # iOS 原生代码
│   └── Runner/
│
├── packages/                     # 本地 Flutter 插件
│   └── media_processing_kit/     # 媒体处理黑盒
│       ├── lib/                  # Dart 接口
│       ├── android/              # Android 实现
│       └── ios/                  # iOS 实现
│
├── third_party/                  # 第三方库（本地修补版）
│   ├── ffmpeg_kit_flutter/       # FFmpeg 视频处理
│   └── gallery_saver/            # 保存到相册
│
├── assets/                       # 资源文件
│   └── test_images/              # 测试用图片
│
├── pubspec.yaml                  # Flutter 依赖配置
├── TEAM_GUIDE.md                 # 本文档
└── FLUTTER_SDK_SETUP_WINDOWS.md  # Windows SDK 配置（旧）
```

### 关键依赖说明

| 依赖 | 位置 | 用途 |
|------|------|------|
| `media_processing_kit` | `packages/` | 图片/视频特效处理 |
| `ffmpeg_kit_flutter` | `third_party/` | 视频编解码（已修补） |
| `gallery_saver` | `third_party/` | 保存到系统相册 |
| `camera` | pub.dev | 相机插件（iOS fallback） |
| `image_picker` | pub.dev | 相册选择器 |
| `video_player` | pub.dev | 视频播放器 |

---

## 分享方式

### Git 仓库（推荐）

如果项目已推送到 GitHub/GitLab，直接克隆即可：

```bash
git clone <仓库地址>
```

### 压缩包分享

打包前请排除以下目录以减小体积：

```
build/
.dart_tool/
android/.gradle/
ios/Pods/
```

Windows 打包命令：

```powershell
# 先清理
flutter clean

# 打包（排除不必要文件）
Compress-Archive -Path * -DestinationPath bitter_camera.zip -Force
```

### APK 直接分发

如果测试人员不需要修改代码，直接发送编译好的 APK：

```bash
flutter build apk --release
```

---

## 联系方式

如有问题，请联系项目负责人。

---

*文档更新时间：2026年2月*

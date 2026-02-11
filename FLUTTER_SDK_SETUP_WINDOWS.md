## Windows 配置 Flutter SDK（适用于本项目 `bitter_camera`）

> 目标：让命令行可用 `flutter --version`、`flutter doctor`，并能跑起 Android 模拟器/真机调试。

---

### 1) 安装 Flutter SDK（官方 zip）

1. 打开 Flutter 官方 Windows 安装指南：`https://docs.flutter.dev/get-started/install/windows`
2. 下载 **Stable channel** 的 Flutter SDK（Windows zip）。
3. 解压到一个“无空格、无中文、权限稳定”的路径，例如：
   - `C:\src\flutter`
   - 或 `D:\dev\flutter`

> 不要解压到 `C:\Program Files\...`（容易遇到权限问题）。

---

### 2) 配置 PATH（让 flutter 命令可用）

把以下目录加入 **用户环境变量 PATH**：
- `C:\src\flutter\bin`（按你的实际解压路径替换）

操作路径：
1. 开始菜单搜索：`环境变量`
2. 打开：`编辑系统环境变量` → `环境变量...`
3. 在“用户变量”里找到 `Path` → `编辑`
4. 新增一条：`C:\src\flutter\bin`
5. 一路确定保存
6. **重启** PowerShell / VSCode / Cursor（让 PATH 生效）

验证：

```powershell
flutter --version
```

---

### 3) 安装 Git（Flutter 常用依赖）

Flutter 常用 `git` 拉取依赖与升级。

安装方式（任选其一）：
- 方式 A：下载 Git for Windows：`https://git-scm.com/download/win`
- 方式 B：如果你有 winget：`winget install --id Git.Git -e`

验证：

```powershell
git --version
```

---

### 4) 跑 `flutter doctor`（确认缺什么）

```powershell
flutter doctor -v
```

常见需要补齐的项：

- Android toolchain（Android Studio / SDK / licenses）
- Android Studio（可选但推荐）
- Connected device（真机或模拟器）

---

### 5) 配好 Android（最常见的开发目标）

1. 安装 Android Studio：`https://developer.android.com/studio`
2. Android Studio → SDK Manager：
   - 安装 Android SDK Platform（建议最新稳定）
   - 安装 Android SDK Build-Tools
   - 安装 Android SDK Command-line Tools
3. 接受 licenses：

```powershell
flutter doctor --android-licenses
```

---

### 6) 在本项目里跑起来（拿到最小闭环）

进入项目目录：

```powershell
cd D:\code\bitter_camera
flutter pub get
flutter run
```

> 如果你看到 `media_processing_kit` 相关报错，优先确认：\n
> - `D:\code\Bitter Camera\flutter_packages\media_processing_kit\` 目录存在\n
> - `pubspec.yaml` 的 path 依赖路径写对了


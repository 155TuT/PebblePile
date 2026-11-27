# Android 开发指南

在仓库根目录运行 Expo，即可在模拟器上使用叠石的首页、记录库和设置。开发包 `com.pebblepile.mobile.dev` 与正式包 `com.pebblepile.android` 共用 Compose 页面、Room 和 WorkManager，各自保存记录和设置。开发流程参考了[福大助手](https://github.com/west2-online/fzuhelper-app)和 [Expo 本地构建指南](https://docs.expo.dev/guides/local-app-development/)。

## 安装开发工具

1. 安装 Node.js 与随附的 npm。项目使用 Expo SDK 55，要求 Node.js 20.19.4 或更高版本，CI 使用 22.x。[Node.js 下载](https://nodejs.org/en/download) · [项目版本要求](../package.json)
2. 安装 OpenJDK 17 的 **JDK**，记下包含 `bin/java` 和 `bin/javac` 的安装目录。[Temurin 17 下载](https://adoptium.net/temurin/releases/?version=17)
3. 安装 Android Studio，完成首次启动向导。Python 3.9 及以上可运行仓库提供的环境检查和 Gradle 脚本。[Android Studio 下载](https://developer.android.com/studio) · [Python 下载](https://www.python.org/downloads/)

## 安装 Android SDK

在 Android Studio 欢迎页选择 **More Actions → SDK Manager**，已打开工程时选择 **Tools → SDK Manager**。记下顶部的 **Android SDK Location**。在以下两个选项卡勾选组件，点 **Apply**，接受许可并等待下载完成。[SDK Manager 使用说明](https://developer.android.com/studio/intro/update#sdk-manager)

| 选项卡 | 勾选内容 |
| --- | --- |
| **SDK Platforms** | Android SDK Platform 36（Expo）和 Android SDK Platform 35（独立 APK） |
| **SDK Tools** | Android SDK Platform-Tools、Android Emulator、Android SDK Command-line Tools (latest) |
| **SDK Tools → Show Package Details** | Android SDK Build-Tools 36.0.0 和 34.0.0、NDK (Side by side) 27.1.12297006、CMake 3.22.1 |

## 配置环境变量

**Windows。** 在开始菜单搜索“编辑帐户的环境变量”。新建两个用户变量，`JAVA_HOME` 填 OpenJDK 17 安装目录，`ANDROID_HOME` 填 SDK Manager 显示的 SDK 目录。编辑用户变量 `Path`，分别添加 `%JAVA_HOME%\bin`、`%ANDROID_HOME%\platform-tools` 和 `%ANDROID_HOME%\emulator`。确定后，重新打开终端。执行 `java -version` 应显示 17，`where.exe java` 可查看命令实际使用的 Java。

也可以先在当前 PowerShell 中试运行以下配置。第一行填本机 JDK 目录，第二行给出 Windows 上常见的 SDK 位置，可按 SDK Manager 中的实际路径调整。

```powershell
$env:JAVA_HOME = 'C:\实际安装位置\jdk-17'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
```

**macOS 与 Linux。** 将下面三行放入当前 shell 的启动文件（例如 `~/.zshrc` 或 `~/.bashrc`），然后新开终端。在 macOS，JDK 17 的路径可用 `/usr/libexec/java_home -v 17` 查看，SDK 常在 `$HOME/Library/Android/sdk`。Linux 的 SDK 常在 `$HOME/Android/Sdk`。两端都以 SDK Manager 中的实际位置为准。[Android 工具环境变量](https://developer.android.com/tools/variables)

```sh
export JAVA_HOME="/实际安装位置/jdk-17"
export ANDROID_HOME="/SDK Manager 中的 Android SDK Location"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## 创建模拟器

1. 在 Android Studio 欢迎页打开 **More Actions → Virtual Device Manager**。已打开工程时，从 **View → Tool Windows → Device Manager** 进入。点 **＋ → Create Virtual Device**，从 **Phone** 选一款手机。
2. 选择 API 36 的系统映像并完成下载。Intel／AMD 电脑选 x86_64，Apple 芯片选 arm64-v8a。点 **Finish** 保存虚拟设备（AVD）。在终端运行 `emulator -list-avds` 应看到设备名。[Android 虚拟设备指南](https://developer.android.com/studio/run/managing-avds)
3. 回到仓库根目录，运行环境检查。脚本会检查 Node、npm、JDK、SDK 组件和 AVD。按输出补齐缺少的项，再执行一次。若本机的 Python 命令名为 `python3`，下文的 `python` 均可换成 `python3`。

```sh
python scripts/check-android-env.py
```

## 安装与启动

首次检出项目或依赖锁文件更新后，在仓库根目录安装依赖。

```sh
npm ci
```

以后在同一目录用一条命令启动模拟器和开发包。

```sh
npx expo run:android
```

Expo 首次生成根目录的 `android/`，随后编译并安装开发包，启动 Metro 并打开应用。首次出现开发菜单时，点 **Continue**，再点右上角的关闭按钮即可进入首页。有多个设备时，用 `npx expo run:android --device` 选择。

开发包已经装好，且只修改 JS/TS 时，下次运行 `npx expo start --android --dev-client` 即可重新连接。按 `Ctrl+C` 结束 Metro，在 Device Manager 中关闭模拟器。

## 热刷新与调试

| 改动 | 操作 |
| --- | --- |
| TypeScript 与 React 组件 | 在首页输入草稿，修改 [App.tsx](../src/App.tsx) 的 `homeTitle` 并保存。标题随 Fast Refresh 更新，草稿仍可编辑。验完将标题改回“叠石”。 |
| JavaScript 断点与日志 | 在 Expo 终端按 `j` 打开 React Native DevTools，在 Sources 设置断点，在 Console 查看日志。[React Native DevTools](https://reactnative.dev/docs/react-native-devtools) |
| Kotlin、Room、Worker 或原生视图 | 修改后重新运行 `npx expo run:android` 编译并安装开发包。用 Android Studio 打开生成的 `android/` 工程并同步 Gradle，通过 **Attach Debugger to Android Process** 连接 `com.pebblepile.mobile.dev`。 |
| Compose 页面布局 | 用 Android Studio 打开独立工程 [config/gradle/](../config/gradle/)，选择 JDK 17 完成同步并运行 `app`。启用 **Live Edit** 后，可以在设备上预览受支持的 Composable 函数体改动。[Live Edit 使用条件](https://developer.android.com/develop/ui/compose/tooling/iterative-development) |

页面、应用入口、Room 和系统任务分别在 [src/screens/](../src/screens/)、[src/runtime/](../src/runtime/)、[src/storage/](../src/storage/) 和 [src/work/](../src/work/)。业务与算法在 [lib/](../lib/)，Expo 原生视图在 [modules/pebble-client/](../modules/pebble-client/)。要修改生成的宿主行为，请编辑[应用配置](../app.json)和[配置插件](../config/withPebbleAndroid.js)，再运行以下两条命令。第一条会替换整份本地 `android/`，第二条安装重建后的开发包。

```sh
npx expo prebuild --clean --platform android
npx expo run:android
```

## 常见问题

- **安装时报 `INSTALL_FAILED_INSUFFICIENT_STORAGE`。** 运行 `adb shell df -h /data` 查看模拟器容量。在 Device Manager 新建一台有足够内部存储的 AVD，通过 `npx expo run:android --device` 选择它，原设备的数据会保留。
- **模拟器提示找不到开发包。** 在仓库根目录运行 `npx expo run:android` 安装带叠石原生模块的开发包。
- **模拟器连接不上 Metro。** 运行 `adb devices` 确认目标模拟器状态为 `device`。使用单台模拟器和默认端口时，可运行 `adb reverse tcp:8081 tcp:8081`，再运行 `npx expo start --android --dev-client --localhost`。
- **Gradle 提示缺少 SDK 或工具链。** 在 SDK Manager 对照上方版本检查已安装组件，核对当前终端的 `JAVA_HOME` 和 `ANDROID_HOME`。Windows 可运行 `where.exe java` 确认 PATH 中优先使用的 Java。

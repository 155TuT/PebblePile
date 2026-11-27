# 叠石 Android 开发指南

从这个目录运行 Expo，可以在模拟器上直接使用叠石的首页、记录库和设置。开发包 `com.pebblepile.mobile.dev` 调用仓库里的 Compose 页面、Room 和 WorkManager。它与正式包 `com.pebblepile.android` 各自保存记录和设置。启动方式参考了[福大助手](https://github.com/west2-online/fzuhelper-app)和[Expo 本地构建指南](https://docs.expo.dev/guides/local-app-development/)。

## 准备环境

1. 安装 Node.js（最低 20.19.4）和 npm。安装 OpenJDK 17，将 `JAVA_HOME` 指向 JDK 根目录，并将其 `bin` 目录放在 PATH 中旧 Java 路径之前。[工程版本要求](package.json) · [Expo SDK 55 版本要求](https://docs.expo.dev/versions/v55.0.0/)
2. 在 Android Studio 的 **SDK Manager** 中安装 Android SDK Platform 36 和 35。Expo 使用 API 36，项目的原生调度模块使用 API 35。在 **SDK Tools** 中勾选 Platform-Tools、Android Emulator、Android SDK Command-line Tools (latest)。打开 **Show Package Details**，选择 Build-Tools 36.0.0、NDK (Side by side) 27.1.12297006 和 CMake 3.22.1。[下载 Android Studio](https://developer.android.com/studio)
3. 将 `ANDROID_HOME` 指向 SDK Manager 显示的 **Android SDK Location**，将其中的 `platform-tools`、`emulator` 加入 PATH。打开 **Device Manager → Create Virtual Device**，选择手机和适合本机架构的 API 36 系统映像，完成 AVD 创建。[配置 Android 模拟器](https://developer.android.com/studio/run/managing-avds)

在新开的终端检查环境。确认 `java -version` 输出 17，`emulator -list-avds` 至少列出一个设备。

```sh
node --version
npm --version
java -version
adb version
emulator -list-avds
```

## 安装与启动

首次在仓库根目录执行以下命令。依赖锁文件更新后，在 `mobile/` 重新执行 `npm ci`。

```sh
cd mobile
npm ci
```

在 `mobile/` 中启动开发包。

```sh
npx expo run:android
```

Expo 首次生成 `mobile/android/`，随后编译并安装开发包、启动 Metro、打开应用。首次出现开发菜单时，点 **Continue**，再点右上角的关闭按钮即可进入叠石首页。有多个设备时可用 `npx expo run:android --device` 选择。开发包已经装好且只修改 JS/TS 时，下次运行 `npx expo start --android --dev-client` 即可重新连接。终端按 `Ctrl+C` 结束 Metro，可在 Device Manager 中关闭模拟器。

## 热刷新与调试

| 要验证的改动 | 操作和预期结果 |
| --- | --- |
| TypeScript 与 React 组件 | 在叠石首页输入一段草稿，修改 [App.tsx](App.tsx) 的 `homeTitle` 并保存。模拟器里的标题随 Fast Refresh 更新，未提交的草稿仍可编辑。验完将标题改回“叠石”。 |
| JavaScript 断点与日志 | 在运行 Expo 的终端按 `j` 打开 React Native DevTools，在 Sources 中设置断点，在 Console 中查看日志。[React Native DevTools](https://reactnative.dev/docs/react-native-devtools) |
| Kotlin、Room、Worker 或原生视图 | 改完后在本目录再次运行 `npx expo run:android` 编译并安装。要调试原生代码，用 Android Studio 打开生成的 `mobile/android/` 工程，同步 Gradle，再用 **Attach Debugger to Android Process** 连接 `com.pebblepile.mobile.dev`。 |
| Compose 页面布局 | 也可以用 Android Studio 打开仓库根目录的 [android/](../android/)，运行 `app` 调试配置。启用 **Live Edit** 后，受支持的 Composable 函数体修改可直接在运行中的应用预览。[Live Edit 使用条件](https://developer.android.com/develop/ui/compose/tooling/iterative-development) |

原生业务和页面源码维护在 [android/](../android/)，Expo 视图适配维护在 [modules/pebble-client/](modules/pebble-client/)，生成配置维护在 [plugins/](plugins/)。改动配置插件或需要重新应用原生配置时，在本目录运行 `npx expo prebuild --clean --platform android`，再运行 `npx expo run:android`。前一条命令会重建 `mobile/android/`，直接编辑生成目录的内容会丢失。

## 常见问题

- **安装时报 `INSTALL_FAILED_INSUFFICIENT_STORAGE`**：用 `adb shell df -h /data` 查看模拟器可用空间。在 Device Manager 新建一台有充足内部存储的 AVD，运行 `npx expo run:android --device` 选中它，即可保留原设备的数据。
- **模拟器显示找不到开发包**：先在本目录运行 `npx expo run:android` 安装含叠石原生模块的开发包。Expo Go 只包含预装的原生模块。
- **模拟器连接不上 Metro**：运行 `adb devices` 确认目标模拟器状态为 `device`。使用单台模拟器和默认端口时，可运行 `adb reverse tcp:8081 tcp:8081`，再运行 `npx expo start --android --dev-client --localhost`。
- **Gradle 提示缺少 SDK 或工具链**：在 SDK Manager 对照“准备环境”检查组件版本，在当前终端核对 `JAVA_HOME` 和 `ANDROID_HOME`。Windows 可运行 `where.exe java` 检查 PATH 中优先使用的 Java。

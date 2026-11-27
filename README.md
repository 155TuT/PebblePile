<h1 align="center">
  <img src="assets/app-icon.svg" width="40" height="40" alt="">
  PebblePile · 叠石
</h1>

<p align="center">移动端本地优先的个人记忆与偏好建模服务</p>

<p align="center">
  <a href="https://github.com/155TuT/PebblePile/releases/tag/v0.1.0">下载 Android 版</a> ·
  <a href="#本地开发">本地开发</a> ·
  <a href="docs/README.md">项目文档</a> ·
  <a href="LICENSE">MIT License</a>
</p>

叠石是 2601 福州大学软件工程团队作业。用户把日常经历记在手机上，下次做决定时，可以查看哪些原话支持这次建议，再把实际结果和自己的纠正留在原记录里。当前交付 Android 客户端，首个离线场景是晚餐选择。

## 体验叠石

在 Android 8.0 及以上设备安装 [0.1.0 稳定版](https://github.com/155TuT/PebblePile/releases/tag/v0.1.0)，可以用一顿晚餐走完整个流程。

1. 在首页记下两次加班时的晚餐经历，也可以从其他应用分享一段原话，到记录库里找回它。
2. 问“今天周四加班，预算二十元，晚饭吃什么？”点开建议的依据，回看是哪两次经历影响了这次推荐。
3. 把实际吃的菜记在这次建议下，或把“优先省事”限定到工作日加班。下次提问时，叠石会按新条件比较。原来的建议和反馈仍可一起回看。[完整晚餐示例](docs/releases/0.1.0.md)

页面设计可见 [Paper 移动端原型设计](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0)。

由于Paper无法以交互的形式体验原型，我们提供了下载仓库后用浏览器打开的[本地点击导览](assets/prototype/index.html)用以浏览虚构样例。

## 本地开发

首次开发需要准备 Android 工具链，并在 Android Studio 里创建一台虚拟手机。以后在仓库根目录运行 Expo 即可打开叠石。

1. 安装 Node.js 22.x（项目最低要求 20.19.4）、OpenJDK 17 和 Android Studio。项目的环境检查和原生构建脚本使用 Python 3.9 或更新版本。[工具下载与版本](docs/development.md#安装开发工具)
2. 在 Android Studio 的 **SDK Manager** 安装 Android SDK Platform 36 和 35、对应构建工具、NDK、CMake 和模拟器。设置 `JAVA_HOME`、`ANDROID_HOME`，再把 JDK 的 `bin` 和 SDK 的 `platform-tools`、`emulator` 加入 PATH。[SDK 组件清单](docs/development.md#安装-android-sdk) · [环境变量设置](docs/development.md#配置环境变量)
3. 在 **Device Manager** 创建一台 API 36 虚拟手机。打开终端进入仓库根目录，检查本机环境，再按锁文件安装依赖。[创建模拟器](docs/development.md#创建模拟器)

```sh
python scripts/check-android-env.py
npm ci
```

准备完成后，用一条命令生成 Android 宿主、编译并安装开发包、启动 Metro。模拟器打开首页后即可录入第一条记录。

```sh
npx expo run:android
```

保存 TypeScript 文件后，Fast Refresh 会更新正在运行的页面。开发包已安装时，下次只改 TypeScript 可以用 `npx expo start --android --dev-client`。改动 Kotlin、Room 或原生模块后，重新运行 `npx expo run:android`。开发包与正式版各自保存资料。[断点调试与常见问题](docs/development.md#热刷新与调试)

## 目录结构

```text
PebblePile/
├── .github/workflows/       # Android 检查、签名构建与发布
├── android/                 # Expo 在本地生成的 Android 宿主
├── assets/                  # 品牌、设计 token、字体、图标和原型导览
│   └── android/             # 应用打包使用的资源与许可
├── components/              # 共用 Compose 控件、主题与预览
├── config/                  # Expo 宿主生成插件、应用版本
│   └── gradle/              # 独立 Android 工程、模块配置与 Gradle Wrapper
├── docs/                    # 项目文档索引、产品设计、开发与发布指南
├── lib/                     # Kotlin 领域模型、仓储契约、业务、算法与调度
├── modules/pebble-client/   # Expo 与原生客户端之间的桥接
├── scripts/                 # Python 环境检查、构建与资产维护脚本
├── src/
│   ├── App.tsx              # React 应用入口
│   ├── runtime/             # 应用装配、系统分享与任务分派
│   ├── screens/             # 首页、记录库、详情与设置
│   ├── storage/             # Room 数据库、迁移与仓储实现
│   └── work/                # WorkManager 唤醒与重试
├── test/                    # 单元和设备测试、固定样例与发布校验
├── utils/                   # 跨模块共用工具
├── app.json                 # Expo 应用配置
├── CONTRIBUTING.md          # 参与说明
└── AGENTS.md                # AI 协作约定
```

开发包和正式包共用同一份 Kotlin 源码。Expo 可以从 `app.json`、配置插件和原生模块重建整个 `android/`。要单独构建 APK，从仓库根目录运行 `python scripts/gradle.py :app:assembleDebug`。[客户端工程说明](docs/android-client.md) · [构建、签名与发布](docs/android-build-release.md)

## 参与协作

协作前阅读 [参与说明](CONTRIBUTING.md) 与 [AI 协作约定](AGENTS.md)。修改设计时，同步受影响的说明、Paper 画板与本地资产。

## 许可证

仓库采用 [MIT License](LICENSE)。字体与图标的来源和许可见对应资产说明。

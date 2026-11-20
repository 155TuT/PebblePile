# Android 构建、签名与发布

李宗欣交付签名 APK、提交号和 SHA-256。林金晶和林叶鑫用同一份包做用户试用和真机回归，王智壹确认后，将验收通过的原文件发布到团队仓库。

## 环境基线

| 项目 | 取值 |
| --- | --- |
| 应用工程 | `android/` |
| 包名 | `com.pebblepile.android` |
| 最低 Android | 8.0 / API 26 |
| compileSdk / targetSdk | 35 / 35 |
| JDK | 17；本地 JDK 21 也可用于构建 |
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| CI 模拟器 | Android 35 Google APIs，x86_64 |

首页、记录库、系统分享和详情已在模拟器上走通。首测手机安装后，李宗欣登记机型、系统版本和 APK 校验值，林叶鑫按同一份包回归晚餐流程。[Issue #5 验证](../android/ISSUE5_VERIFICATION.md)

## 版本规则

版本只在 [`android/version.properties`](../android/version.properties) 维护：

```properties
versionName=0.1.0-alpha.1
versionCode=1
```

- 每次交付 APK 时递增 `versionCode`，已安装设备才能升级。
- 预发布使用 `0.1.0-alpha.1`、`0.1.0-rc.1` 等名称；修复后递增 `.2`、`.3`。
- 最终验收候选的 `versionName` 必须是 `0.1.0`，`versionCode` 必须大于此前所有包。
- Git 标签为 `v` 加版本名，例如 `v0.1.0-alpha.1`。
- 普通构建可用 `-PpebblepileVersionName` 和 `-PpebblepileVersionCode` 临时覆盖，但正式候选包只使用提交中的版本文件。
- 每次构建候选包前更新 [`android/release-notes.md`](../android/release-notes.md) 的支持范围、样例和已知问题；CI 会把这些内容附到自动生成的版本信息后。

## 调试构建

Windows PowerShell：

```powershell
$env:JAVA_HOME = "<JDK 17 或 21 目录>"
$env:ANDROID_HOME = "<Android SDK 目录>"

cd android
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

macOS / Linux：

```bash
export JAVA_HOME="<JDK 17 或 21 目录>"
export ANDROID_HOME="<Android SDK 目录>"

cd android
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

在模拟器或已开启 USB 调试的设备上安装并启动：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -W -n com.pebblepile.android/.MainActivity
```

工程路径可以包含中文，Gradle 配置已开启 `android.overridePathCheck=true`。

## 签名材料

发布负责人首次创建并离线备份专用密钥：

```powershell
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\Documents\pebblepile-release.jks" `
  -alias pebblepile `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

发布负责人离线保管密钥和密码，后续版本用同一把密钥签名，让已安装的用户可以直接升级。

在团队发布仓库 `155TuT/PebblePile` 的 **Settings → Secrets and variables → Actions** 配置以下四项。

| Secret | 内容 |
| --- | --- |
| `PEBBLEPILE_KEYSTORE_BASE64` | JKS 文件的单行 Base64 |
| `PEBBLEPILE_KEYSTORE_PASSWORD` | keystore password |
| `PEBBLEPILE_KEY_ALIAS` | 通常为 `pebblepile` |
| `PEBBLEPILE_KEY_PASSWORD` | key password |

在 Windows 上用下列命令把 Base64 复制到剪贴板，粘贴到对应的 GitHub Secret。

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("$env:USERPROFILE\Documents\pebblepile-release.jks")
) | Set-Clipboard
```

## 本地签名构建

以下安全读取写法需要 PowerShell 7（`pwsh`）；环境变量只在单个终端会话中有效：

```powershell
$env:PEBBLEPILE_KEYSTORE_PATH = "$env:USERPROFILE\Documents\pebblepile-release.jks"
$env:PEBBLEPILE_KEYSTORE_PASSWORD = Read-Host "Keystore password" -AsSecureString |
  ConvertFrom-SecureString -AsPlainText
$env:PEBBLEPILE_KEY_ALIAS = "pebblepile"
$env:PEBBLEPILE_KEY_PASSWORD = Read-Host "Key password" -AsSecureString |
  ConvertFrom-SecureString -AsPlainText

cd android
.\gradlew.bat clean assembleRelease
```

运行 `assembleRelease` 前，在同一终端设置上面四个签名变量。

产物与校验：

```powershell
$version = (ConvertFrom-StringData (Get-Content version.properties -Raw)).versionName
$source = "app\build\outputs\apk\release\app-release.apk"
$target = "..\dist\PebblePile-$version.apk"

New-Item -ItemType Directory -Force ..\dist | Out-Null
Copy-Item $source $target
Get-FileHash $target -Algorithm SHA256 |
  ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" } |
  Set-Content ..\dist\SHA256SUMS.txt -Encoding ascii
```

检查签名与包信息：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" `
  verify --verbose --print-certs $source
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt.exe" dump badging $source |
  Select-String "package:|sdkVersion:|targetSdkVersion:"
```

## GitHub Actions 自动流程

### 1. 日常检查

[`android-ci.yml`](../.github/workflows/android-ci.yml) 在 `android/` 变更的 PR 和 `main` 推送中：

1. 使用干净 Ubuntu runner、JDK 17、Android SDK 35。
2. 运行单元测试、Lint 和 `assembleDebug`。
3. 上传调试 APK，保留 14 天。

### 2. 构建候选包

在仓库 Actions 中选择 **Android release → Run workflow**：

1. `mode` 选 `candidate`。
2. 保持 `publish=false`，把候选 artifact 交给试用和真机回归负责人。首页和分享接通正式读写后可发 alpha；真实晚餐流程和中断恢复通过后可发 rc。发布负责人已核对本轮预发布条件时，也可以选 `publish=true`，由 CI 直接发布本次 alpha 或 rc。
3. 工作流读取提交中的 `version.properties`，用 GitHub Secrets 临时还原密钥。
4. CI 构建签名 APK，检查签名证书、`versionCode` 和 `versionName`。
5. 在 API 35 模拟器上先跑 Debug 仪器测试，再安装签名 APK，启动首页并检查应用进程。
6. 生成 `PebblePile-<版本>.apk`、`SHA256SUMS.txt`、`build-info.json` 和发布说明，上传 90 天。

Candidate 结果的 `GITHUB_STEP_SUMMARY` 会列出：

- candidate commit；
- Actions run ID；
- artifact 名称；
- APK SHA-256。

这几个值与对应 artifact 名称交给真机测试人，验收人也必须记录同一 SHA-256。

### 3. 原样发布候选包或最终 APK

候选包完成所需试用和真机回归后，在 **Android release → Run workflow** 选择 `promote`。alpha／rc 会发布为预发布，最终版会创建 `v0.1.0` 并标记 latest：

1. `mode` 选 `promote`。
2. 填写验收包的 `source_run_id`。
3. 填写同一包的 `source_artifact`。
4. 把验收记录中的 64 位 APK SHA-256 填入 `expected_sha256`。

Promote 从团队仓库成功的 Android release 运行中下载指定候选包，核对运行编号、尝试次数、提交号和版本，再对 APK 本身计算 SHA-256。摘要与构建信息、校验文件和验收记录一致后，工作流在原提交上创建标签，发布同一个 APK。重试时，同名标签须指向该提交。

命令行入口与网页相同：

```powershell
gh workflow run android-release.yml -f mode=candidate -f publish=false

gh workflow run android-release.yml `
  -f mode=promote `
  -f publish=false `
  -f source_run_id=<RUN_ID> `
  -f source_artifact=PebblePile-android-<VERSION>-<COMMIT_SHA>-<RUN_ATTEMPT> `
  -f expected_sha256=<SHA256>
```

## 验收清单

发布负责人和测试人共同确认：

- [ ] 干净 checkout 可按本文完成 debug 构建。
- [ ] Candidate 工作流的签名校验、connected tests 和启动检查全部通过。
- [ ] APK、提交号、版本号和 SHA-256 已写入交接记录。
- [ ] 真机安装启动成功，测试人下载的就是登记哈希的 APK。
- [ ] 首页、系统分享、记录库、详情、进程恢复和真实晚餐流程已按对应 Issue 回归。
- [ ] alpha／rc 发布说明列出支持范围、安装步骤、样例和已知问题。
- [ ] 正式版用 promote 发布已验收的 APK，下载后的 SHA-256 与报告一致。

## 常见问题

**`assembleRelease` 提示签名未配置**

确认四个 `PEBBLEPILE_*` 环境变量都在当前终端有效；CI 中确认四个 GitHub Secrets 已配置。

**设备提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**

设备上的同包名应用使用了另一份签名。先备份需要保留的测试数据，再卸载旧包；正式用户升级必须始终使用同一密钥。

**GitHub Release 创建失败，提示 tag 已存在**

核对已有版本的提交。已有标签指向其他提交时，递增版本并生成新候选包。

**最终候选与 Release 哈希不同**

重新下载验收记录对应的候选 artifact，逐项核对运行编号、尝试次数、文件名和 SHA-256，再用同一文件发布。

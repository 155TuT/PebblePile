# Android 构建、签名与发布

本文说明 PebblePile Android 0.1.0 的构建、签名、候选包验证和 GitHub Release 流程。版本、构建命令、产物和校验值必须对得上；正式版发布的是已验收的同一个 APK，不在发布前重新构建。

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

仓库已有首页、记录库、系统分享和详情的模拟器验证记录，见 [Issue #5 验证](../android/ISSUE5_VERIFICATION.md)。该记录早于签名发布流程，不能替代对候选 APK、真机安装和真实晚餐流程的最终验收。

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

## 无签名构建

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

密钥文件、store password 和 key password 只由发布负责人保管，不进入仓库、Issue、PR、聊天记录或构建日志。密钥丢失后，使用同一包名的新 APK 无法覆盖安装旧版本。

在 `Ki-K4/PebblePile` 的 **Settings → Secrets and variables → Actions** 添加：

| Secret | 内容 |
| --- | --- |
| `PEBBLEPILE_KEYSTORE_BASE64` | JKS 文件的单行 Base64 |
| `PEBBLEPILE_KEYSTORE_PASSWORD` | keystore password |
| `PEBBLEPILE_KEY_ALIAS` | 通常为 `pebblepile` |
| `PEBBLEPILE_KEY_PASSWORD` | key password |

Windows 可生成 Base64；只把结果粘贴到 GitHub Secret，不要存进仓库：

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

若签名变量不完整，`assembleRelease` 会直接失败，避免误把未签名包或调试签名包当成交付文件。

产物与校验：

```powershell
$version = ((Get-Content version.properties | Where-Object { $_ -like 'versionName=*' }) -replace '^versionName=', '')[0]
$source = "app\build\outputs\apk\release\app-release.apk"
$target = "..\dist\PebblePile-$version.apk"

New-Item -ItemType Directory -Force ..\dist | Out-Null
Copy-Item $source $target
Get-FileHash $target -Algorithm SHA256 |
  ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" } |
  Set-Content ..\dist\SHA256SUMS.txt
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
2. 默认保持 `publish=false`，先取得可下载的候选 artifact；外部试用和真机回归结束后再用 promote 发布同一文件。只有只需 CI 自动检查便可公开时，才把 `publish` 设为 `true`。
3. 工作流读取提交中的 `version.properties`，用 GitHub Secrets 临时还原密钥。
4. CI 构建签名 APK，检查签名证书、`versionCode` 和 `versionName`。
5. 在 Android 35 模拟器运行 connected tests，再安装并启动签名包。
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
4. 填写验收记录中的 `expected_sha256`。

Promote 不构建 APK。它从原 Actions run 下载 artifact，校验成功状态、提交号、`SHA256SUMS.txt` 和期望值，然后在原提交创建标签并上传同一个 APK。若校验不一致，工作流停止且不创建 Release。

命令行入口与网页相同：

```powershell
gh workflow run android-release.yml -f mode=candidate -f publish=false

gh workflow run android-release.yml `
  -f mode=promote `
  -f publish=false `
  -f source_run_id=<RUN_ID> `
  -f source_artifact=PebblePile-android-<VERSION>-<COMMIT_SHA> `
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
- [ ] 最终 Release 使用 promote 的同一 APK，没有重新构建。

## 常见问题

**`assembleRelease` 提示签名未配置**

确认四个 `PEBBLEPILE_*` 环境变量都在当前终端有效；CI 中确认四个 GitHub Secrets 已配置。

**设备提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**

设备上的同包名应用使用了另一份签名。先备份需要保留的测试数据，再卸载旧包；正式用户升级必须始终使用同一密钥。

**GitHub Release 创建失败，提示 tag 已存在**

检查是否已发布过同名版本。不要移动已公开的 Release tag；修复后递增预发布编号或 `versionCode` 并生成新候选包。

**最终候选与 Release 哈希不同**

停止发布。Promote 过程不得重新构建 APK，应重新下载候选 artifact 并核对 run ID、artifact 名称和 SHA-256。

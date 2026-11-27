#!/usr/bin/env python3
"""Check the local toolchain for Expo and the standalone Android application."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
EXE = ".exe" if os.name == "nt" else ""


def output(command, *args):
    if not command:
        return ""
    try:
        result = subprocess.run([command, *args], capture_output=True, text=True,
                                errors="replace", timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout.strip() if result.returncode == 0 else ""


def check(label, value, remedy):
    print(f"[OK] {label}  {value}" if value else f"[缺少] {label}  {remedy}")
    return bool(value)


def check_node():
    manifest = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
    minimum = manifest["engines"]["node"].removeprefix(">=")
    version = output(shutil.which("node"), "--version")
    parts = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", version)
    supported = parts and tuple(map(int, parts.groups())) >= tuple(map(int, minimum.split(".")))
    node = check("Node.js", version if supported else "", f"安装 Node.js {minimum} 或更新版本并加入 PATH。")
    npm = output(shutil.which("npm.cmd" if os.name == "nt" else "npm"), "--version")
    return check("npm", npm, "请安装 Node.js 自带的 npm 并加入 PATH。") and node


def check_java():
    home = os.environ.get("JAVA_HOME")
    if not home:
        return check("JAVA_HOME", "", "设置为 OpenJDK 17 的安装目录。")
    java_bin = Path(home) / "bin"
    launcher = output(java_bin / f"java{EXE}", "--version").splitlines()
    compiler = output(java_bin / f"javac{EXE}", "-version")
    is_jdk17 = (launcher and re.search(r"\b17(?:[.+-]|\b)", launcher[0])
                and re.match(r"javac 17(?:[.+-]|\b)", compiler))
    valid = check("JAVA_HOME", f"{home} ({compiler})" if is_jdk17 else "",
                  "将 JAVA_HOME 指向同时包含 java 和 javac 的 JDK 17。")
    if valid and not re.search(r"\b17(?:[.+-]|\b)", output(shutil.which("java"), "--version").partition("\n")[0]):
        print("[提示] 当前 PATH 中的 java 与 JAVA_HOME 不一致。将 JAVA_HOME/bin 加到 PATH 首位。")
    return valid


def check_sdk():
    home = os.environ.get("ANDROID_HOME")
    if not check("ANDROID_HOME", home if home and Path(home).is_dir() else "",
                 "设为 Android Studio → SDK Manager 显示的 Android SDK Location。"):
        return False
    sdk = Path(home)
    packages = {
        "platforms;android-35": "platforms/android-35/android.jar",
        "platforms;android-36": "platforms/android-36/android.jar",
        "build-tools;34.0.0": f"build-tools/34.0.0/aapt2{EXE}",
        "build-tools;36.0.0": f"build-tools/36.0.0/aapt2{EXE}",
        "ndk;27.1.12297006": "ndk/27.1.12297006/build/cmake/android.toolchain.cmake",
        "cmake;3.22.1": f"cmake/3.22.1/bin/cmake{EXE}",
        "cmdline-tools;latest": "cmdline-tools/latest/bin/sdkmanager" + (".bat" if os.name == "nt" else ""),
    }
    results = []
    for package, relative in packages.items():
        results.append(check(package, relative if (sdk / relative).is_file() else "",
                             f"在 SDK Manager 安装 {package}。"))
    adb = output(sdk / "platform-tools" / f"adb{EXE}", "version").splitlines()
    results.append(check("adb", adb[0] if adb else "", "在 SDK Manager 安装 Android SDK Platform-Tools。"))
    emulator = sdk / "emulator" / f"emulator{EXE}"
    if not check("Android Emulator", emulator if emulator.is_file() else "", "在 SDK Tools 安装 Android Emulator。"):
        return False
    avds = output(emulator, "-list-avds").splitlines()
    results.append(check("AVD", ", ".join(avds), "在 Device Manager 创建手机模拟器，并安装对应系统镜像。"))
    return all(results)


def main():
    argparse.ArgumentParser(description="检查 Android SDK、JDK、Node.js 与模拟器配置。").parse_args()
    checks = [check_node(), check_java(), check_sdk()]
    if all(checks):
        print("环境已就绪。首次运行 npm ci，随后用 npx expo run:android 启动。")
        return 0
    print("按提示补齐配置后，打开新终端重新检查。")
    return 1


if __name__ == "__main__":
    sys.exit(main())

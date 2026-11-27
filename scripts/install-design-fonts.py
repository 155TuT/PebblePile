#!/usr/bin/env python3
"""Install the local design fonts for the current Windows user."""

import argparse
import ctypes
import filecmp
import os
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
FONTS = {
    "lxgw-wenkai/LXGWWenKai-Regular.ttf": "LXGW WenKai (TrueType)",
    "lxgw-wenkai/LXGWWenKai-Medium.ttf": "LXGW WenKai Medium (TrueType)",
    "leftist-mono-serif/LeftistMonoSerif.ttf": "Leftist Mono Serif (TrueType)",
}


def check_files(font_root, destination):
    files = []
    for filename, name in FONTS.items():
        source = font_root / filename
        target = destination / source.name
        if not source.is_file():
            sys.exit(f"缺少字体 {source}。来源见 assets/fonts/README.md。")
        if target.exists() and not filecmp.cmp(source, target, shallow=False):
            sys.exit(f"已安装的同名字体内容不同，请先核对 {target}。")
        files.append((source, target, name))
    return files


def install(font_root):
    import winreg
    from ctypes import wintypes

    destination = Path(os.environ["LOCALAPPDATA"]) / "Microsoft/Windows/Fonts"
    files = check_files(font_root, destination)
    destination.mkdir(parents=True, exist_ok=True)
    add_font = ctypes.windll.gdi32.AddFontResourceExW
    add_font.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.LPVOID]
    key_path = r"Software\Microsoft\Windows NT\CurrentVersion\Fonts"
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key_path) as key:
        for source, target, name in files:
            if not target.exists():
                # Windows rename publishes the completed file and rejects a
                # conflicting destination, including a concurrent installation.
                with TemporaryDirectory(dir=destination) as staging:
                    staged = Path(staging) / target.name
                    shutil.copyfile(source, staged)
                    try:
                        staged.rename(target)
                    except FileExistsError:
                        if not filecmp.cmp(source, target, shallow=False):
                            raise
            winreg.SetValueEx(key, name, 0, winreg.REG_SZ, str(target))
            loaded = add_font(str(target), 0, None)
            print(f"{name} 已注册，加载 {loaded} 项字体资源。")

    # Notify open applications that the current user's fonts have changed.
    broadcast = ctypes.windll.user32.SendMessageTimeoutW
    broadcast.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM,
                          wintypes.UINT, wintypes.UINT, ctypes.POINTER(ctypes.c_size_t)]
    broadcast.restype = wintypes.LPARAM
    broadcast(0xFFFF, 0x001D, 0, 0, 2, 3000, ctypes.byref(ctypes.c_size_t()))
    print("已安装到当前用户字体目录。重新加载 Paper 后可核对字体。")


def main():
    parser = argparse.ArgumentParser(description="为当前 Windows 用户安装三份本地设计字体。")
    parser.add_argument("--font-root", type=Path, default=ROOT / ".local/fonts",
                        help="设计字体源目录（默认 .local/fonts）")
    args = parser.parse_args()
    if os.name != "nt":
        sys.exit("此脚本适用于 Windows 当前用户。字体清单见 assets/fonts/README.md。")
    if not os.environ.get("LOCALAPPDATA"):
        sys.exit("请在 Windows 用户会话中配置 LOCALAPPDATA。")
    try:
        install(args.font_root)
    except OSError as error:
        sys.exit(f"字体安装失败。{error}")


if __name__ == "__main__":
    main()

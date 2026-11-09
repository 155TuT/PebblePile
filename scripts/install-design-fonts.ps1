param([string]$FontRoot)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $FontRoot) { $FontRoot = Join-Path $repoRoot '.local/fonts' }
$fontSpecs = @(
    @{ File = 'lxgw-wenkai/LXGWWenKai-Regular.ttf'; Name = 'LXGW WenKai (TrueType)' },
    @{ File = 'lxgw-wenkai/LXGWWenKai-Medium.ttf'; Name = 'LXGW WenKai Medium (TrueType)' },
    @{ File = 'leftist-mono-serif/LeftistMonoSerif.ttf'; Name = 'Leftist Mono Serif (TrueType)' }
)
$targetRoot = Join-Path $env:LOCALAPPDATA 'Microsoft/Windows/Fonts'
$registryPath = 'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\Fonts'
$resolved = foreach ($spec in $fontSpecs) {
    $source = Join-Path $FontRoot $spec.File
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "缺少字体：$source。来源见 assets/fonts/README.md。" }
    $target = Join-Path $targetRoot ([IO.Path]::GetFileName($source))
    if ((Test-Path -LiteralPath $target) -and ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $target).Hash)) {
        throw "已安装的同名字体内容不同，请先核对：$target"
    }
    [pscustomobject]@{ Source = $source; Target = $target; Name = $spec.Name }
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class PebblePileFontInstall {
    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    public static extern int AddFontResourceEx(string path, uint flags, IntPtr reserved);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeout, out UIntPtr result);
}
'@

New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
New-Item -Path $registryPath -Force | Out-Null
foreach ($font in $resolved) {
    if (-not (Test-Path -LiteralPath $font.Target)) { Copy-Item -LiteralPath $font.Source -Destination $font.Target }
    New-ItemProperty -Path $registryPath -Name $font.Name -Value $font.Target -PropertyType String -Force | Out-Null
    $loaded = [PebblePileFontInstall]::AddFontResourceEx($font.Target, 0, [IntPtr]::Zero)
    Write-Output "$($font.Name): registered; loaded resources=$loaded"
}
[UIntPtr]$broadcastResult = [UIntPtr]::Zero
[void][PebblePileFontInstall]::SendMessageTimeout([IntPtr]0xffff, 0x001D, [UIntPtr]::Zero, [IntPtr]::Zero, 2, 3000, [ref]$broadcastResult)
Write-Output '已安装到当前用户字体目录。请在 Paper 中核对字体可用性；已打开的应用可能需要刷新字体列表。'

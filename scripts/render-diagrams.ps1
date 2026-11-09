param(
    [string]$PlantUmlJar,
    [string]$JavaPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $PlantUmlJar) {
    $PlantUmlJar = Join-Path $repoRoot '.build/tools/plantuml.jar'
}
if (-not (Test-Path -LiteralPath $PlantUmlJar -PathType Leaf)) {
    throw '请先下载 PlantUML 官方 JAR 到 .build/tools/plantuml.jar，或用 -PlantUmlJar 指定路径：https://github.com/plantuml/plantuml/releases'
}
if (-not $JavaPath) {
    $javaCandidates = @()
    if ($env:JAVA_HOME) {
        $javaCandidates += Join-Path $env:JAVA_HOME 'bin/java.exe'
    }
    if ($env:ProgramFiles) {
        $javaCandidates += Join-Path $env:ProgramFiles 'Java/jdk-17/bin/java.exe'
    }
    $JavaPath = $javaCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $JavaPath) {
        $JavaPath = (Get-Command java -ErrorAction Stop).Source
    }
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'docs/diagrams') -Filter '*.puml' | Sort-Object Name | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'docs/diagrams 中没有 PlantUML 源文件。' }
& $JavaPath '-Djava.awt.headless=true' -jar $PlantUmlJar -charset UTF-8 -failfast2 -tsvg -nometadata @sources
if ($LASTEXITCODE -ne 0) { throw "PlantUML 渲染失败，退出码 $LASTEXITCODE。" }
Write-Output "已生成 $($sources.Count) 张 SVG。"

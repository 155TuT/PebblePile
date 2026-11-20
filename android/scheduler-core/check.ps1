param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Kotlinc = $env:KOTLINC
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
if (-not $Kotlinc) {
    $Kotlinc = Join-Path $root '.build\tools\kotlinc\bin\kotlinc.bat'
}
if (-not (Test-Path -LiteralPath $Kotlinc)) {
    throw 'Kotlin compiler not found. Set KOTLINC or install it under .build/tools/kotlinc.'
}
if (-not $JavaHome) {
    $detected = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($detected) { $JavaHome = $detected.FullName }
}
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
    $java = Join-Path $JavaHome 'bin\java.exe'
} else {
    $java = 'java'
}

$build = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$jar = Join-Path $build 'scheduler-self-test.jar'
& $Kotlinc (Join-Path $PSScriptRoot 'src\main\kotlin') (Join-Path $PSScriptRoot 'src\test\kotlin') -include-runtime -jvm-target 17 -d $jar
& $java -jar $jar

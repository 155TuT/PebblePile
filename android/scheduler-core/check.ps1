$ErrorActionPreference = 'Stop'
$android = Split-Path -Parent $PSScriptRoot
& (Join-Path $android 'gradlew.bat') -p $android ':scheduler-core:test' ':scheduler-android:testDebugUnitTest' ':scheduler-android:assembleDebug'
exit $LASTEXITCODE

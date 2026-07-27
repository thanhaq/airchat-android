param(
    [switch]$SkipLint
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME is not set. Install JDK 17 and point JAVA_HOME to it."
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Error "ANDROID_HOME or ANDROID_SDK_ROOT is not set. Install Android SDK platform 35 first."
}

$tasks = @(":app:testDebugUnitTest", ":app:assembleDebug")
if (-not $SkipLint) {
    $tasks += ":app:lintDebug"
}

& .\gradlew.bat @tasks

$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Get-FileHash $apk -Algorithm SHA256
} else {
    Write-Error "Debug APK was not produced at $apk"
}

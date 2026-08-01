param(
    [switch]$SkipLint
)

$ErrorActionPreference = "Stop"

$variant = "FdroidDebug"
$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\fdroid\debug\app-fdroid-debug.apk"

if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME is not set. Install JDK 17 and point JAVA_HOME to it."
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Error "ANDROID_HOME or ANDROID_SDK_ROOT is not set. Install Android SDK platform 35 first."
}

$tasks = @(":app:test${variant}UnitTest", ":app:assemble$variant")
if (-not $SkipLint) {
    $tasks += ":app:lint$variant"
}

& .\gradlew.bat @tasks

if (Test-Path $apk) {
    Get-FileHash $apk -Algorithm SHA256
} else {
    Write-Error "Debug APK was not produced at $apk"
}

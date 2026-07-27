param(
    [string]$Version = "local"
)

$ErrorActionPreference = "Stop"

.\scripts\preflight.ps1

$releaseDir = Join-Path (Get-Location) "release"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

$apkSource = Join-Path (Get-Location) "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkSource)) {
    Write-Error "Debug APK was not produced at $apkSource"
}

$safeVersion = $Version -replace '[^A-Za-z0-9._-]', '-'
$apkName = "airchat-$safeVersion-debug-test.apk"
$apkTarget = Join-Path $releaseDir $apkName
Copy-Item -LiteralPath $apkSource -Destination $apkTarget -Force

$hash = (Get-FileHash $apkTarget -Algorithm SHA256).Hash
$shaFile = Join-Path $releaseDir "SHA256SUMS.txt"
[System.IO.File]::WriteAllText(
    $shaFile,
    "$hash  $apkName`n",
    [System.Text.UTF8Encoding]::new($false)
)

$notesFile = Join-Path $releaseDir "RELEASE_NOTES.md"
$notes = @"
# AirChat $Version

This is an early Android debug test build for offline Wi-Fi mesh field testing.

## Verification

- Source commit: $(git rev-parse HEAD)
- APK SHA-256: $hash
- Build command: ``.\scripts\package-debug-release.ps1 $Version``
- Test gate: ``.\scripts\preflight.ps1``

## Field-Test Before Public Promotion

- Run the LAN, Wi-Fi Direct, DM, file transfer, diagnostics, and background mesh checks in ``docs/TEST_PLAN.md``.
- Attach diagnostics text from both test devices.
- Label the APK as a debug test build until release signing is configured.
"@
[System.IO.File]::WriteAllText(
    $notesFile,
    $notes,
    [System.Text.UTF8Encoding]::new($false)
)

Get-ChildItem $releaseDir | Select-Object Name,Length,LastWriteTime

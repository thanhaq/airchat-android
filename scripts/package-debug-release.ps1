param(
    [string]$Version = "local"
)

$ErrorActionPreference = "Stop"

$flavor = "fdroid"
$variant = "FdroidDebug"
$variantSlug = "fdroidDebug"

.\scripts\preflight.ps1

$releaseDir = Join-Path (Get-Location) "release"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$generatedReleaseFiles = @("SHA256SUMS.txt", "RELEASE_NOTES.md", "RELEASE_MANIFEST.json")
Get-ChildItem -LiteralPath $releaseDir -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "airchat-*.apk" -or $_.Name -in $generatedReleaseFiles } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

$apkSource = Join-Path (Get-Location) "app\build\outputs\apk\$flavor\debug\app-$flavor-debug.apk"
if (-not (Test-Path $apkSource)) {
    Write-Error "Debug APK was not produced at $apkSource"
}

$safeVersion = $Version -replace '[^A-Za-z0-9._-]', '-'
$apkName = "airchat-$safeVersion-$flavor-debug-test.apk"
$apkTarget = Join-Path $releaseDir $apkName
Copy-Item -LiteralPath $apkSource -Destination $apkTarget -Force

$hash = (Get-FileHash $apkTarget -Algorithm SHA256).Hash
$sourceCommit = (git rev-parse HEAD)
$apkSize = (Get-Item -LiteralPath $apkTarget).Length
$generatedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$shaFile = Join-Path $releaseDir "SHA256SUMS.txt"
[System.IO.File]::WriteAllText(
    $shaFile,
    "$hash  $apkName`n",
    [System.Text.UTF8Encoding]::new($false)
)

$manifestFile = Join-Path $releaseDir "RELEASE_MANIFEST.json"
$manifest = [ordered]@{
    schema = "dev.offlinemesh.airchat.release-manifest.v1"
    app = "AirChat"
    version = $Version
    variant = "debug-test"
    distributionFlavor = $flavor
    sourceCommit = $sourceCommit
    generatedAtUtc = $generatedAt
    apk = [ordered]@{
        file = $apkName
        sha256 = $hash
        sizeBytes = $apkSize
        signingCertificateSha256 = $null
    }
    build = [ordered]@{
        command = ".\scripts\package-debug-release.ps1 $Version"
        testGate = ".\scripts\preflight.ps1"
        gradleVariant = $variantSlug
        compileSdk = 35
        minSdk = 26
        targetSdk = 35
    }
}
[System.IO.File]::WriteAllText(
    $manifestFile,
    ($manifest | ConvertTo-Json -Depth 5) + "`n",
    [System.Text.UTF8Encoding]::new($false)
)

$notesFile = Join-Path $releaseDir "RELEASE_NOTES.md"
$notes = @"
# AirChat $Version

This is an early F-Droid-flavored Android debug test build for offline Wi-Fi mesh field testing.

## Verification

- Source commit: $sourceCommit
- Distribution flavor: $flavor
- Gradle variant: $variantSlug
- APK SHA-256: $hash
- Machine-readable manifest: ``RELEASE_MANIFEST.json``
- Build command: ``.\scripts\package-debug-release.ps1 $Version``
- Test gate: ``.\scripts\preflight.ps1``

## Field-Test Before Public Promotion

- Run the LAN, Wi-Fi Direct, DM, file transfer, diagnostics, and background mesh checks in ``docs/TEST_PLAN.md``.
- Attach diagnostics text from both test devices.
- Attach the Markdown comparison from ``scripts/compare-diagnostics.ps1``.
- Label the APK as a debug test build until release signing is configured.
"@
[System.IO.File]::WriteAllText(
    $notesFile,
    $notes,
    [System.Text.UTF8Encoding]::new($false)
)

Get-ChildItem $releaseDir | Select-Object Name,Length,LastWriteTime

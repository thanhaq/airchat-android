param(
    [string]$Version = "local"
)

$ErrorActionPreference = "Stop"

function Get-RequiredEnv {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $value = [Environment]::GetEnvironmentVariable($Name)
    if (-not $value) {
        Write-Error "$Name is required for signed release packaging."
    }
    return $value
}

function Find-ApkSigner {
    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) {
        $sdkRoot = $env:ANDROID_SDK_ROOT
    }
    if (-not $sdkRoot) {
        return $null
    }

    $buildTools = Join-Path $sdkRoot "build-tools"
    if (-not (Test-Path -LiteralPath $buildTools)) {
        return $null
    }

    $tool = Get-ChildItem -LiteralPath $buildTools -Recurse -Filter "apksigner.bat" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($tool) {
        return $tool.FullName
    }
    return $null
}

function Get-ApkCertificateFingerprint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    $apkSigner = Find-ApkSigner
    if (-not $apkSigner) {
        Write-Error "apksigner not found. Install Android SDK build tools before packaging a signed release."
    }

    $output = & $apkSigner verify --print-certs $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error "apksigner verification failed for $ApkPath"
    }

    $line = $output | Select-String -Pattern "Signer #1 certificate SHA-256 digest:\s*(.+)" | Select-Object -First 1
    if (-not $line) {
        Write-Error "apksigner did not print a signer SHA-256 digest for $ApkPath"
    }

    return $line.Matches[0].Groups[1].Value.Trim()
}

$keystore = Get-RequiredEnv "AIRCHAT_KEYSTORE"
$keystorePassword = Get-RequiredEnv "AIRCHAT_KEYSTORE_PASSWORD"
$keyAlias = Get-RequiredEnv "AIRCHAT_KEY_ALIAS"

if (-not $env:AIRCHAT_KEY_PASSWORD) {
    $env:AIRCHAT_KEY_PASSWORD = $keystorePassword
}

if (-not (Test-Path -LiteralPath $keystore)) {
    Write-Error "AIRCHAT_KEYSTORE does not exist: $keystore"
}

& (Join-Path $PSScriptRoot "preflight.ps1")
& .\gradlew.bat :app:assembleRelease

$releaseDir = Join-Path (Get-Location) "release"
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$generatedReleaseFiles = @("SHA256SUMS.txt", "RELEASE_NOTES.md", "RELEASE_MANIFEST.json")
Get-ChildItem -LiteralPath $releaseDir -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "airchat-*.apk" -or $_.Name -in $generatedReleaseFiles } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

$apkSource = Join-Path (Get-Location) "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $apkSource)) {
    Write-Error "Signed release APK was not produced at $apkSource"
}

$safeVersion = $Version -replace '[^A-Za-z0-9._-]', '-'
$apkName = "airchat-$safeVersion-signed.apk"
$apkTarget = Join-Path $releaseDir $apkName
Copy-Item -LiteralPath $apkSource -Destination $apkTarget -Force

$hash = (Get-FileHash $apkTarget -Algorithm SHA256).Hash
$fingerprint = Get-ApkCertificateFingerprint $apkTarget
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
    variant = "signed-release"
    sourceCommit = $sourceCommit
    generatedAtUtc = $generatedAt
    apk = [ordered]@{
        file = $apkName
        sha256 = $hash
        sizeBytes = $apkSize
        signingCertificateSha256 = $fingerprint
    }
    build = [ordered]@{
        command = ".\scripts\package-signed-release.ps1 $Version"
        testGate = ".\scripts\preflight.ps1"
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

This is a signed Android APK for offline Wi-Fi mesh field testing and public GitHub release review.

## Verification

- Source commit: $sourceCommit
- APK SHA-256: $hash
- Signing certificate SHA-256: $fingerprint
- Machine-readable manifest: ``RELEASE_MANIFEST.json``
- Build command: ``.\scripts\package-signed-release.ps1 $Version``
- Test gate: ``.\scripts\preflight.ps1``

## Field-Test Before Public Promotion

- Run the LAN, Wi-Fi Direct, DM, file transfer, diagnostics, and background mesh checks in ``docs/TEST_PLAN.md``.
- Attach diagnostics text from both test devices.
- Attach the Markdown comparison from ``scripts/compare-diagnostics.ps1``.
- Keep the release as a draft until the field-test report is complete.
"@
[System.IO.File]::WriteAllText(
    $notesFile,
    $notes,
    [System.Text.UTF8Encoding]::new($false)
)

Get-ChildItem $releaseDir | Select-Object Name,Length,LastWriteTime

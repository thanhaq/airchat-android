param(
    [string]$ManifestPath = "release/RELEASE_MANIFEST.json",
    [switch]$SkipCertificate
)

$ErrorActionPreference = "Stop"

function Resolve-ApkSigner {
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

function Normalize-Hex {
    param([AllowNull()][string]$Value)

    if (-not $Value) {
        return ""
    }
    return (($Value.ToLowerInvariant() -replace '[^0-9a-f]', ''))
}

function Read-ApkCertificateFingerprint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    $apkSigner = Resolve-ApkSigner
    if (-not $apkSigner) {
        Write-Error "apksigner not found. Install Android SDK build tools or pass -SkipCertificate for hash-only verification."
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

if (-not (Test-Path -LiteralPath $ManifestPath)) {
    Write-Error "Release manifest not found: $ManifestPath"
}

$manifestFile = Get-Item -LiteralPath $ManifestPath
$releaseDir = $manifestFile.DirectoryName
$manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json

if ($manifest.schema -ne "dev.offlinemesh.airchat.release-manifest.v1") {
    Write-Error "Unsupported release manifest schema: $($manifest.schema)"
}

if ($manifest.variant -ne "debug-test" -and $manifest.variant -ne "signed-release") {
    Write-Error "Unsupported release variant: $($manifest.variant)"
}

if ($manifest.distributionFlavor) {
    if ($manifest.distributionFlavor -ne "fdroid") {
        Write-Error "Unsupported distribution flavor: $($manifest.distributionFlavor)"
    }

    $gradleVariant = $manifest.build.gradleVariant
    $expectedGradleVariant = if ($manifest.variant -eq "debug-test") { "fdroidDebug" } else { "fdroidRelease" }
    if ($gradleVariant -ne $expectedGradleVariant) {
        Write-Error "Gradle variant mismatch. Manifest=$gradleVariant, expected=$expectedGradleVariant"
    }
}

if (-not $manifest.apk.file) {
    Write-Error "Manifest is missing apk.file"
}

if (-not $manifest.apk.sha256 -or (Normalize-Hex $manifest.apk.sha256).Length -ne 64) {
    Write-Error "Manifest apk.sha256 is missing or invalid"
}

$apkPath = Join-Path $releaseDir $manifest.apk.file
if (-not (Test-Path -LiteralPath $apkPath)) {
    Write-Error "APK listed in manifest was not found: $apkPath"
}

$apkItem = Get-Item -LiteralPath $apkPath
if ([int64]$manifest.apk.sizeBytes -ne $apkItem.Length) {
    Write-Error "APK size mismatch. Manifest=$($manifest.apk.sizeBytes), actual=$($apkItem.Length)"
}

$actualHash = Normalize-Hex ((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash)
$manifestHash = Normalize-Hex $manifest.apk.sha256
if ($actualHash -ne $manifestHash) {
    Write-Error "APK SHA-256 mismatch. Manifest=$manifestHash, actual=$actualHash"
}

$shaFile = Join-Path $releaseDir "SHA256SUMS.txt"
if (-not (Test-Path -LiteralPath $shaFile)) {
    Write-Error "SHA256SUMS.txt not found beside manifest"
}

$escapedApkFile = [regex]::Escape($manifest.apk.file)
$shaLine = Get-Content -LiteralPath $shaFile |
    Where-Object { $_ -match "^\s*([0-9A-Fa-f]{64})\s+\*?$escapedApkFile\s*$" } |
    Select-Object -First 1
if (-not $shaLine) {
    Write-Error "SHA256SUMS.txt does not contain an entry for $($manifest.apk.file)"
}

$shaFileHash = Normalize-Hex ([regex]::Match($shaLine, "([0-9A-Fa-f]{64})").Groups[1].Value)
if ($shaFileHash -ne $actualHash) {
    Write-Error "SHA256SUMS.txt hash mismatch. SHA256SUMS=$shaFileHash, actual=$actualHash"
}

if ($manifest.variant -eq "signed-release") {
    $manifestFingerprint = Normalize-Hex $manifest.apk.signingCertificateSha256
    if ($manifestFingerprint.Length -ne 64) {
        Write-Error "Signed release manifest must include a 64-character signing certificate SHA-256 fingerprint"
    }

    if (-not $SkipCertificate) {
        $actualFingerprint = Normalize-Hex (Read-ApkCertificateFingerprint $apkPath)
        if ($actualFingerprint -ne $manifestFingerprint) {
            Write-Error "Signing certificate mismatch. Manifest=$manifestFingerprint, actual=$actualFingerprint"
        }
    }
}

Write-Host "Verified release manifest: $($manifestFile.FullName)"
Write-Host "Verified APK: $apkPath"
Write-Host "Variant: $($manifest.variant)"
if ($manifest.distributionFlavor) {
    Write-Host "Distribution flavor: $($manifest.distributionFlavor)"
    Write-Host "Gradle variant: $($manifest.build.gradleVariant)"
}
Write-Host "SHA-256: $actualHash"
if ($manifest.variant -eq "signed-release" -and -not $SkipCertificate) {
    Write-Host "Signing certificate SHA-256: $manifestFingerprint"
}

# Release Guide

This guide keeps releases repeatable without assuming any hosted server.

## Prerequisites

- JDK 17.
- Android Studio or Android SDK command-line tools.
- Android platform 35 and matching build tools.
- Two physical Android devices for field testing.

## Local verification

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Or run:

```powershell
.\scripts\preflight.ps1
```

On macOS or Linux:

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Then run the real-device checks in [TEST_PLAN.md](TEST_PLAN.md).

For public artifacts, also prepare the source/build evidence described in [VERIFY_RELEASE.md](VERIFY_RELEASE.md).
Use [FIELD_TEST_REPORT.md](FIELD_TEST_REPORT.md) to record device and transport evidence.

## Packaging a Test APK

For early field testing, create a labeled debug test package locally:

```powershell
.\scripts\package-debug-release.ps1 v0.1.0
```

On macOS or Linux:

```bash
bash ./scripts/package-debug-release.sh v0.1.0
```

The package script runs the full preflight gate, copies the debug APK into `release/`, writes `SHA256SUMS.txt`, and drafts release notes. The `release/` folder is ignored so generated artifacts do not enter source control.

## Versioning

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Move unreleased entries in `CHANGELOG.md` under a dated release heading.
3. Confirm `README.md`, `SECURITY.md`, and protocol docs still match behavior.

## Signing

For public GitHub releases, generate a release keystore outside the repository and keep it out of source control.

Example:

```powershell
keytool -genkeypair -v -keystore C:\secure\airchat-release.jks -alias airchat -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=AirChat Offline Mesh, OU=AirChat, O=Offline Mesh"
```

Recommended local environment variables:

```powershell
$env:AIRCHAT_KEYSTORE="C:\path\to\airchat-release.jks"
$env:AIRCHAT_KEYSTORE_PASSWORD="<store-password>"
$env:AIRCHAT_KEY_ALIAS="airchat"
$env:AIRCHAT_KEY_PASSWORD="<key-password>"
```

`AIRCHAT_KEY_PASSWORD` may be omitted if it is the same as `AIRCHAT_KEYSTORE_PASSWORD`.

Do not commit keystores, passwords, Play Console exports, or private signing material.

To create a signed APK locally after field testing:

```powershell
.\scripts\package-signed-release.ps1 v0.1.0
```

On macOS or Linux:

```bash
bash ./scripts/package-signed-release.sh v0.1.0
```

The signed package script runs the full preflight gate, builds `:app:assembleRelease`, copies the signed APK into `release/`, writes `SHA256SUMS.txt`, records the source commit, and records the signing certificate SHA-256 fingerprint in `RELEASE_NOTES.md`.

For GitHub Actions signed releases, add these repository secrets:

- `AIRCHAT_KEYSTORE_BASE64`: base64-encoded release keystore.
- `AIRCHAT_KEYSTORE_PASSWORD`: release keystore password.
- `AIRCHAT_KEY_ALIAS`: release key alias.
- `AIRCHAT_KEY_PASSWORD`: release key password, or the same value as the keystore password.

## Release artifact

Pushing a tag like `v0.1.0` runs `.github/workflows/release.yml`. The workflow runs the full preflight gate, uploads a signed APK when signing secrets are configured, falls back to a debug test APK otherwise, uploads `SHA256SUMS.txt` and generated notes as an Actions artifact, then creates a draft GitHub Release.

Keep the release as a draft until the real-device test matrix is complete. For early open-source testing, attach a debug APK only if the release notes clearly label it as a test build.

## Tag checklist

- CI passes on the release commit.
- Manual LAN, Wi-Fi Direct, DM, file persistence, share, and background mesh checks are recorded.
- Slash command checks are recorded.
- Field test report is completed.
- APK SHA-256 and source commit are listed in the release notes.
- Signing certificate fingerprint is listed for signed APK releases.
- Screenshots are refreshed.
- Known limitations are called out in the release notes.
- Tag format: `v0.1.0`.

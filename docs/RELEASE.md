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

## Versioning

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Move unreleased entries in `CHANGELOG.md` under a dated release heading.
3. Confirm `README.md`, `SECURITY.md`, and protocol docs still match behavior.

## Signing

For public GitHub releases, generate a release keystore outside the repository and keep it out of source control.

Recommended local environment variables:

```powershell
$env:AIRCHAT_KEYSTORE="C:\path\to\airchat-release.jks"
$env:AIRCHAT_KEY_ALIAS="airchat"
```

Do not commit keystores, passwords, Play Console exports, or private signing material.

## Release artifact

Use Android Studio's Generate Signed Bundle/APK flow or wire a private CI secret-backed release job later. For early open-source testing, attach a debug APK only if the release notes clearly label it as a test build.

## Tag checklist

- CI passes on the release commit.
- Manual LAN, Wi-Fi Direct, DM, file persistence, share, and background mesh checks are recorded.
- Slash command checks are recorded.
- APK SHA-256 and source commit are listed in the release notes.
- Screenshots are refreshed.
- Known limitations are called out in the release notes.
- Tag format: `v0.1.0`.

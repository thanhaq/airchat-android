# F-Droid Readiness

AirChat now builds through a dedicated `fdroid` distribution flavor. The flavor is intentionally plain Android/Kotlin:

- No proprietary SDKs.
- No analytics.
- No push notification service.
- No accounts or hosted backend.
- No Play Services dependency.
- Local Wi-Fi and Wi-Fi Direct transports only.

## Build

From the repository root on Windows:

```powershell
.\gradlew.bat :app:testFdroidDebugUnitTest :app:assembleFdroidDebug :app:lintFdroidDebug
```

On macOS or Linux:

```bash
bash ./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug :app:lintFdroidDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
```

## Release Packaging

The release scripts package the F-Droid flavor by default:

- Debug test artifact: `fdroidDebug`
- Signed public artifact: `fdroidRelease`

`RELEASE_MANIFEST.json` includes `distributionFlavor` and `build.gradleVariant` so reviewers can confirm which variant produced the APK.

## Permission Notes

`android.permission.INTERNET` is required for local TCP sockets over Wi-Fi and Wi-Fi Direct; AirChat does not use a hosted server for local chat. Nearby Wi-Fi/location permissions are used for Android discovery APIs. Notification and foreground-service permissions support background mesh mode.

## Metadata

Basic listing text lives in `fastlane/metadata/android/en-US/`:

- `title.txt`
- `short_description.txt`
- `full_description.txt`
- `changelogs/1.txt`

Before an F-Droid submission, complete the real-device field test matrix, publish signed release evidence, and add device screenshots.

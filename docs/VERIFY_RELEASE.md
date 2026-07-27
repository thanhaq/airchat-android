# Release Verification

Use this guide to help users verify that a GitHub release matches the public source.

## What Maintainers Should Publish

Each release should include:

- Git tag and commit SHA.
- APK SHA-256 hash.
- Build command used for the artifact.
- Device test matrix from `docs/TEST_PLAN.md`.
- Known limitations and security notes.
- Signing certificate fingerprint once release signing is configured.

## Build From Source

Clone the repository, check out the release tag, install JDK 17 and Android SDK platform 35, then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On macOS or Linux:

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Compare APK Hash

On Windows:

```powershell
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

On macOS or Linux:

```bash
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

For early debug releases, byte-for-byte APK reproducibility is not guaranteed because Android build tooling can embed local metadata. Treat matching source, passing CI, a published APK hash, and a documented field-test matrix as the minimum trust baseline until a reproducible release pipeline is added.

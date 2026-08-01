# Launch Status

This file records the public launch state so contributors can quickly see what is ready and what still needs real-device proof.

## Public Repository

- Repository: `thanhaq/airchat-android`
- Visibility: public
- Issues: enabled
- Discussions: enabled
- Main branch gate: `Android CI`
- CI coverage: unit tests, debug APK build, Android lint
- Dependency automation: GitHub Actions only; Android, Kotlin, and Gradle dependency upgrades are handled manually in compatibility batches.

## v0.1.0 Release Candidate

- Tag: `v0.1.0`
- Variant: `debug-test`
- Distribution flavor: historical unflavored debug artifact; current packaging uses `fdroid`.
- Release state: draft
- Source commit: `3a6dd55092ab4de10ce6301c68fd794815224d01`
- GitHub Actions package command: `bash ./scripts/package-debug-release.sh v0.1.0`
- APK: `airchat-v0.1.0-debug-test.apk`
- APK SHA-256: `2442a9390e895c93161048a1e2e576e10707b22d164e4784861e2247f16d1ce0`
- Manifest: `RELEASE_MANIFEST.json`
- Checksum file: `SHA256SUMS.txt`

## Verified Before Public Source Launch

- Source tree is clean before push.
- `main` has a passing GitHub Actions CI run.
- The release workflow successfully generated a draft release package from tag `v0.1.0`.
- Repository metadata, topics, Issues, and Discussions are configured.
- Failing automatic Android/Kotlin dependency PRs were closed; those upgrades require manual compatibility testing.

## Required Before Public APK Promotion

- Complete `docs/FIELD_TEST_REPORT.md` on at least two physical Android devices.
- Test LAN chat with internet disabled and Wi-Fi still connected.
- Test Wi-Fi Direct discovery and link behavior on real devices.
- Test private rooms, room verification codes, QR invite cards, encrypted DMs, file transfer, background mesh alerts, courier relay, and panic wipe.
- Attach diagnostics from both devices and compare them with `scripts/compare-diagnostics.ps1` or `scripts/compare-diagnostics.sh`.
- Configure signed-release secrets in GitHub Actions:
  - `AIRCHAT_KEYSTORE_BASE64`
  - `AIRCHAT_KEYSTORE_PASSWORD`
  - `AIRCHAT_KEY_ALIAS`
  - `AIRCHAT_KEY_PASSWORD`
- Re-tag or run the release workflow for a signed APK once field testing passes.

## Suggested Public Launch Assets

- Two-phone demo video under 60 seconds.
- Fresh screenshots of LAN chat, private room code, QR safety card, file inbox, diagnostics, and background notification.
- A completed field-test report linked from the public release notes.

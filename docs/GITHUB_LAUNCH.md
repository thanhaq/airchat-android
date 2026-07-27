# GitHub Launch Checklist

Use this checklist before making the repository public or posting the first demo.

## Repository Setup

- Repository name: `airchat-android` or `offline-mesh-chat-android`.
- Description: `Offline-first Android Wi-Fi mesh messenger with private rooms, encrypted DMs, file transfer, and no server.`
- Suggested topics: `android`, `kotlin`, `jetpack-compose`, `wifi-direct`, `mesh-network`, `offline-first`, `p2p`, `e2e-encryption`, `messaging`, `android-nsd`, `local-first`.
- Enable Issues and Discussions.
- Set the social preview to `art/social-card.svg` or an exported PNG version of it.

## First Public Commit

- Keep the project root as this folder, not the parent workspace.
- Confirm generated folders are ignored: `.gradle`, `.kotlin`, `app/build`.
- Run the preflight script:

```powershell
.\scripts\preflight.ps1
```

On macOS or Linux:

```bash
bash ./scripts/preflight.sh
```

Create an empty GitHub repository, then publish:

```powershell
.\scripts\publish-github.ps1 https://github.com/<owner>/airchat-android.git
```

To publish and trigger the draft release workflow with a tag:

```powershell
.\scripts\publish-github.ps1 https://github.com/<owner>/airchat-android.git -Tag v0.1.0
```

## First Release

- Use tag `v0.1.0`.
- Push tag `v0.1.0` and let `.github/workflows/release.yml` create the draft release package.
- Attach the debug APK only if the release title or notes clearly say it is a test build.
- Include the source commit SHA and APK SHA-256.
- Link the real-device test notes from `docs/TEST_PLAN.md`.
- Include diagnostics text from both test devices.
- Attach a completed copy of `docs/FIELD_TEST_REPORT.md`.
- Mention that the app currently has no hosted server, no accounts, and no internet requirement for local Wi-Fi chat.

## Demo Assets

- Record two physical Android phones.
- Show internet disabled while Wi-Fi remains connected.
- Show `/join`, the room switcher with unread counts, `/lock`, matching room codes, a locked private-room message unlocking after the second phone enters the passphrase, `/rotate`, encrypted DM, file receive/save/share, diagnostics recent events, and background mesh notification.
- Keep the video under 60 seconds for social posting.

## Honest Limitations

- Public channels are visible to local peers; use `/lock` before sending sensitive room traffic.
- Android Keystore identity mode requires Android 12+ and hardware backing still depends on device support; older or incompatible devices fall back to app-private software keys.
- Wi-Fi Direct behavior varies by Android vendor.
- Real disaster-use security needs more review and field testing.

# Demo Script

Use this script for a short README GIF, release video, or social post.

## Setup

- Two physical Android phones.
- One phone hotspot or a small travel router.
- Mobile data disabled on both phones after the hotspot/router connection is established.
- AirChat debug APK installed on both phones.

## Shot list

1. Show both phones connected to the same Wi-Fi with internet disabled.
2. Open AirChat on both phones and wait for `LAN: Ready`.
3. Send `hello from phone A` in `lobby`.
4. Reply from phone B with `offline reply`.
5. Show the first message changing from `sent` to `received`.
6. Type `/join field_ops`, then send a short room message.
7. Type `/who` and show the local peer notice.
8. Open the peer row and show the safety number.
9. Tap `Trust`, compare the safety number, and confirm.
10. Type `/msg <peer> encrypted hello` to send a private message.
11. Attach a small text file in DM mode.
12. Show the receiver's file strip with save and share actions.
13. Open diagnostics and show transport/key status.
14. Enable background mesh mode and show the persistent notification.

## Voiceover beats

- "No server, no account, no internet."
- "LAN mode works through a local router or hotspot."
- "Wi-Fi Direct is included for phone-to-phone links."
- "Packets are signed; direct messages and direct files are encrypted."
- "Delivery receipts come back through the same local mesh."
- "Slash commands make it feel like a tiny local IRC network."
- "Diagnostics make device testing and issue reports easier."
- "The outbox keeps messages ready for the next peer contact."

## End frame

Show the README, the green CI badge, and the command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

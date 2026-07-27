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
6. Type `/join field_ops`, then `/lock demo-passphrase` on phone A.
7. Show the room switcher with `lobby` and `field_ops`.
8. Send a room message and show phone B receiving a locked placeholder.
9. Type `/lock demo-passphrase` on phone B and show the same room code plus unlocked message.
10. Tap the private-room chip and show the QR room-code card.
11. Type `/code` on one phone, then `/rotate demo-passphrase-2` and show the code change.
12. Switch rooms and show an unread count clearing when the room chip is opened.
13. Type `/who` and show the local peer notice.
14. Open the peer row and show the safety number.
15. Tap `Trust`, show the QR safety card, compare the safety number, and confirm.
16. Type `/msg <peer> encrypted hello` to send a private message.
17. Attach a small text file in DM mode.
18. Show the receiver's file strip with save and share actions.
19. Open diagnostics and show transport/key status plus recent events.
20. Enable background mesh mode and show the persistent notification.

## Voiceover beats

- "No server, no account, no internet."
- "LAN mode works through a local router or hotspot."
- "Wi-Fi Direct is included for phone-to-phone links."
- "Packets are signed; private rooms, direct messages, and direct files are encrypted."
- "A phone that joins late can unlock buffered private-room packets after entering the passphrase."
- "QR room-code cards let people confirm they typed the same passphrase."
- "QR safety cards make peer-key checks easier without trusting a server."
- "Room chips make multiple offline channels easy to follow."
- "Delivery receipts come back through the same local mesh."
- "Slash commands make it feel like a tiny local IRC network."
- "Diagnostics make device testing and issue reports easier."
- "Recent events show what the mesh was doing without exposing chat text."
- "The outbox keeps messages ready for the next peer contact."

## End frame

Show the README, the green CI badge, and the command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

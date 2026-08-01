# Test Plan

AirChat needs both automated protocol tests and real-device radio checks. Use this plan before tagging a release or publishing demo media.

## Automated checks

Run from the project root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Expected coverage:

- Packet encoding, signing bytes, and mutable relay fields.
- Direct-message encryption/decryption.
- PacketGuard validation and rate limiting.
- Conversation filtering.
- File chunking and SHA-256 reassembly.
- Router outbox, relay, courier queue quotas, ACK receipts, courier receipts, public history sync, private-room encryption, room summaries, pinned-room and manual-room ordering, QR verification and invite payloads, background alert decisions, battery-aware relay policy, diagnostics event logging, trust, peer blocking, and key-change behavior.

## LAN field test

Devices: two physical Android phones on the same router or phone hotspot. The hotspot does not need internet.

1. Install the same debug build on both phones.
2. Grant nearby Wi-Fi or location permission.
3. Open AirChat on both phones.
4. Confirm each device shows `LAN: Ready`.
5. Send a message in `lobby` from phone A.
6. Confirm phone B receives it as verified.
7. Confirm phone A changes the sent message to received after the ACK returns.
8. Send a reply from phone B.
9. Toggle airplane mode internet off while keeping Wi-Fi connected and repeat the message check.
10. Close AirChat on phone B, send two public `lobby` messages from phone A, reopen phone B, and confirm recent public messages sync without duplicate ACK or relay noise.

## Wi-Fi Direct field test

Devices: two physical Android phones with Wi-Fi Direct support.

1. Open AirChat on both phones.
2. Wait for peers to appear.
3. Tap `Link` on one peer.
4. Confirm the peer state reaches connected.
5. Send room messages in both directions.
6. Repeat after locking and unlocking one phone.

## Courier relay test

Devices: three physical Android phones, or two phones plus one emulator/fake transport build.

1. Start phone A and phone B on the same offline Wi-Fi.
2. Temporarily make phone B unable to reach any other peer except phone A.
3. Send a verified message from phone A through phone B with relay TTL remaining.
4. Confirm phone B diagnostics shows a non-zero courier queue if relay broadcast fails.
5. Tap the `Courier` chip, set retention to 15 minutes, and confirm diagnostics reports courier relay on plus the selected retention.
6. Restart AirChat on phone B and confirm diagnostics still shows the queued courier packet.
7. Confirm phone A diagnostics logs a courier receipt from phone B for the retained packet.
8. Send enough relayed packets from one origin to confirm the per-origin quota evicts older courier entries before filling the whole queue.
9. Tap `Clear queue` and confirm diagnostics shows zero queued packets.
10. Repeat with a fresh relay packet, bring phone C onto the local mesh within the selected retention window, and confirm phone B flushes the courier queue and phone C receives the relayed packet once.
11. Disable courier relay, repeat a failed relay, and confirm phone B does not retain a courier packet.

## Trust and DM test

1. On phone A, tap `Trust` for phone B.
2. Confirm the trust dialog shows both the safety number and a QR safety card.
3. Compare the safety number or QR content with phone B before confirming.
4. Tap `DM` and send a private message.
5. Confirm the message appears only in the direct conversation.
6. Confirm the sender changes the DM from sent to received only after the recipient receives it.
7. Reinstall phone B and confirm phone A marks the peer as `Key changed` before trusting the new key.
8. Tap the block icon for phone B or type `/block <peer>`, then confirm phone A drops new packets from phone B and does not send DMs to it.
9. Type `/block` and confirm phone B is listed.
10. Type `/unblock <peer>` and confirm DMs can be sent again.

## Private room test

1. On phone A and phone B, type `/join field_ops`.
2. On phone A, type `/lock shared-field-passphrase`.
3. Confirm phone A shows a room code and passphrase-strength label.
4. Send a room message from phone A and confirm phone B shows a locked message placeholder before it has the passphrase.
5. On phone B, type `/lock shared-field-passphrase`.
6. Confirm phone B shows the same room code, unlocks the buffered message, and marks it verified.
7. Tap the private-room chip and confirm the QR invite card appears.
8. Confirm the invite card shows the room name and code, but not the passphrase.
9. Tap `Share` and confirm the shared invite text includes the room name, code, and payload, but not the passphrase.
10. Type `/code` on both phones and compare the codes or QR invite cards out of band.
11. Send a reply from phone B and confirm phone A receives it as verified.
12. Send a file while both phones are in the locked room and confirm the receiver reassembles it.
13. Type `/rotate new-shared-field-passphrase` on phone A and confirm the room code changes.
14. Type `/unlock` on phone B, send another private-room message from phone A, and confirm phone B shows it as locked until the new passphrase is entered.

## Slash command test

1. Type `/join field_ops` and confirm the room changes locally.
2. Confirm `field_ops` appears in the room switcher and `lobby` remains available.
3. Type `/lock shared-field-passphrase` and confirm the room chip shows private mode with a code.
4. Type `/code` and confirm the same code appears as a local notice.
5. Type `/rotate new-shared-field-passphrase` and confirm the code changes.
6. Type `/unlock` and confirm the room returns to public mode.
7. Type `/who` and confirm the visible peer list appears as a local notice.
8. Type `/block <peer name or id prefix>` and confirm the peer row shows blocked.
9. Type `/block` and confirm blocked peers appear as a local notice.
10. Type `/unblock <peer name or id prefix>` and confirm the peer row returns to normal.
11. Type `/msg <peer name or id prefix> command test` and confirm the peer receives it in DM mode.
12. Type `/me checks relay` and confirm the action text is sent to the active room or DM.
13. Type `/room` and confirm the composer returns to room mode.

## Room switcher test

1. On phone A, send a message in `lobby`.
2. Type `/join ops`, then `/join maps` on phone A so there are at least two non-lobby rooms to reorder.
3. Tap the star beside `ops` and confirm the room stays near the front of the strip.
4. Use the room arrow controls to move `ops` before `maps`.
5. Restart AirChat and confirm the pinned room and manual order remain visible.
6. Send a message from phone B in `lobby` while phone A is viewing `ops`.
7. Confirm phone A shows an unread count on the `lobby` room chip.
8. Tap the `lobby` room chip and confirm the unread count clears.
9. Receive a file in `ops` and confirm the `ops` room chip shows the file count.

## File transfer test

1. Send a small text file in `lobby`.
2. Confirm the receiver shows the file, size, and hash prefix.
3. Save the file to device storage and verify its contents.
4. Share the file through Android's share sheet.
5. Restart AirChat and confirm the received-file inbox still shows the file.
6. Repeat in private-room mode and confirm file metadata is not visible in raw room packet payloads during unit tests.
7. Repeat in DM mode and confirm file metadata is not visible in raw direct packet payloads during unit tests.

## Background mesh test

1. Grant notification permission on Android 13+.
2. Tap the background mesh icon.
3. Confirm the persistent notification appears.
4. Confirm the `Power` chip says `normal`, `conserve`, or `critical`.
5. Enable system battery saver and tap the `Power` chip; confirm diagnostics reports conserve mode when unplugged.
6. Leave AirChat and lock the phone for two minutes.
7. Send a message from another phone.
8. Reopen AirChat and confirm the message arrived.
9. Confirm the alert says a new AirChat message arrived without showing the message body.
10. Send a small file and confirm the alert does not show the file name.
11. Tap `Stop` in the notification and confirm discovery/relay stops when the activity is not visible.

## Diagnostics test

1. Tap the info icon in the top bar.
2. Confirm the report includes app version, protocol version, Android version, peer id, identity key mode, private-room state, room code when enabled, background mesh state, power mode, battery state, visible peers, blocked peers, visible rooms, unread rooms, visible messages, visible files, courier queue size, courier relay mode, courier retention, courier quota, transport states, and recent events.
3. Confirm the report includes pinned room count after pinning a room.
4. Confirm recent events include transport/packet/outbox/courier categories after exercising those paths.
5. Confirm recent events do not include message bodies, private-room passphrases, or file names.
6. Tap `Share` and confirm the Android share sheet opens with plain-text diagnostics.
7. Save reports from two devices and run `.\scripts\compare-diagnostics.ps1 <device-a.txt> <device-b.txt>`.
8. Confirm the compare report highlights app/protocol mismatches, transport differences, private-room code/state differences, power-mode differences, pinned-room counts, blocked-peer counts, courier queue/quota differences, and recent event categories.

## Release sign-off

- Record device model, Android version, and transport used.
- Attach diagnostics text from both phones.
- Attach the Markdown output from `scripts/compare-diagnostics.ps1`.
- Capture at least one screenshot of LAN chat, private-room locked/unlocked state, QR invite card, Wi-Fi Direct peer list, QR safety card, DM verification, and file inbox.
- Note failures with logcat output and whether battery saver was enabled.

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
- Router outbox, relay, courier queue, ACK receipt, private-room encryption, trust, and key-change behavior.

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
5. Restart AirChat on phone B and confirm diagnostics still shows the queued courier packet.
6. Bring phone C onto the local mesh within 15 minutes.
7. Confirm phone B flushes the courier queue and phone C receives the relayed packet once.

## Trust and DM test

1. On phone A, tap `Trust` for phone B.
2. Compare the safety number with phone B before confirming.
3. Tap `DM` and send a private message.
4. Confirm the message appears only in the direct conversation.
5. Confirm the sender changes the DM from sent to received only after the recipient receives it.
6. Reinstall phone B and confirm phone A marks the peer as `Key changed` before trusting the new key.

## Private room test

1. On phone A and phone B, type `/join field_ops`.
2. On phone A, type `/lock shared-field-passphrase`.
3. Confirm phone A shows a room code and passphrase-strength label.
4. Send a room message from phone A and confirm phone B shows a locked message placeholder before it has the passphrase.
5. On phone B, type `/lock shared-field-passphrase`.
6. Confirm phone B shows the same room code, unlocks the buffered message, and marks it verified.
7. Type `/code` on both phones and compare the codes out of band.
8. Send a reply from phone B and confirm phone A receives it as verified.
9. Send a file while both phones are in the locked room and confirm the receiver reassembles it.
10. Type `/rotate new-shared-field-passphrase` on phone A and confirm the room code changes.
11. Type `/unlock` on phone B, send another private-room message from phone A, and confirm phone B shows it as locked until the new passphrase is entered.

## Slash command test

1. Type `/join field_ops` and confirm the room changes locally.
2. Type `/lock shared-field-passphrase` and confirm the room shows private mode with a code.
3. Type `/code` and confirm the same code appears as a local notice.
4. Type `/rotate new-shared-field-passphrase` and confirm the code changes.
5. Type `/unlock` and confirm the room returns to public mode.
6. Type `/who` and confirm the visible peer list appears as a local notice.
7. Type `/msg <peer name or id prefix> command test` and confirm the peer receives it in DM mode.
8. Type `/me checks relay` and confirm the action text is sent to the active room or DM.
9. Type `/room` and confirm the composer returns to room mode.

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
4. Leave AirChat and lock the phone for two minutes.
5. Send a message from another phone.
6. Reopen AirChat and confirm the message arrived.
7. Tap `Stop` in the notification and confirm discovery/relay stops when the activity is not visible.

## Diagnostics test

1. Tap the info icon in the top bar.
2. Confirm the report includes app version, protocol version, Android version, peer id, identity key mode, private-room state, room code when enabled, visible peers, visible messages, visible files, courier queue size, and transport states.
3. Tap `Share` and confirm the Android share sheet opens with plain-text diagnostics.

## Release sign-off

- Record device model, Android version, and transport used.
- Attach diagnostics text from both phones.
- Capture at least one screenshot of LAN chat, private-room locked/unlocked state, Wi-Fi Direct peer list, DM verification, and file inbox.
- Note failures with logcat output and whether battery saver was enabled.

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
- Router outbox, relay, courier queue, ACK receipt, trust, and key-change behavior.

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
5. Bring phone C onto the local mesh within 15 minutes.
6. Confirm phone B flushes the courier queue and phone C receives the relayed packet once.

## Trust and DM test

1. On phone A, tap `Trust` for phone B.
2. Compare the safety number with phone B before confirming.
3. Tap `DM` and send a private message.
4. Confirm the message appears only in the direct conversation.
5. Confirm the sender changes the DM from sent to received only after the recipient receives it.
6. Reinstall phone B and confirm phone A marks the peer as `Key changed` before trusting the new key.

## Slash command test

1. Type `/join field_ops` and confirm the room changes locally.
2. Type `/who` and confirm the visible peer list appears as a local notice.
3. Type `/msg <peer name or id prefix> command test` and confirm the peer receives it in DM mode.
4. Type `/me checks relay` and confirm the action text is sent to the active room or DM.
5. Type `/room` and confirm the composer returns to room mode.

## File transfer test

1. Send a small text file in `lobby`.
2. Confirm the receiver shows the file, size, and hash prefix.
3. Save the file to device storage and verify its contents.
4. Share the file through Android's share sheet.
5. Restart AirChat and confirm the received-file inbox still shows the file.
6. Repeat in DM mode and confirm file metadata is not visible in raw direct packet payloads during unit tests.

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
2. Confirm the report includes app version, protocol version, Android version, peer id, identity key mode, visible peers, visible messages, visible files, courier queue size, and transport states.
3. Tap `Share` and confirm the Android share sheet opens with plain-text diagnostics.

## Release sign-off

- Record device model, Android version, and transport used.
- Attach diagnostics text from both phones.
- Capture at least one screenshot of LAN chat, Wi-Fi Direct peer list, DM verification, and file inbox.
- Note failures with logcat output and whether battery saver was enabled.

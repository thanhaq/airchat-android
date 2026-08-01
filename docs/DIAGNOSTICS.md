# Diagnostics Workflow

AirChat diagnostics are designed for field tests where two or three physical Android devices are on the same offline Wi-Fi or Wi-Fi Direct mesh. The report captures app version, protocol version, Android version, peer identity, private-room state, background mesh state, power mode, battery state, transport states, room counts, pinned-room count, blocked-peer count, courier queue size, courier relay mode, courier retention, courier per-origin quota, and a short recent event timeline.

## Capture Reports

1. Install the same APK on every test device.
2. Reproduce the issue or demo flow.
3. Tap the info icon in AirChat.
4. Tap `Share` and save the plain-text diagnostics from each device.
5. Name the files by role, for example `device-a.txt`, `device-b.txt`, and `courier-c.txt`.

## Compare Two Devices

From the repository root on Windows:

```powershell
.\scripts\compare-diagnostics.ps1 .\field-tests\device-a.txt .\field-tests\device-b.txt -FirstLabel PixelA -SecondLabel PixelB -OutFile .\field-tests\diagnostics-compare.md
```

If local execution policy blocks `.ps1` files, run the same script through PowerShell explicitly:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\compare-diagnostics.ps1 .\field-tests\device-a.txt .\field-tests\device-b.txt -FirstLabel PixelA -SecondLabel PixelB -OutFile .\field-tests\diagnostics-compare.md
```

On macOS or Linux with PowerShell installed:

```bash
bash ./scripts/compare-diagnostics.sh ./field-tests/device-a.txt ./field-tests/device-b.txt -FirstLabel PixelA -SecondLabel PixelB -OutFile ./field-tests/diagnostics-compare.md
```

The generated Markdown report includes:

- App, protocol, Android, identity-key, conversation, room, unread, blocked-peer, file, power, courier counters, courier quota, and courier policy side by side.
- Pinned-room counts for checking whether room preferences persisted.
- Transport state differences for LAN and Wi-Fi Direct.
- Recent diagnostic event categories from both devices.
- The last recent events from each device.
- Suggested checks for common mismatches.

## Reading The Output

- `App` or `Protocol` differs: install the same APK on every device.
- `Transport` differs: check Wi-Fi, Android nearby/location permissions, hotspot/router client isolation, Wi-Fi Direct support, and battery saver.
- `Power mode` differs: align charging state, battery saver, and low-battery state before comparing relay behavior.
- `Private room` differs: switch both devices to the same room, enter the same passphrase, then compare `/code`.
- `Conversation` differs: switch both devices to the same room before debugging room delivery.
- `Courier queue` is non-zero: reconnect peers to the same local mesh before the courier window expires, or clear the queue manually from the `Courier` chip before a sensitive test.
- Recent events are empty: reproduce the issue again and copy diagnostics immediately afterward.

## Privacy Notes

Diagnostics intentionally avoid message bodies, private-room passphrases, and file names. They can still include device model, Android version, peer nickname, peer id, transport state, and timing metadata. Redact those fields before posting public issues if they identify a person, device, or location.

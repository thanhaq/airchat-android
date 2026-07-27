# Field Test Report Template

Copy this file for each release candidate and fill it in before making a GitHub Release public.

## Build

- Commit:
- APK SHA-256:
- Version:
- Build command:

## Devices

| Role | Device | Android version | Battery saver | Notes |
| --- | --- | --- | --- | --- |
| A |  |  |  |  |
| B |  |  |  |  |
| C, optional courier |  |  |  |  |

## Network Setup

- Transport tested: LAN / Wi-Fi Direct / both
- Router or hotspot model:
- Internet disabled: yes / no
- Location services enabled where required by Android: yes / no

## Results

| Check | Result | Notes |
| --- | --- | --- |
| LAN discovery |  |  |
| LAN message both directions |  |  |
| Room switcher and unread count |  |  |
| Wi-Fi Direct discovery |  |  |
| Wi-Fi Direct link |  |  |
| Private room lock/unlock |  |  |
| Private room code compare |  |  |
| Private room encrypted text |  |  |
| Private room key rotation |  |  |
| Safety number compare |  |  |
| Encrypted DM |  |  |
| ACK changes sender state to received |  |  |
| Public file transfer |  |  |
| Private room file transfer |  |  |
| Encrypted DM file transfer |  |  |
| Received-file persistence after restart |  |  |
| Courier queue persists after restart |  |  |
| Background mesh notification |  |  |
| Diagnostics share sheet |  |  |
| Diagnostics recent events |  |  |
| Panic wipe |  |  |

## Diagnostics

Paste diagnostics from each device below.

### Device A

```text
```

### Device B

```text
```

### Device C

```text
```

## Screenshots And Media

- LAN chat:
- Wi-Fi Direct peer list:
- Safety-number trust dialog:
- File inbox:
- Diagnostics:
- Background notification:

## Failures

Include logcat snippets, timestamps, Android permission state, and whether battery saver was enabled.

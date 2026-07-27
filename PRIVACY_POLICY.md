# Privacy Policy

AirChat is designed for local, offline communication. It has no account system, no analytics SDK, no advertising SDK, and no hosted AirChat server.

## Data AirChat Stores

AirChat stores the minimum local data needed for mesh messaging:

- A per-install identity key and peer id.
- Local message history and outbox entries.
- Short-lived courier relay packets for verified mesh traffic awaiting the next peer contact.
- Courier relay preference and retention setting.
- Trusted peer public keys.
- Blocked peer ids.
- Known rooms and pinned room preferences.
- Received-file inbox metadata and encrypted file blobs.
- App preferences needed for foreground background mesh mode.
- A short in-memory diagnostics event log for field testing.

Message history, outbox entries, courier relay packets, trust records, blocked peer ids, room preferences, and received-file inbox entries are encrypted locally with Android Keystore AES-GCM keys. On Android 12+ new identity keys prefer Android Keystore and may be hardware-backed depending on the device. On Android 8-11, or when signing plus ECDH are not both available through Keystore, AirChat falls back to app-private software keys excluded from Android backup.

## Network Behavior

AirChat communicates with nearby peers through local Wi-Fi LAN discovery and Wi-Fi Direct. It does not send messages to an AirChat cloud service.

Public room messages and public room file metadata are visible to nearby peers participating in the same local mesh. Direct messages and direct file packets are encrypted for the recipient public key, but nearby devices can still observe radio/network metadata such as timing, packet sizes, and local peer identifiers. Courier relay can be disabled or cleared from the app when users do not want verified transit packets retained for later peer contact.

Peer blocking is local to the device. AirChat can drop content and stop relaying packets from blocked peer ids, but it cannot hide that nearby radios exist or force another device to stop broadcasting.

Diagnostics reports are created only when the user opens and shares them. The recent event log is intended for debugging and includes metadata such as transport state changes, packet categories, queue events, and guard rejections. It does not intentionally include message bodies, private-room passphrases, or file names.

## Android Permissions

AirChat requests Wi-Fi, nearby-device, notification, and legacy location permissions when Android requires them for discovery, Wi-Fi Direct, or foreground background operation. AirChat does not use location permission to collect location history.

## User Controls

Users can block or unblock peers from the peer row or slash commands. Users can remove local data with panic wipe. Android system uninstall also removes app-private data.

## Project Status

AirChat is a pre-1.0 open-source prototype. Review [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) and [SECURITY.md](SECURITY.md) before using it in sensitive situations.

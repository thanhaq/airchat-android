# Privacy Policy

AirChat is designed for local, offline communication. It has no account system, no analytics SDK, no advertising SDK, and no hosted AirChat server.

## Data AirChat Stores

AirChat stores the minimum local data needed for mesh messaging:

- A per-install identity key and peer id.
- Local message history and outbox entries.
- Trusted peer public keys.
- Received-file inbox metadata and encrypted file blobs.
- App preferences needed for foreground background mesh mode.

Message history, outbox entries, trust records, and received-file inbox entries are encrypted locally with Android Keystore AES-GCM keys. On Android 12+ new identity keys prefer Android Keystore and may be hardware-backed depending on the device. On Android 8-11, or when signing plus ECDH are not both available through Keystore, AirChat falls back to app-private software keys excluded from Android backup.

## Network Behavior

AirChat communicates with nearby peers through local Wi-Fi LAN discovery and Wi-Fi Direct. It does not send messages to an AirChat cloud service.

Public room messages and public room file metadata are visible to nearby peers participating in the same local mesh. Direct messages and direct file packets are encrypted for the recipient public key, but nearby devices can still observe radio/network metadata such as timing, packet sizes, and local peer identifiers.

## Android Permissions

AirChat requests Wi-Fi, nearby-device, notification, and legacy location permissions when Android requires them for discovery, Wi-Fi Direct, or foreground background operation. AirChat does not use location permission to collect location history.

## User Controls

Users can remove local data with panic wipe. Android system uninstall also removes app-private data.

## Project Status

AirChat is a pre-1.0 open-source prototype. Review [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) and [SECURITY.md](SECURITY.md) before using it in sensitive situations.

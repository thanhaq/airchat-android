# Security Policy

AirChat is not a finished secure messenger yet. Treat the current app as an offline mesh prototype with authenticated public-channel packets and encrypted direct messages/files.

## Supported versions

Only the `main` branch is supported during the pre-1.0 phase.

## Reporting a vulnerability

Please open a private advisory or contact the maintainers before publishing exploit details. Include:

- Affected commit or release.
- Reproduction steps.
- Expected impact.
- Suggested mitigation if known.

## Current known limitations

- Public channel messages are visible to local peers.
- On Android 12+ new identity keys prefer Android Keystore and may be hardware-backed depending on the device; Android 8-11 and incompatible devices fall back to app-private software keys excluded from backup.
- Wi-Fi Direct metadata can expose device names and MAC-like identifiers provided by Android APIs.
- No formal Noise handshake is implemented yet.

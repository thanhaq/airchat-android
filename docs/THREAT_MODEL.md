# Threat Model

AirChat is built for local communication when internet access is unavailable, censored, overloaded, or intentionally avoided. It is not a complete anonymity network.

## Goals

- Work on a local Wi-Fi network without internet.
- Work over Wi-Fi Direct where Android hardware and permissions allow it.
- Authenticate packet origins.
- Encrypt direct message bodies.
- Keep relays simple and untrusted.

## Non-goals for the current version

- Hiding that two devices are nearby.
- Hiding timing, packet size, or relay path metadata.
- Protecting public channel messages from local peers.
- Guaranteeing hardware-backed identity keys on every Android device.
- Background delivery under every OEM battery policy.

## Current protections

- Public packets are signed by the origin.
- On Android 12+ new identity keys prefer Android Keystore and are non-exportable when compatible signing and ECDH are available.
- Direct message bodies use ephemeral ECDH and AES-GCM.
- Direct-file manifests and chunks are wrapped inside encrypted direct packets.
- Identity material, messages, outbox entries, trust records, received-file metadata, and encrypted received-file blobs are excluded from Android backup and device transfer.
- Message history, outbox JSON, trust records, received-file metadata, and received-file blobs are encrypted with Android Keystore AES-GCM before persistence.
- Panic wipe clears message history, outbox, peer cache, identity data on disk, and rotates the in-memory identity for the running process.
- Relay mutation of TTL/path does not affect origin signatures.
- Message ids are deduplicated to reduce loops and replay noise.
- PacketGuard limits oversized payloads, invalid TTL/path metadata, clock-skew abuse, and noisy origins.
- Safety-number fingerprints give users a compact way to compare peer keys out of band.
- Trusted peer records are encrypted at rest; if a trusted peer id presents a different public key, direct sends are blocked until the user trusts the new key.

## Known risks

- Device names and Wi-Fi Direct metadata may identify users.
- Public channels are readable by nearby peers.
- Public-channel file contents are readable by nearby peers while in transit; direct-file contents are encrypted per packet.
- Android 8-11 devices use app-private software identity fallback because Keystore ECDH purpose support starts on Android 12.
- Some Android 12+ devices may still use Android Keystore software backing or app-private software identity fallback when hardware-backed signing plus ECDH are unavailable.
- Trust-on-first-use does not prove identity unless users compare safety numbers out of band.
- A malicious local peer can still attempt link-level flooding; current app-level rate limits are basic.
- Android vendor differences can affect Wi-Fi Direct reliability.

## Planned hardening

- Noise-style interactive session handshake for direct messages.
- Per-peer replay windows.
- Transport quotas and stronger adaptive rate limiting.
- QR safety number verification.
- Per-file retention controls and export auditing.

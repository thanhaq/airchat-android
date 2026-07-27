# Threat Model

AirChat is built for local communication when internet access is unavailable, censored, overloaded, or intentionally avoided. It is not a complete anonymity network.

## Goals

- Work on a local Wi-Fi network without internet.
- Work over Wi-Fi Direct where Android hardware and permissions allow it.
- Authenticate packet origins.
- Encrypt direct message bodies.
- Encrypt optional passphrase-locked room traffic.
- Keep relays simple and untrusted.
- Support bounded opportunistic store-and-forward for verified relay packets.

## Non-goals for the current version

- Hiding that two devices are nearby.
- Hiding timing, packet size, or relay path metadata.
- Protecting public channel messages from local peers unless users explicitly lock that room.
- Recovering forgotten private-room passphrases.
- Guaranteeing hardware-backed identity keys on every Android device.
- Background delivery under every OEM battery policy.

## Current protections

- Public packets are signed by the origin.
- On Android 12+ new identity keys prefer Android Keystore and are non-exportable when compatible signing and ECDH are available.
- Direct message bodies use ephemeral ECDH and AES-GCM.
- Direct-file manifests and chunks are wrapped inside encrypted direct packets.
- Private-room text, file manifests, and file chunks are encrypted with an in-memory AES-GCM key derived from the room passphrase and channel name.
- Private-room verification codes let users compare room keys without revealing the passphrase itself.
- Locked-room packets received before a key is entered are buffered only in memory for the running process.
- Identity material, messages, outbox entries, courier relay packets, trust records, room preferences, received-file metadata, and encrypted received-file blobs are excluded from Android backup and device transfer.
- Message history, outbox JSON, courier relay packets, trust records, received-file metadata, and received-file blobs are encrypted with Android Keystore AES-GCM before persistence.
- Known-room and pinned-room preferences are encrypted with Android Keystore AES-GCM before persistence.
- Panic wipe clears message history, outbox, peer cache, identity data on disk, and rotates the in-memory identity for the running process.
- Relay mutation of TTL/path does not affect origin signatures.
- Message ids are deduplicated to reduce loops and replay noise.
- Courier relay stores verified transit packets for a short bounded window when no transport currently accepts the relay.
- PacketGuard limits oversized payloads, invalid TTL/path metadata, clock-skew abuse, and noisy origins.
- Safety-number fingerprints give users a compact way to compare peer keys out of band.
- QR safety cards and QR room-code cards make out-of-band comparison easier without transmitting passphrases or private keys.
- Trusted peer records are encrypted at rest; if a trusted peer id presents a different public key, direct sends are blocked until the user trusts the new key.
- Diagnostics recent events are kept in memory and avoid message bodies, private-room passphrases, and file names.

## Known risks

- Device names and Wi-Fi Direct metadata may identify users.
- Public channels are readable by nearby peers.
- Public-channel file contents are readable by nearby peers while in transit; private-room and direct-file contents are encrypted per packet.
- Private-room passphrases need enough entropy to resist offline guessing from captured packets.
- Passphrase-strength labels are advisory and cannot prove that a phrase is safe against targeted guessing.
- Room verification codes are not secrets; they are short key fingerprints for human comparison.
- QR verification cards are not secrets; they encode fingerprints for comparison, not identity proof by themselves.
- Private-room passphrases are memory-only; users must re-enter them after process restart and share them out of band.
- Shared diagnostics can still reveal timing, transport state, packet categories, room names, peer id prefixes, and queue behavior.
- Pinned and known room preferences can reveal local workflow patterns if the device is unlocked.
- Encrypted courier relay can extend packet lifetime within the local mesh for up to the configured queue window.
- Android 8-11 devices use app-private software identity fallback because Keystore ECDH purpose support starts on Android 12.
- Some Android 12+ devices may still use Android Keystore software backing or app-private software identity fallback when hardware-backed signing plus ECDH are unavailable.
- Trust-on-first-use does not prove identity unless users compare safety numbers out of band.
- A malicious local peer can still attempt link-level flooding; current app-level rate limits are basic.
- Android vendor differences can affect Wi-Fi Direct reliability.

## Planned hardening

- Noise-style interactive session handshake for direct messages.
- Courier per-peer quotas, relay receipts, and user controls.
- Per-peer replay windows.
- Transport quotas and stronger adaptive rate limiting.
- Camera scan-to-verify for QR safety cards.
- Per-file retention controls and export auditing.

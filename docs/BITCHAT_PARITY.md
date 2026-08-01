# BitChat Parity And Differentiation

AirChat started from the same broad product idea as BitChat: local-first messaging that keeps working when accounts, phone numbers, central servers, or the wider internet are not available. The Android implementation deliberately chooses Wi-Fi LAN and Wi-Fi Direct as its first transports instead of Bluetooth LE.

This comparison is based on BitChat's public README and protocol whitepaper as reviewed on 2026-08-01:

- BitChat README: <https://github.com/permissionlesstech/bitchat/blob/main/README.md>
- BitChat whitepaper: <https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md>

## Product Position

BitChat is a mature cross-platform app with Bluetooth mesh for offline communication and Nostr for internet-based global reach. AirChat is an Android-first Wi-Fi mesh messenger focused on higher-throughput local rooms, private-room workflows, file transfer, diagnostics, and reproducible open-source release evidence.

AirChat should not pretend to be a drop-in BitChat clone. The stronger public story is:

- BitChat proves the demand for accountless local mesh chat.
- AirChat explores the Android Wi-Fi version of that idea.
- Wi-Fi gives a practical path for group chat, files, hotspot tests, and local collaboration.
- The repo is structured for external review: protocol docs, threat model, test plan, diagnostics, CI, release manifests, and field-test templates.

## Feature Matrix

| Area | BitChat | AirChat Android |
| --- | --- | --- |
| Primary offline transport | Bluetooth LE mesh. | Wi-Fi LAN via NSD plus Wi-Fi Direct discovery/link flow. |
| Internet fallback | Nostr relay path for global reach and location channels. | None by design in the current release; local Wi-Fi and Wi-Fi Direct stay serverless. |
| Accounts | No accounts, phone numbers, or central servers for identity. | No accounts, phone numbers, or central servers. |
| Relay | BLE multi-hop relay with TTL and controlled flooding. | TTL-limited relay over shared packet format for LAN and Wi-Fi Direct transports. |
| Store and forward | Sender outbox, couriers, public history sync, and Nostr mailboxes. | Encrypted local outbox, bounded encrypted courier queue with per-origin quotas and relay receipts, plus recent public-room history sync. |
| Direct messages | Noise sessions on mesh; app-specific private envelopes over Nostr. | Ephemeral ECDH over P-256 plus AES-GCM direct envelopes. Noise-style sessions are planned. |
| Public rooms | Mesh room plus geohash location channels over Nostr. | Named local rooms over Wi-Fi mesh, with unread counts, pinned rooms, and manual ordering. |
| Private rooms | Private messages are the main encrypted path. | Passphrase-locked group rooms with verification codes, QR invite cards, encrypted text, and encrypted file packets. |
| Files/media | Fragmented media in the mesh, with caps and user acceptance. | Public, private-room, and DM file transfer up to 10 MB, chunking, SHA-256 verification, encrypted inbox persistence, save/share actions. |
| Identity verification | QR verification binds nickname to fingerprint. | Safety-number compare, QR safety cards, trust records, and key-change protection. |
| Panic wipe | Clears local identity and retained delivery state. | Clears local history, outbox, peer cache, identity data on disk, and rotates in-memory identity. |
| Power behavior | Adaptive battery optimizations. | Visible normal/conserve/critical power policy, relay TTL clamp, courier flush spacing, and critical-battery courier storage pause. |
| Diagnostics | Protocol whitepaper and build verification guidance. | In-app diagnostics report, privacy-preserving recent event log, structured JSON export, compare scripts, release manifest, launch status, and field-test template. |
| Public release state | App Store/Play Store listed by upstream README. | Public source and draft v0.1.0 debug-test release candidate; signed public APK promotion waits for field testing. |

## Where AirChat Is Already Competitive

- Native Android codebase with Jetpack Compose UI and Android-specific radio notes.
- Wi-Fi LAN mode works with a router, offline hotspot, or local access point, which is easier for many Android users to test than BLE mesh internals.
- Wi-Fi Direct support creates a phone-to-phone path without an access point.
- Private-room encryption is built around group workflows, not only one-to-one DMs.
- File transfer is a first-class feature across public rooms, private rooms, and DMs.
- Recent public-room history sync helps reconnecting peers catch up on signed public chat without replaying private-room, DM, or file payloads.
- Courier relay now has per-origin fairness quotas and signed receipts surfaced as a `relayed` state so senders can distinguish relay retention from final delivery.
- Diagnostics are designed for GitHub issues and field testing, with report comparison scripts for asymmetric discovery or delivery bugs.
- The repository has launch artifacts that make trust review easier: threat model, protocol docs, release guide, verification guide, CI, generated manifests, checksums, and a field-test report template.

## Where BitChat Is Ahead

- Bluetooth LE mesh is more power-friendly and infrastructure-free at short range.
- Nostr fallback gives global reach when the internet exists.
- Noise session handshakes provide a stronger direct-message cryptographic story than AirChat's current one-shot ECDH envelopes.
- BitChat's public history sync is more mature and broader than AirChat's current recent public-chat sync.
- App Store and Play Store distribution are already available upstream.

## AirChat Parity Roadmap

The next high-impact parity targets are:

- Add Noise-style interactive sessions for online DMs.
- Expand public-room history sync with compact set reconciliation and longer retention controls.
- Add richer courier expiry tuning, receipt details, and per-peer quota controls.
- Add Wi-Fi Aware for supported Android devices.
- Explore optional BLE low-bandwidth discovery while keeping Wi-Fi as the high-throughput data path.
- Add signed public APK release automation after field testing.
- Add F-Droid-ready build flavor and reproducible release verification.

## Messaging For Contributors

Use this wording when presenting AirChat publicly:

> AirChat is an Android Wi-Fi take on accountless mesh chat: no server, no phone number, encrypted DMs, passphrase private rooms, file transfer, courier relay, diagnostics, and release verification. It is not BitChat-compatible, but it is chasing the same resilience goal through Android Wi-Fi primitives.

Avoid claiming:

- Production disaster-readiness before physical field tests pass.
- Forward secrecy for current AirChat DMs before a Noise-style session handshake lands.
- Anonymous presence; both Wi-Fi and mesh metadata can reveal nearby device activity.
- Compatibility with BitChat's protocol or Nostr private envelopes.

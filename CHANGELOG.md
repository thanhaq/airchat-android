# Changelog

## 0.1.0 - Unreleased

- Scaffolded Android Kotlin + Compose app.
- Added LAN transport with Android NSD advertisement, discovery, and JSON-line TCP packets.
- Added Wi-Fi Direct discovery, group connection hooks, and TCP socket exchange.
- Added signed mesh packet protocol with TTL relay and deduplication.
- Added signed ACK delivery receipts for public and direct text messages.
- Added encrypted direct-message payloads using ephemeral ECDH and AES-GCM.
- Added Android Keystore-backed identity generation with signing/ECDH self-test and app-private software fallback.
- Added encrypted local message/outbox persistence backed by Android Keystore AES-GCM.
- Added panic wipe for local history, outbox, peer cache, and identity data on disk.
- Added panic-wipe in-memory identity rotation for the running app process.
- Added PacketGuard validation for TTL, payload size, route length, clock skew, and per-origin rate limiting.
- Added conversation filtering and safety-number fingerprints in peer rows.
- Added encrypted trust store, peer trust confirmation, and key-change blocking for direct sends.
- Added public-channel and encrypted direct file transfer with Android picker, manifest/chunk packets, persistent encrypted inbox, and SHA-256 reassembly checks.
- Added encrypted local persistence for received-file inbox metadata and blobs.
- Added save-to-device UI for received files.
- Added Android share sheet support for received files through a non-exported FileProvider.
- Added Android backup/device-transfer exclusions for encrypted received-file metadata and blobs.
- Added IRC-style slash commands: `/join`, `/room`, `/msg`, `/dm`, `/me`, `/who`, and `/help`.
- Added in-app diagnostics report with share action for field tests and GitHub issues.
- Added foreground background mesh service with notification controls for longer-lived local discovery and relay.
- Added protocol, threat-model, and Android Wi-Fi documentation.
- Added real-device test plan for LAN, Wi-Fi Direct, trust, file transfer, and background mesh checks.
- Added release guide with signing, versioning, and tag checklist.
- Added privacy policy and source/build verification guide.
- Added Gradle wrapper scripts pinned to Gradle 8.10.2.
- Added `.gitattributes` for wrapper script line endings and binary artifacts.
- Added Dependabot configuration for Gradle and GitHub Actions updates.
- Added GitHub Actions CI, issue templates, and contribution docs.
- Added Android lint to the documented and CI verification gate.
- Added README badges and app-screen SVG artwork for GitHub presentation.
- Added demo script for README GIFs, release videos, and social posts.

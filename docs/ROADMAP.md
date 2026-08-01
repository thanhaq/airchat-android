# Roadmap

AirChat is designed to evolve from a local Wi-Fi messenger into a resilient Android mesh stack.

## Phase 1: Reliable local chat

- Stabilize LAN NSD discovery across common routers and hotspots.
- Add logcat diagnostics for discovery and socket failures.
- Add emulator-friendly fake transport for UI tests.
- Expand public-room history sync with compact reconciliation for larger rooms.

## Phase 2: Private messaging

- Add camera scan-to-verify for QR safety cards.
- Add signed trust import/merge UI for device migration.
- Add Noise-style interactive sessions for online DMs.
- Track BitChat parity gaps in [BITCHAT_PARITY.md](BITCHAT_PARITY.md).

## Phase 3: Store and forward

- Add richer courier expiry tuning.
- Add courier per-peer quota controls and richer receipt details.
- Add per-peer replay windows and adaptive transport quotas.

## Phase 4: More transports

- Add Wi-Fi Aware discovery and data path support.
- Add optional BLE low-bandwidth discovery.
- Add transport scoring by latency, reliability, and battery cost.

## Phase 5: Release polish

- Add F-Droid screenshots and reproducible-build notes.
- Add screenshots and demo videos.
- Add reproducible release verification workflow.
- Add a visual diagnostics diff viewer for shared field-test reports.
- Add localization.

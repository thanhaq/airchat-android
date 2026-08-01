# Roadmap

AirChat is designed to evolve from a local Wi-Fi messenger into a resilient Android mesh stack.

## Phase 1: Reliable local chat

- Stabilize LAN NSD discovery across common routers and hotspots.
- Add logcat diagnostics for discovery and socket failures.
- Add emulator-friendly fake transport for UI tests.
- Expand public-room history sync with compact reconciliation for larger rooms.

## Phase 2: Private messaging

- Add camera scan-to-verify for QR safety cards.
- Add signed trust export/import for device migration.
- Add Noise-style interactive sessions for online DMs.
- Track BitChat parity gaps in [BITCHAT_PARITY.md](BITCHAT_PARITY.md).

## Phase 3: Store and forward

- Add relay receipts and expiry tuning.
- Add courier per-peer quota controls and richer receipt surfacing.
- Add per-peer replay windows and adaptive transport quotas.

## Phase 4: More transports

- Add Wi-Fi Aware discovery and data path support.
- Add optional BLE low-bandwidth discovery.
- Add transport scoring by latency, reliability, and battery cost.

## Phase 5: Release polish

- Add F-Droid build flavor.
- Add screenshots and demo videos.
- Add inline preview for common received file types.
- Add reproducible release verification workflow.
- Add structured diagnostics import to compare two device reports side by side.
- Add localization.

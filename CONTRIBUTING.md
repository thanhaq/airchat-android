# Contributing

AirChat is early, but it is designed to grow in public. Small, well-tested pull requests are preferred.

## Development priorities

1. Keep the transport boundary clean. New radios or routing strategies should implement `MeshTransport`.
2. Keep protocol changes backward-compatible where practical.
3. Add tests for packet formats, routing behavior, and crypto helpers.
4. Avoid server assumptions. The app should keep working on a local network with no internet.

## Pull request checklist

- Explain the user-visible behavior change.
- Add or update tests for protocol, routing, or transport logic.
- Update `README.md` when setup or behavior changes.
- Call out privacy or security implications.

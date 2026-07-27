# AirChat Protocol

AirChat uses one JSON line per packet over TCP sockets. The same packet format is shared by LAN and Wi-Fi Direct transports so the router can relay without understanding radio-specific details.

## MeshPacket

```json
{
  "id": "uuid",
  "type": "Chat",
  "originId": "stable-peer-id",
  "originName": "peer-abcd",
  "originPublicKey": "base64url-x509-key",
  "createdAt": 1785160000000,
  "ttl": 7,
  "channel": "lobby",
  "payload": "hello",
  "signature": "base64url-ecdsa",
  "path": []
}
```

## Packet types

- `Hello`: identity announcement.
- `Chat`: public channel message.
- `Direct`: encrypted direct message payload.
- `FileManifest`: file metadata, chunk count, and SHA-256 hash.
- `FileChunk`: base64url-encoded chunk data.
- `Ack`: signed delivery receipt for a message packet.

## Signing

Packets are signed with ECDSA over the immutable packet body. Relay metadata is deliberately excluded:

- `signature` is always cleared before signing.
- `ttl` is normalized to `0`.
- `path` is normalized to `[]`.

That lets relays decrement TTL and append their peer id without breaking origin authentication.

## Direct payload

`Direct` packets store an encrypted JSON payload:

```json
{
  "recipientId": "stable-peer-id",
  "ephemeralPublicKey": "base64url-x509-key",
  "nonce": "base64url-12-byte-nonce",
  "ciphertext": "base64url-aes-gcm"
}
```

Associated data is:

```text
airchat-direct-v1:{packetId}:{recipientId}
```

Relays can forward direct packets but cannot read the body.

New direct payloads decrypt to a `DirectEnvelope`. Older clients that sent raw text are still accepted as text.

```json
{
  "kind": "Text",
  "body": "hello"
}
```

`kind` may be `Text`, `FileManifest`, or `FileChunk`. File envelopes put the serialized manifest or chunk JSON in `body`.

## Delivery receipts

When a peer accepts and verifies a public `Chat` packet or decrypts and verifies a direct text packet, it sends a signed `Ack` packet back toward the origin. ACK packets can also be relayed through the mesh.

```json
{
  "packetId": "uuid",
  "receivedAt": 1785160001000,
  "status": "Verified"
}
```

Senders mark a local message as `received` after a valid ACK for that packet id. Direct-message ACKs are accepted only from the direct recipient named in the local message channel.

## File transfers

File transfer is represented by a manifest packet and a sequence of chunk packets. Chunks are capped at 32 KiB before base64url encoding, and the current file picker caps selected files at 10 MB. Receivers reassemble only when every chunk index is present and the final SHA-256 hash matches the manifest.

Public-channel files use `FileManifest` and `FileChunk` packets. Direct files use `Direct` packets whose decrypted envelope kind is `FileManifest` or `FileChunk`. Plain `FileManifest` and `FileChunk` packets in `dm:` channels are ignored.

```json
{
  "transferId": "uuid",
  "fileName": "field-notes.txt",
  "mimeType": "text/plain",
  "totalBytes": 4096,
  "sha256": "hex",
  "chunkSize": 32768,
  "totalChunks": 1
}
```

## Routing

- Default TTL is 7.
- Packet ids are deduplicated with an LRU window.
- Relays append their local peer id to `path`.
- A device never displays packets it originated.
- A device never sends an ACK for an ACK.

## Guard rails

Inbound packets pass through `PacketGuard` before display or relay:

- TTL must be between 0 and 7.
- Route path must be no longer than 12 peers.
- Payload must stay below the configured size limit.
- Timestamps must not be stale or too far in the future.
- A single origin is rate-limited within a short rolling window.

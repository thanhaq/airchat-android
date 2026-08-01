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
- `RoomEncrypted`: passphrase-locked room envelope.
- `Direct`: encrypted direct message payload.
- `FileManifest`: file metadata, chunk count, and SHA-256 hash.
- `FileChunk`: base64url-encoded chunk data.
- `Ack`: signed delivery receipt for a message packet.
- `HistoryRequest`: signed request for recent public chat packets the requester has not seen.
- `HistoryResponse`: signed bundle of recent public `Chat` packets for a `HistoryRequest`.
- `CourierReceipt`: signed notice that a relay retained a packet in its bounded courier queue.

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

## Private rooms

`RoomEncrypted` packets store an encrypted JSON payload for a room whose participants have entered the same passphrase with `/lock`.

```json
{
  "version": 1,
  "nonce": "base64url-12-byte-nonce",
  "ciphertext": "base64url-aes-gcm"
}
```

The room key is derived in memory from the passphrase and channel name with PBKDF2-HMAC-SHA256. The passphrase is not written to disk. Each encrypted packet uses a fresh AES-GCM nonce and this associated data:

```text
airchat-room-v1:{channel}:{packetId}
```

The decrypted room payload is a `RoomEnvelope`:

```json
{
  "kind": "Text",
  "body": "hello"
}
```

`kind` may be `Text`, `FileManifest`, or `FileChunk`. Private-room files encrypt the same file manifest and chunk records used by public files.

The room verification code shown by the app is not transmitted in packets. It is a short SHA-256 fingerprint of the derived room key and channel name, intended only for out-of-band comparison between participants.

Private-room invite cards use a QR/text payload that carries room metadata without carrying the passphrase:

```text
AIRCHAT-ROOM-INVITE:1:{channel}:{channelDigest}:{verificationCode}
```

The channel name is included so another device can join the same room. `channelDigest` is a 12-hex-character SHA-256 prefix over the sanitized channel name, and `verificationCode` is the room-code fingerprint users compare after entering the passphrase out of band.

Receivers that have not entered the room key keep a bounded in-memory buffer of locked room packets. When the user later enters the matching passphrase, AirChat attempts to unlock buffered packets for that channel and replaces locked placeholders with verified plaintext.

## Delivery receipts

When a peer accepts and verifies a public `Chat` packet, decrypts and verifies a private-room text packet, or decrypts and verifies a direct text packet, it sends a signed `Ack` packet back toward the origin. ACK packets can also be relayed through the mesh.

```json
{
  "packetId": "uuid",
  "receivedAt": 1785160001000,
  "status": "Verified"
}
```

Senders mark a local message as `received` after a valid ACK for that packet id. Direct-message ACKs are accepted only from the direct recipient named in the local message channel.

## Courier receipts

When a relay verifies a packet, fails to broadcast it onward, and retains it in the encrypted courier queue, it sends a signed `CourierReceipt` toward the original sender.

```json
{
  "packetId": "uuid",
  "storedAt": 1785160001000,
  "expiresAt": 1785160901000,
  "remainingTtl": 6
}
```

A courier receipt is not a delivery ACK. It only means one relay accepted responsibility to retry the packet until the receipt's expiry window or until the user clears/disables courier relay. `CourierReceipt` packets are not relayed, acknowledged, or courier-stored.

When the sender receives a verified courier receipt for a local message, AirChat may show that local message as `relayed`. A later valid `Ack` from a recipient still upgrades that message to `received`.

## Public history sync

When a transport reports a peer, AirChat may send a signed `HistoryRequest` packet directly to that peer. The request includes a bounded list of public packet ids already visible locally, an optional list of public channels, and a requested maximum packet count.

```json
{
  "knownPacketIds": ["uuid-1", "uuid-2"],
  "channels": [],
  "maxPackets": 24
}
```

An empty `channels` list means the requester is willing to receive recent public chat packets from any public room. A responder answers with a signed `HistoryResponse` packet containing only recent signed `Chat` packets that are not in `knownPacketIds`.

```json
{
  "requestId": "history-request-id",
  "packets": []
}
```

Receivers verify the outer response signature and then verify every included `Chat` packet against the original origin signature before appending it to the local message log. History-imported messages do not trigger ACK packets, relay, courier storage, private-room unlock attempts, or file reassembly. `HistoryRequest` and `HistoryResponse` packets are not relayed or courier-stored.

## File transfers

File transfer is represented by a manifest packet and a sequence of chunk packets. Chunks are capped at 32 KiB before base64url encoding, and the current file picker caps selected files at 10 MB. Receivers reassemble only when every chunk index is present and the final SHA-256 hash matches the manifest.

Public-channel files use `FileManifest` and `FileChunk` packets. Private-room files use `RoomEncrypted` packets whose decrypted envelope kind is `FileManifest` or `FileChunk`. Direct files use `Direct` packets whose decrypted envelope kind is `FileManifest` or `FileChunk`. Plain `FileManifest` and `FileChunk` packets in `dm:` channels are ignored.

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
- A device never displays, acknowledges, relays, or courier-stores packets from locally blocked peer ids.
- A device never sends an ACK for an ACK.
- Only signed and verified non-`Hello` packets are relayed.
- `HistoryRequest` and `HistoryResponse` are direct sync control packets and are never relayed or courier-stored.
- If no transport accepts a relay packet, the router keeps it in a bounded encrypted courier queue for later peer contact.

## Courier queue

Courier mode is opportunistic store-and-forward for packets that were already accepted by `PacketGuard` and verified against the origin signature.

- Queue capacity is 256 packets.
- Each origin is capped to a default of 32 retained packets so one noisy peer cannot monopolize the local queue.
- Queue lifetime is user-configurable at 5, 15, or 60 minutes.
- Users can disable courier relay or clear the queue manually from the app UI.
- Entries are encrypted at rest with Android Keystore AES-GCM and excluded from Android backup/device transfer.
- The queue is flushed when transports report peer changes or when the router starts.
- Courier entries keep the already-decremented TTL and appended relay path, so retries do not create extra hops.
- A relay sends a signed `CourierReceipt` to the packet origin when it stores a packet.
- Public packets remain visible to local peers; private-room and direct packets remain encrypted but still expose metadata such as timing and packet size.

## Guard rails

Inbound packets pass through `PacketGuard` before display or relay:

- TTL must be between 0 and 7.
- Route path must be no longer than 12 peers.
- Payload must stay below the configured size limit.
- Timestamps must not be stale or too far in the future.
- A single origin is rate-limited within a short rolling window.
- Locally blocked origins are dropped after peer discovery metadata is refreshed, but before signature handling, display, ACK, relay, or courier queueing.

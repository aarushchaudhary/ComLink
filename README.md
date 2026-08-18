# ComLink

> *"Privacy is necessary for an open society in the electronic age."* — A Cypherpunk's Manifesto

**ComLink** is a decentralized, off-grid mesh messenger for Android. No servers. No internet. No phone numbers. Just Bluetooth Low Energy, cryptography, and the devices in your pocket.

Messages travel device-to-device over an encrypted BLE mesh network. If the recipient is out of direct range, nearby ComLink devices automatically relay the encrypted payload — up to 10 hops — without ever being able to read it.

Built natively in Kotlin with Jetpack Compose. Designed for the moments when the network is down, the towers are off, or you simply refuse to trust them.

---

## Features

**Off-Grid Communication**
Messages are transmitted exclusively over Bluetooth Low Energy. No cellular, no Wi-Fi, no internet connection of any kind.

**End-to-End Encryption**
Every message is encrypted with AES-256-GCM using keys derived from an X25519 ECDH key agreement. Your private key never leaves the device — it's wrapped by AES-256-GCM inside the Android Keystore hardware.

**Multi-Hop Mesh Routing**
When the recipient isn't in direct BLE range, intermediate ComLink devices relay the encrypted envelope. Each relay decrements a TTL counter (max 10 hops). Relay nodes can route the message but cannot decrypt its contents.

**Identity via QR Code**
No phone numbers. No email addresses. No accounts. Identity is a cryptographic keypair generated on-device. You exchange public keys out-of-band by scanning a peer's Protobuf-encoded QR code, then verify the relationship via a deterministic SHA-256 fingerprint.

**Offline Message Queue**
Messages sent while the recipient is out of range are persisted locally and automatically retransmitted when the peer reconnects.

**Delivery Receipts**
Encrypted ACK messages confirm delivery. The UI shows message status: `[WAIT]` → `[SENT]` → `[ACK]`.

**Cypherpunk Aesthetic**
A custom Jetpack Compose design system — black backgrounds, monospaced typography, hard borders, and a user-configurable accent color wheel. No Material defaults.

---

## How It Works

### 1. Identity Generation

On first launch, ComLink generates:

- An **X25519 keypair** — the long-term identity for key agreement
- A **random device ID** (16 bytes, Base64) — used for message routing
- The private key is encrypted using **AES-256-GCM** via Google Tink, backed by the **Android Keystore** hardware security module

```
Private Key → Tink AEAD (AES-256-GCM) → Android Keystore HSM
Public Key  → SharedPreferences (Base64)
Device ID   → SharedPreferences (Base64)
```

### 2. Peer Discovery & Key Exchange

ComLink runs as a **dual-role BLE device** — simultaneously advertising as a GATT server and scanning for other ComLink devices via a shared service UUID.

To add a contact:
1. Your peer opens their QR code (a Base64-encoded `ContactPayload` protobuf containing their device ID, public key, and display name)
2. You scan it with ComLink's CameraX-powered scanner
3. A **SHA-256 fingerprint** is computed from both public keys (lexicographically sorted) for out-of-band verification
4. On confirmation, the peer's public key and device ID are stored in the local Room database

### 3. Sending a Message

```
Plaintext
    ↓
SessionCipher.encrypt(plaintext, counter)
    ├── X25519 ECDH → Shared Secret
    ├── HKDF-SHA256 → 32-byte AES key + 8-byte session salt
    ├── Nonce: [8B salt][1B direction][3B counter]
    └── AES-256-GCM encrypt → Ciphertext + Auth Tag
    ↓
Envelope (Protobuf)
    ├── sender_id, recipient_id
    ├── ciphertext, nonce
    ├── envelope_id (UUID), timestamp
    └── ttl = 10
    ↓
Wire encode → BLE chunking (500B chunks + 6B header) → GATT write
```

**Nonce construction** ensures uniqueness without coordination:
- **Session salt** (8 bytes): derived from HKDF, shared between peers
- **Direction byte** (1 byte): `0x01` or `0x02`, assigned by lexicographic ordering of public keys — guarantees both sides use different nonces even at the same counter value
- **Counter** (3 bytes): strictly monotonic, supports ~16 million messages per session

### 4. Mesh Routing

When a device receives an envelope:

1. **Deduplication** — the `envelope_id` is checked against an LRU cache (10,000 entries). If seen before, the envelope is dropped.
2. **Destination check** — if `recipient_id` matches this device, decrypt and deliver.
3. **Relay** — if not for this device, decrement TTL. If TTL > 0, re-broadcast to all connected peers. The ciphertext is never touched.

```
Device A ──BLE──▶ Device B ──BLE──▶ Device C ──BLE──▶ Device D
(sender)         (relay)           (relay)           (recipient)
                 Can't decrypt     Can't decrypt     Decrypts ✓
                 TTL: 10→9         TTL: 9→8
```

### 5. Anti-Replay Protection

Each session tracks two counters in the database:
- `myNextCounter` — the next counter this device will use for sending
- `peerHighestCounter` — the highest counter received from the peer

Any incoming message with a counter ≤ `peerHighestCounter` is silently dropped as a replay.

---

## Architecture

```
com.aarushchaudhary.comlink/
│
├── ComLinkApp.kt                    # Application class, dependency wiring
├── MainActivity.kt                  # Entry point, permissions, navigation, tabs
│
├── bluetooth/
│   ├── BluetoothService.kt          # BLE GATT server/client, chunking, heartbeat
│   └── MeshRouter.kt                # LRU dedup, multi-hop relay, message routing
│
├── crypto/
│   ├── IdentityManager.kt           # X25519 keygen, Keystore wrapping, fingerprints
│   └── SessionCipher.kt             # ECDH, HKDF, AES-256-GCM encrypt/decrypt
│
├── data/
│   └── ComLinkDatabase.kt           # Room entities (peers, sessions, messages), DAO
│
├── ui/
│   ├── ComLinkViewModel.kt          # Central orchestrator — send, receive, presence
│   ├── CypherpunkComponents.kt      # Bottom bar, color wheel
│   ├── QrImageAnalyzer.kt           # CameraX image analysis → ZXing decode
│   ├── QrUtils.kt                   # QR bitmap generation
│   ├── conversation/
│   │   └── ConversationScreen.kt    # Chat UI — bubbles, replies, context menu
│   ├── settings/
│   │   └── SettingsScreen.kt        # Display name, accent color, about
│   └── theme/
│       └── Theme.kt                 # DataStore-backed dynamic theming
│
└── proto/
    └── comlink.proto                # Envelope, ContactPayload, AckMessage
```

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose (100% custom components) |
| Networking | Android BLE — `BluetoothGattServer` + `BluetoothLeScanner` (dual-role) |
| Cryptography | Google Tink — X25519, AES-256-GCM, HKDF-SHA256, Android Keystore |
| Database | Room (SQLite) — 3 tables, auto-migrations, reactive `Flow` queries |
| Serialization | Square Wire (Protocol Buffers 3) |
| Camera | CameraX + ZXing (QR scanning & generation) |
| State | Kotlin Coroutines, Flows, ViewModel, Jetpack DataStore Preferences |

### Database Schema

| Table | Purpose |
|---|---|
| `peers` | Stores contacts — device ID, public key, display name, nickname, online status, last seen |
| `session_states` | Tracks send/receive counters per peer for replay protection |
| `messages` | Persists all messages with delivery status, reply metadata, and foreign key to `peers` (cascade delete) |

### BLE Protocol

ComLink uses a custom chunking protocol over BLE GATT:

```
┌──────────────────────────────────────────┐
│              Chunk Header (6B)           │
├──────────┬──────────────┬────────────────┤
│ packetId │ totalChunks  │  chunkIndex    │
│  (4B)    │    (1B)      │    (1B)        │
├──────────┴──────────────┴────────────────┤
│              Payload (up to 500B)        │
└──────────────────────────────────────────┘
```

- **MTU**: Negotiated up to 512 bytes per connection
- **Chunk size**: Adapts to the negotiated MTU per peer
- **Reassembly**: Receiver buffers chunks by `packetId` until all arrive, then reassembles
- **Heartbeat**: Periodic PING packets detect stale connections

---

## Build Instructions

### Requirements

- Android Studio (Ladybug or newer)
- Android SDK 34+
- A physical Android device — **emulators do not support BLE**
- Minimum two devices to test peer-to-peer messaging

### Build

```bash
# Clone
git clone https://github.com/aarushchaudhary/ComLink.git
cd ComLink

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

### Permissions

ComLink requests the following permissions at runtime:

| Permission | Purpose |
|---|---|
| `BLUETOOTH_CONNECT` | Connect to nearby BLE devices (Android 12+) |
| `BLUETOOTH_ADVERTISE` | Advertise as a GATT server for peer discovery |
| `BLUETOOTH_SCAN` | Scan for nearby ComLink devices |
| `CAMERA` | Scan QR codes for peer key exchange |

Location permission is **not** requested. BLE scanning uses the `neverForLocation` flag.

---

## Security Model

| Property | Implementation |
|---|---|
| **Key Generation** | X25519 keypair via Google Tink |
| **Key Storage** | Private key encrypted with AES-256-GCM, master key in Android Keystore HSM |
| **Key Exchange** | Out-of-band via QR code (Protobuf-encoded `ContactPayload`) |
| **Key Verification** | Deterministic SHA-256 fingerprint from lexicographically sorted public keys |
| **Key Agreement** | X25519 Elliptic Curve Diffie-Hellman |
| **Key Derivation** | HKDF-SHA256 (40 bytes: 32B AES key + 8B nonce salt) |
| **Message Encryption** | AES-256-GCM with 128-bit authentication tag |
| **Nonce Construction** | Deterministic 96-bit: `[8B salt][1B direction][3B counter]` |
| **Replay Protection** | Strictly monotonic counter per session; stale counters rejected |
| **Routing Privacy** | Relay nodes see `Envelope` headers only; ciphertext is opaque |
| **Broadcast Storm Prevention** | LRU cache (10,000 envelope IDs) deduplicates relayed messages |

### What ComLink Does Not Protect Against

- **Traffic analysis** — an observer can see that two devices are communicating over BLE, even if the content is encrypted
- **Device compromise** — if an attacker gains root access to your device, they can extract the decrypted private key from memory
- **Long-term key compromise** — ComLink does not currently implement forward secrecy (e.g., Double Ratchet). Compromising a private key decrypts all past and future messages for that peer.

---

## License

ComLink is licensed under the [GNU General Public License v3.0](LICENSE).

---

> *Developed by [Aarush Chaudhary](https://github.com/aarushchaudhary)*

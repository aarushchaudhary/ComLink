# ComLink
Developed by **Aarush Chaudhary**.

**ComLink** is a cypherpunk, off-grid, and serverless peer-to-peer chat application for Android. It operates completely independently of internet infrastructure, cellular networks, and Google Mobile Services (GMS). It relies entirely on a custom-built **Bluetooth Low Energy (BLE) GATT mesh network** to provide secure, multi-hop communication.

---

## Architecture & Features

### 1. 100% Off-Grid Mesh Networking (BLE GATT)
ComLink has abandoned unstable Bluetooth Classic/RFCOMM sockets in favor of a highly resilient, dual-role BLE GATT architecture:
* **Dual-Role Operation:** Every device acts simultaneously as a **GATT Server** (listening for incoming connections) and a **GATT Client** (actively scanning for and connecting to discovered peers).
* **No Pairing Required:** Connections are completely unbonded. There are no OS-level pairing prompts, ensuring seamless, silent background meshing.
* **Custom Packet Fragmentation:** BLE MTUs are strictly limited. ComLink implements an automatic chunking engine that fragments encrypted Protobuf payloads into 500-byte chunks. A custom 6-byte header `[Packet ID (4 bytes) | Total Chunks (1 byte) | Chunk Index (1 byte)]` guarantees reliable reassembly on the receiving end.
* **Multi-Hop Gossip Routing:** Messages are relayed to peers out of direct physical range. The built-in `MeshRouter` uses a Time-To-Live (TTL) limit and an LRU-cache based deduplication mechanism to efficiently flood the mesh without endless loops.

### 2. State-of-the-Art Cryptography
Built natively on top of **Google Tink**, ComLink guarantees end-to-end encryption without centralized key servers:
* **Identity:** In-person QR code scans exchange raw X25519 Public Keys.
* **Diffie-Hellman Key Exchange (ECDH):** Shared secrets are mathematically derived from the exchanged keys.
* **Encryption:** AES-256-GCM is applied to every packet payload.
* **Strict Monotonic Security:** Replay attacks are prevented using deterministic, strictly-incremental 96-bit nonces (Salt + Direction + Counter) generated via HKDF.
* **Serialization:** Square Wire Protobuf is used for byte-perfect, lightweight serialization of the encrypted envelopes.

### 3. De-Googled & Privacy First
* Strictly **NO** Google Mobile Services (GMS), FCM (Firebase Cloud Messaging), or WebRTC dependencies.
* Works flawlessly on privacy-hardened Android forks such as **CalyxOS**, **GrapheneOS**, and AOSP ROMs.
* No message deletion or editing (strict immutability). Data is yours and yours alone.

### 4. Modern Jetpack Compose UI
* **Presence Indicators:** View real-time "Online" status if a peer is directly connected via BLE, or "Last Seen" timestamps derived from the mesh gossiping.
* **Rich Messaging:** Long-press context menus, message copy, and rich quoted message replies visually integrated into modern chat bubbles.
* **Customizable Aesthetics:** Dark Mode, Light Mode, and System Theme support alongside a selection of cyberpunk-inspired accent colors (Cypherpunk Green, Electric Cyan, Sunset Amber, Royal Purple).

---

## Tech Stack
* **Language:** 100% Kotlin
* **UI:** Jetpack Compose (Material 3)
* **Data Persistence:** Jetpack Room & DataStore Preferences
* **Cryptography:** Google Tink (`com.google.crypto.tink:tink-android:1.9.0`)
* **Serialization:** Square Wire Protobuf (`com.squareup.wire:wire-runtime:4.9.9`)
* **Camera / QR:** CameraX API & ZXing

---

## Security & Verification Flow

1. **Initial Meet:** Two users meet in person.
2. **QR Handshake:** User A generates an X25519 Keypair. User B scans User A's QR code.
3. **Cryptographic Validation:** The app displays a SHA-256 fingerprint of the key for manual validation.
4. **Session Establishment:** Once accepted, all future peer-to-peer communications are implicitly authenticated and end-to-end encrypted. Even if packets are relayed through untrusted middle-men in the mesh, the contents cannot be read or tampered with.

---

## Build Instructions

**Important Note for Developers:** ComLink enforces a strict dependency on Gradle 8.7 to maintain compatibility with the Square Wire protobuf compiler.

1. Clone the repository.
2. Open the project in Android Studio.
3. If Android Studio prompts you to upgrade the Gradle JVM or the AGP version, **IGNORE IT**. Do not click the quick fix.
4. Go to `File -> Settings -> Build, Execution, Deployment -> Build Tools -> Gradle`.
5. Set the **Gradle JDK** to **Java 21**.
6. Sync the project and run `./gradlew assembleDebug`.

---

## License
GNU General Public License v3.0 (GPLv3)

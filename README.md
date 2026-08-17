# ComLink 🟢

> "Privacy is necessary for an open society in the electronic age." - *A Cypherpunk's Manifesto*

ComLink is a decentralized, off-grid Bluetooth Mesh messenger designed for Android. It operates entirely without cellular towers, Wi-Fi, or centralized servers. It relies exclusively on Bluetooth Low Energy (BLE) to establish an encrypted, peer-to-peer mesh network to route messages directly from device to device.

Built from the ground up natively on Android using modern Jetpack Compose architecture, ComLink enforces a strict cypherpunk aesthetic with dynamic theming, ensuring absolute utility and undeniable style.

## 🚀 Core Features

- **True Peer-to-Peer:** Zero central servers. All routing happens locally over BLE.
- **End-to-End Encryption:** Utilizes Google Tink for secure Ed25519 key generation and AES-256-GCM message encryption.
- **Multi-Hop Mesh Routing:** Messages can hop through intermediate devices (up to 10 hops) to reach their destination if the recipient isn't in direct range.
- **Identity via QR:** No phone numbers or emails. Exchange cryptographic keys out-of-band by scanning your peer's Protobuf-encoded QR code.
- **Cypherpunk UI/UX:** A bespoke Jetpack Compose design system stripping out standard Material UI in favor of high-contrast, monospaced typography, hard borders, and a global dynamic accent color wheel.

## 🏗️ Architecture Stack

- **UI:** Jetpack Compose (100% custom components)
- **Database:** Room (SQLite) for offline message persistence and peer tracking.
- **Network:** Android `BluetoothGattServer` / `BluetoothGattClient` for BLE mesh.
- **Crypto:** Google Tink (AEAD / Hybrid Encryption).
- **Serialization:** Protocol Buffers (`comlink.proto`) for minimal-overhead BLE transmission.
- **Camera:** CameraX and ZXing for QR payload generation and scanning.
- **State:** Kotlin Coroutines / Flows & Jetpack DataStore.

## 🛠️ Build Instructions

ComLink is built purely natively using Gradle and targeting Android 15+.

1. Clone the repository:
```bash
git clone https://github.com/aarushchaudhary/ComLink.git
```
2. Open the project in Android Studio (Jellyfish or newer recommended).
3. Connect an Android device (Emulators do not support Bluetooth transmission).
4. Run `Assemble Debug` or compile directly via gradle:
```bash
./gradlew build
```

## 🔒 Security Model

1. **Identity:** When installed, a unique `deviceId` and Ed25519 Keypair are generated into the Android Keystore.
2. **Handshake:** Scanning a peer's QR code exchanges `ContactPayload` protobufs, storing their Public Key locally.
3. **Session:** All subsequent messages sent to that peer are encrypted using AES-256-GCM. 
4. **Routing:** Encrypted ciphertexts are wrapped in an `Envelope` protobuf. Intermediate devices in the mesh can read the `Envelope` to route the message, but cannot decrypt the inner ciphertext.

---

> *Developed by Aarush Chaudhary*

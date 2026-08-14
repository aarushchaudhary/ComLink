# ComLink

**ComLink** is a cypherpunk, off-grid, and serverless peer-to-peer chat application for Android. It operates completely independently of internet infrastructure and Google Mobile Services (GMS), relying exclusively on Bluetooth Classic for multi-hop mesh networking.

## Features

- **100% Off-Grid:** No cell towers, no Wi-Fi, no internet. All communication goes through a resilient, ad-hoc Bluetooth Classic mesh network.
- **De-Googled Ready:** Strictly no Google Mobile Services (GMS), FCM, or WebRTC dependencies. Works perfectly on CalyxOS, GrapheneOS, and AOSP ROMs.
- **State-of-the-art Cryptography:** Built on top of **Google Tink**.
  - **Identity:** X25519 Keypairs for Identity and Diffie-Hellman Key Exchange (ECDH).
  - **Encryption:** AES-256-GCM for all packets.
  - **Security:** Deterministic strictly-incremental 96-bit nonces (Salt + Direction + Counter) generated via HKDF.
- **Protobuf Messaging:** Lightweight packet serialization using Square Wire to preserve bandwidth over low-throughput RFCOMM Bluetooth sockets.
- **Multi-Hop Mesh Routing:** Built-in message relaying to peers out of direct physical range using an LRU-cache based gossip routing mechanism.

## Tech Stack
- **Language:** 100% Kotlin
- **UI:** Jetpack Compose (Material Design)
- **Data Persistence:** Room Database
- **Cryptography:** Google Tink (`com.google.crypto.tink:tink-android`)
- **Serialization:** Square Wire Protobuf (`com.squareup.wire`)
- **Build System:** Gradle (Kotlin DSL) 8.7

## Security & Verification

To establish a trusted session, users exchange their public X25519 identity keys in person via QR code. Once the public keys are exchanged, all subsequent peer-to-peer communications are implicitly authenticated and encrypted end-to-end.

## Build Instructions

**Important Note for Developers:** ComLink enforces a strict dependency on Gradle 8.7 to maintain compatibility with the Square Wire protobuf compiler.

1. Clone the repository.
2. Open the project in Android Studio.
3. If Android Studio prompts you to upgrade the Gradle JVM or the AGP version, **IGNORE IT**. Do not click the quick fix.
4. Go to `File -> Settings -> Build, Execution, Deployment -> Build Tools -> Gradle`.
5. Set the **Gradle JDK** to **Java 21**.
6. Sync the project and run `./gradlew assembleDebug`.

## License
MIT License

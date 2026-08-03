# ADB Over TCP

A standalone Android 15 utility that bundles its own ADB executable, pairs through Android Wireless Debugging, switches `adbd` to classic TCP 5555, and reconnects locally through `127.0.0.1:5555`.

## Automatic pairing flow

1. Tap **Start Automatic Pairing**.
2. The app opens Android's Wireless Debugging settings and starts mDNS discovery for `_adb-tls-pairing._tcp`.
3. Enable Wireless Debugging and tap **Pair device with pairing code**.
4. The app detects the pairing IP and randomized port automatically.
5. Enter only the six-digit code through the notification's inline reply, or use the in-app fallback field.
6. The app pairs its built-in ADB client, discovers `_adb-tls-connect._tcp`, connects, requests `tcpip 5555`, and reconnects through `127.0.0.1:5555`.
7. Optionally, the app starts Shizuku using its official on-device ADB startup script.
8. Wi-Fi may then be disconnected while loopback ADB and Shizuku continue for the current boot session.

No pairing IP, pairing port, or connection port needs to be typed manually.

## Wi-Fi control

After loopback ADB is active, the app can enable or disable Wi-Fi through Android's shell-level `svc wifi` command. The app verifies the loopback transport before every toggle and prints the before/after Wi-Fi status.

The Wi-Fi button cannot work after **Full Off** or after the app deliberately removes its own ADB key, because both actions remove the privileged transport used by the command.

## Home-screen widget

The configurable widget provides:

- **Pair:** opens Wireless Debugging and starts automatic pairing.
- **Wi-Fi:** toggles Wi-Fi through the existing loopback ADB transport.
- **Open:** opens the full app.

Each widget instance can independently choose whether to start Shizuku and whether to remove this app's ADB key from Android's Paired devices after setup.

## Optional self-removal from Paired devices

When enabled, the app calculates the fingerprint of its own ADB public key and asks Android's ADB manager to unpair that fingerprint after TCP setup and optional Shizuku startup. It then deletes its local key pair.

This is intentionally destructive: Shizuku can continue running for the current boot session, but the app must create and pair a new key before it can use loopback ADB or toggle Wi-Fi again. The unpair operation uses Android's hidden shell-accessible ADB manager transaction and is therefore treated as an experimental OEM-compatibility feature.

## Built-in ADB

Development builds package LADB's native ADB executable for arm64-v8a and armeabi-v7a inside the APK. Termux, an external `adb` binary, and Shizuku are not required for the pairing/TCP conversion itself.

The embedded component's license is retained in `app/src/main/assets/LADB_LICENSE.txt`. Do not publish unofficial builds containing it to Google Play.

## Shizuku integration

When enabled, the app runs Shizuku's on-device startup script after loopback ADB is verified:

```sh
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Shizuku client apps can then use the running Shizuku service without requiring Wi-Fi to stay connected. Shizuku still stops after a full reboot on a stock non-root device and must be started again.

## Start/stop meanings

- **Reconnect:** connects the bundled ADB client to the existing loopback listener.
- **Safe Off:** disconnects this app but leaves TCP 5555 available for reconnection.
- **Full Off:** sends `adb usb`, shutting down TCP. Automatic pairing must be run again to restore it.

## Boot behavior

The tested Motorola Android 15 build blocks writing `persist.adb.tcp.port`, so the app does not depend on persistent TCP. The main supported workflow is automatic pairing once per boot whenever Wi-Fi is available, followed by same-boot operation over loopback/mobile data.

The optional boot scan still checks readable OEM init rules for device-specific persistence mechanisms.

## Permanent repository signing key

Starting with version **0.3.2**, every debug and release APK built from this repository uses the same permanent development signing certificate.

**SHA-256 certificate fingerprint:**

```text
8B:F2:5A:D7:4C:47:54:72:17:A2:F3:61:4B:E4:F6:60:B6:00:4A:38:28:28:47:01:A5:21:D3:95:27:F2:A0:18
```

GitHub Actions verifies the certificate on both APK variants and fails the workflow if the fingerprint changes. This means future APKs can update one another without uninstalling, provided they are version 0.3.2 or later.

The key is intentionally a **public development key** because this repository is public. It guarantees update compatibility but must not be used as a private Play Store or security-sensitive production signing identity.

Because versions through 0.3.1 were signed by GitHub runner debug keys, installing 0.3.2 may require one final uninstall of the older app. After 0.3.2 is installed, subsequent builds will retain the same signing identity.

## Build

The project uses Gradle 8.11.1, Android Gradle Plugin 8.9.3, Kotlin 2.1.0, compile/target SDK 35, and Java 17.

```bash
gradle :app:assembleDebug :app:assembleRelease
```

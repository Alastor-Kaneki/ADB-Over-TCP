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

## Built-in ADB

Development builds package LADB's native ADB executable for arm64-v8a and armeabi-v7a inside the APK. Termux, an external `adb` binary, and Shizuku are not required for the pairing/TCP conversion itself.

The embedded component's license is retained in `app/src/main/assets/LADB_LICENSE.txt`. Do not publish unofficial builds containing it to Google Play.

## Shizuku integration

When enabled, the app runs the startup command documented by Shizuku after loopback ADB is verified:

```sh
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Shizuku client apps can then use the running Shizuku service without requiring Wi-Fi to stay connected. Shizuku still stops after a full reboot on a stock non-root device and must be started again.

## Start/stop meanings

- **Reconnect:** connects the bundled ADB client to the existing loopback listener.
- **Safe Off:** disconnects this app but leaves TCP 5555 available for reconnection.
- **Full Off:** sends `adb usb`, shutting down TCP. Automatic pairing must be run again to restore it.

## Boot behavior

The tested Motorola Android 15 build blocks writing `persist.adb.tcp.port`, so the app does not depend on persistent TCP. The main supported workflow is automatic pairing once per boot whenever Wi-Fi is available, followed by same-boot operation over loopback/mobile data.

The optional boot scan still checks readable OEM init rules for device-specific persistence mechanisms.

## Build

The project uses Gradle 8.11.1, Android Gradle Plugin 8.9.3, Kotlin 2.1.0, compile/target SDK 35, and Java 17.

```bash
gradle :app:assembleDebug
```

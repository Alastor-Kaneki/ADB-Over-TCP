# ADB Over TCP

A standalone Android 15 utility that bootstraps classic ADB-over-TCP from Android Wireless Debugging, then reconnects locally through `127.0.0.1:5555` so Wi-Fi can be turned off while mobile data remains active.

## Current flow

1. Open Android Wireless Debugging settings from the app.
2. Enter the pairing `IP:port` and six-digit code.
3. Enter the separate connection port shown on the main Wireless Debugging screen.
4. The bundled ADB executable runs `pair`, `connect`, `tcpip 5555`, then reconnects through loopback.
5. Use the optional overlay for reconnect, Safe Off, Full Off, settings, and status.
6. The boot receiver probes `127.0.0.1:5555` and attempts recovery when the OEM preserved the listener.

## Start/stop meanings

- **Reconnect:** connects to the existing internal listener.
- **Safe Off:** disconnects the app but leaves TCP 5555 available so it can reconnect on mobile data.
- **Full Off:** sends `adb usb`, which shuts down TCP. Re-enabling it then requires Wireless Debugging/USB or a successful OEM boot-persistence method.

## Boot compatibility scan

The app treats an actual socket and authenticated ADB shell on `127.0.0.1:5555` as the source of truth. Some Motorola Android 15 builds return blank values for `service.adb.tcp.port` even while classic TCP ADB is active.

The scan checks:

- whether loopback TCP 5555 is listening and authenticated;
- whether `persist.adb.tcp.port` can be written;
- whether property-based status is usable;
- readable system, product, vendor, ODM, and system-ext init files for ADB/TCP boot triggers.

On the tested Motorola build, writing `persist.adb.tcp.port` as shell is blocked by SELinux/property policy. Same-boot operation over mobile data remains supported; cold-boot recovery still requires a discovered OEM trigger, root, USB ADB, or temporary Wi-Fi pairing.

## Build

The project uses Gradle 8.11.1, Android Gradle Plugin 8.9.3, Kotlin 2.1.0, compile/target SDK 35, and Java 17.

```bash
gradle :app:assembleDebug
```

The build downloads LADB's native `libadb.so` for arm64-v8a and armeabi-v7a. Its license is retained in `app/src/main/assets/LADB_LICENSE.txt`.

## Important limitations

- This does not bypass Android's Wi-Fi requirement for the official Wireless Debugging toggle. It converts an authorized session into classic ADB TCP and then uses loopback.
- A stock non-root device may clear TCP ADB after reboot. The app tests persistence, but cannot guarantee cold-boot bootstrap on every OEM Android 15 build.
- Do not publish unofficial builds containing LADB's native component to Google Play.

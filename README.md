# ADB Over TCP

A standalone Android 15 utility that bootstraps classic ADB-over-TCP from Android Wireless Debugging, then reconnects locally through `127.0.0.1:5555` so Wi-Fi can be turned off while mobile data remains active.

## Planned flow

1. Open Android Wireless Debugging settings.
2. Enter the pairing `IP:port` and six-digit code.
3. Enter the separate connection port.
4. The embedded ADB executable pairs, connects, requests `tcpip:5555`, and reconnects through loopback.
5. Optional overlay provides Start, Safe Off, Full Off, Settings, and Status actions.
6. A boot receiver probes loopback and records whether the OEM preserved TCP ADB.

## Important limitation

A stock, non-root Android device may clear the TCP listener after reboot. The app tests persistence and recovery paths, but cannot guarantee cold-boot privilege bootstrap on every OEM build.

## Third-party component

Development builds fetch the native ADB executable from [LADB](https://github.com/tytydraco/LADB), retaining its license and Google Play distribution restriction. Do not publish unofficial builds containing that component to Google Play.

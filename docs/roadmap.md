# Roadmap: dLidar Mobile

This is a proposal. The order after milestone 1 is the app team's suggestion, not part of the spec.
Each milestone names the scanner-side answers it needs (numbers refer to
[protocol-open-questions.md](protocol-open-questions.md)). Every milestone follows
[architecture.md](../architecture.md), ships with unit tests, and is reviewed before it is pushed to master.

| # | Milestone | Status |
|---|-----------|--------|
| 1 | BLE link, protocol v1, status screen | Done; link verified on device (echo service) |
| 2 | Real-server bring-up | Runs against the real server on the phone (hello, status.get, info.get, events); field tests of reconnect still open |
| 3 | Pairing and bonding | Fresh pairing verified on device: one dialog, bond before subscribing; failed pairing handled with the `pair.sh --forget` hint ([pairing.md](design/pairing.md)); the scanner's pairing button is coming |
| 4 | Recording control | Start and Stop verified on device against the real scanner without a lidar (start → preparing, stop → idle, sensor failure → error); a real recording is still to test |
| 5 | Wi-Fi setup | Built and tested against the fake scanner ([wifi-setup.md](design/wifi-setup.md)); waits for the scanner build to test on the device |
| 6 | Logs and diagnostics | Planned |
| 7 | Release | Planned |

## 2. Real-server bring-up
- Run the app against the real dLidar service: `hello`, `status.get`, `info.get`, events.
- Field-test reconnect: out of range, scanner reboot, Bluetooth off, app in the background.
- Several scanners nearby: pick the right one (needs a way to tell them apart).
- Freeze the UUIDs with the scanner side.
- **Needs answers:** 1–15, 26–28, 30.

## 3. Pairing and bonding
- Bond via the scanner's pairing button (Just Works, about a 2-minute window).
- Remember the bonded scanner and reconnect to it; handle a lost or removed bond.
- **Needs answers:** 29, 33.

## 4. Recording control
- `recording.start` / `recording.stop` with results that arrive later (`op` events).
- Live progress, warnings, the end-of-recording summary.
- **Needs answers:** 16–18, 20–25, 31, 32.

## 5. Wi-Fi setup
- Send network credentials (`wpa2-psk`, `wpa3-sae`, `open`), show the result.
- Never log or store passwords in plain text.
- **Needs answers:** 34.

## 6. Logs and diagnostics
- `log.get` with paging; share the logs from the app.
- Keep the Link test screen in debug builds only.
- **Needs answers:** 35.

## 7. Release
- Release signing, CI (build and tests on every push), Play Store listing.
- Accessibility, dark mode, translations if needed.
- Test on several phones and Android versions (8 to the latest).

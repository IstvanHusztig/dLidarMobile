# Design: Wi-Fi setup of the scanner (not built yet)

The scanner never scans for Wi-Fi networks itself; the phone does the scan, and the user
picks a network.

## Layers

| Layer | Piece |
|---|---|
| `:domain` | `WifiNetwork(ssid, security, rssi)`, `WifiSecurity` (`Wpa2Psk`, `Wpa3Sae`, `Open`, `Unsupported(raw)`), the `WifiNetworkScanner` port, the pure `NetworkListMerger`, and the use cases `ScanWifiNetworks`, `ConfigureWifi` and `ObserveScannerWifi` |
| `:infra:wifi` (new) | `AndroidWifiNetworkScanner`: `WifiManager.startScan()`, `SCAN_RESULTS_AVAILABLE_ACTION`, `scanResults`, the permission and location checks, `ScanThrottle`, and the pure `SecurityTypeResolver` |
| `:data:scanner` | The commands `WifiConnectCommand` (`sensitiveArgs = setOf("psk")`) and `WifiStatusCommand`, plus `PendingOperations`, which matches an `op` event to its request |
| `:presentation` | A network list, a password sheet, and the result |

## Steps

1. **Scan.** Android 13 and newer need `NEARBY_WIFI_DEVICES` (with `neverForLocation`); older
   versions need `ACCESS_FINE_LOCATION`. Location must be switched on, or the results stay
   empty. `ScanThrottle` allows at most 4 `startScan()` calls per 2 minutes in the foreground;
   a refused or throttled scan shows the cached `scanResults` with their age.
2. **List.** `NetworkListMerger` drops empty or hidden SSIDs and merges entries with the same
   SSID, keeping the strongest RSSI. The security type shown comes from the strongest entry.
3. **Security.** `SecurityTypeResolver` reads `ScanResult.capabilities`: `SAE` without `PSK` →
   `wpa3-sae`, `PSK` → `wpa2-psk` (a transition network that also offers SAE stays `wpa2-psk`
   until open question 34 is answered), no `WPA`/`RSN`/`WEP` → `open`. Anything else (EAP, WEP,
   OWE) → `Unsupported`, shown but not selectable.
4. **Password.** It is entered as a `CharArray` and kept only in memory. It is cleared after
   the request is sent and never logged: `sensitiveArgs` redacts it in the protocol log, and
   the domain types hide it in `toString`. It is never written to storage; a later "remember"
   feature would need the Android Keystore.
5. **Send.** `wifi.connect {"ssid", "psk", "security"}` (no `psk` for open networks) answers
   `{"accepted":true}`. The later event with `"op": <request id>` carries the result.
   `PendingOperations` waits for it with a timeout, and a reconnect before the result arrives
   shows "result unknown, check Wi-Fi status".
6. **Result.** connected → show `wifi.status` (SSID and IP address). Wrong password → ask
   again. Not found → "The scanner may be out of range of this network." Timeout → offer a
   retry.

## Tests

Pure JVM tests cover `NetworkListMerger`, `SecurityTypeResolver` (a table of real
capabilities strings), `ScanThrottle` with a fake clock, a check that `psk` is redacted in the
logs, and the op-event matching through `FakeScanner`.

# Design: Wi-Fi setup of the scanner (not built yet; contract final)

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
   `wpa3-sae`, `PSK` → `wpa2-psk` (a transition network that also offers SAE is sent as
   `wpa2-psk`, as the scanner side decided), no `WPA`/`RSN`/`WEP` → `open`. Anything else (EAP,
   WEP, OWE) → `Unsupported`, shown but not selectable. Hidden SSIDs and SSIDs that are not
   UTF-8 are out of scope too.
4. **Password.** It must be 8 to 63 printable ASCII characters or 64 hex digits (the scanner
   answers anything else with `bad_request`), so the app checks it before sending. It is
   entered as a `CharArray` and kept only in memory. It is cleared after
   the request is sent and never logged: `sensitiveArgs` redacts it in the protocol log, and
   the domain types hide it in `toString`. It is never written to storage; a later "remember"
   feature would need the Android Keystore.
5. **Send.** `wifi.connect {"ssid", "security", "psk"}` (no `psk` for open networks) answers
   `{"accepted":true,"timeout_s":60}`. The `result` event with `"op": <request id>` carries the
   outcome and always comes within `timeout_s`. `PendingOperations` waits `timeout_s` plus a
   margin. Immediate errors: `busy` (another join runs, from any phone) → "try again shortly";
   `bad_request` (the app's checks and the scanner's disagree) → show the scanner's message.
   The app checks the SSID too: 1 to 32 bytes of UTF-8.
6. **Result.** `ok:true` → connected; show the SSID and the IP address from the result (ip may
   be null). `wrong_password` → ask again. `not_found` → "The scanner may be out of range of
   this network." `timeout` → offer a retry (a network out of range can show as timeout too,
   since the Pi never scans). `failed` → show the scanner's message as it is (for example a
   missing permission rule on the Pi, NetworkManager not running, no Wi-Fi device), offer a
   retry.
7. **Recovery.** The Pi 5's radio is shared, so the BLE link may drop during the join (not
   measured yet). After a reconnect, or when the wait runs out, the app asks `wifi.status`:
   `last.result` `"pending"` → keep waiting; otherwise `last` gives the lost outcome (only for
   the SSID the user sent; `last` is the newest join since dLidar started).
8. **Status.** `wifi.status` → `state` `connected`, `connecting`, `disconnected` or
   `unavailable`, with `ssid` and `ip` (null when not connected). `unavailable` → "Wi-Fi is not
   available on the scanner", and Connect is not offered. Wi-Fi setup is offered only when
   `hello` lists `wifi.connect` and `wifi.status`.

Scanner rules the app relies on: a network is saved only once joined, next to the saved ones
(the Pi joins whichever is in range); sending a saved SSID again replaces its entry, which is
how a password is changed, with a full join and a short Wi-Fi drop; on failure nothing is saved
and the Pi rejoins its previous network. Joining is allowed during a recording. Removing a
saved network (`wifi.forget`) and listing saved networks come later.

## Tests

Pure JVM tests cover `NetworkListMerger`, `SecurityTypeResolver` (a table of real
capabilities strings), `ScanThrottle` with a fake clock, a check that `psk` is redacted in the
logs, and the op-event matching through `FakeScanner`.

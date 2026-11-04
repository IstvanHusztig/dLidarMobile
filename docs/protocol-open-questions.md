# Protocol v1: open questions for the scanner side

Questions the app team found while building milestone 1. Where the app needed an answer to
work, the **current app behaviour** is noted; it is a conservative choice, not a protocol
assumption. Each **Answer** gives what the spec says; where it says nothing, the answer
says so. Each **Scanner answer** is the scanner team's reply: *binding* answers are in their
spec (docs/phone-protocol.md on the scanner side), *provisional* ones may still change.

## Framing and limits

1. **Does `max_request_bytes` (4096) count the trailing `\n`?** *App:* counts it, so the
   largest request is 4095 bytes plus the newline.

   **Answer:** The spec says only "a request is at most 4096 bytes". Whether the newline counts: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: 4096 includes the "\n". The app already counts it.

2. **Does `max_message_bytes` (16384) count the trailing `\n`?** *App:* accepts 16384 bytes
   plus the newline and drops longer lines.

   **Answer:** The spec gives `max_message_bytes: 16384` in hello's limits, nothing more. Whether the newline counts: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: 16384 includes the "\n". (The app accepts one byte more; harmless.)

3. **May the app send several messages in one write, or start a message in the middle of a
   write?** *App:* every request starts in a fresh write, and two requests never share one.

   **Answer:** The spec says the app splits its writes into pieces of at most MTU-3 bytes. Several messages in one write: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: a write may hold several messages or part of one; the server treats a connection as one byte stream. One request per write is fine.

4. **Write vs. write-without-response:** does the scanner handle both the same way? Is there a
   buffer limit, or a need for flow control, when the app writes quickly without response?
   *App:* uses write-with-response.

   **Answer:** The spec says RX accepts both write and write-without-response. Buffer limits and flow control: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: both are handled the same; use write-with-response. If the scanner's unsent output to the phone passes 64 KiB, the connection is closed.

5. **MTU:** the app requests 247, but Android 14 and newer always request 517 for the first
   exchange. Does the scanner cap the MTU at a value of its own, and does it always split by
   the MTU negotiated on this connection? Can the scanner start the MTU exchange itself?

   **Answer:** The spec says the app requests MTU 247 and the scanner splits messages by MTU-3; the echo service's `info` reports the MTU. A cap, and whether the scanner starts the exchange: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Provisional: no MTU cap of the scanner's own; it splits by MTU-3 of this connection (517 is fine). Do not assume 247.

   **Observed (2026-11-02, echo service, Android phone):** the phone asked for 517 and `info` reported `mtu=517`, so write pieces were 514 B. The scanner did not cap the MTU below 517.

6. **What does the scanner do with an oversized or unreadable line from the app?** The spec
   says `too_large` and "unreadable" arrive as `"id":null`. Does the request that caused it
   ever get its own response? *App:* that request times out after 5 s.

   **Answer:** The spec says too_large and unreadable requests get an unsolicited error with `"id":null`. Whether that request also gets its own response: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: an `"id":null` error for `too_large`, or `bad_request` without a readable id, takes that line's place in the response order: the app completes its **oldest pending request** with it. `too_many_clients` belongs to no request. *Conflicts with the app today (the request times out).*

## Requests and responses

7. **Ids:** can the app reuse ids across connections, and after wrapping past 2147483647?
   *App:* counts from 1 on each connection, wraps to 0, and skips ids still pending.

   **Answer:** The spec says ids are integers 0..2147483647. Reuse across connections and wrapping: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: ids are per connection and never checked for reuse. The app's scheme is fine.

8. **Ordering:** "responses come in request order". Does the scanner process requests one at
   a time? Can a slow command (later: `recording.start`) hold back the responses behind it?
   *App:* matches responses by id and only logs a response that arrives out of order.

   **Answer:** The spec says every request gets exactly one response, responses come in request order, and several requests may be in flight. Whether a slow command holds back later responses: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: requests are handled one at a time, in order; no command waits for the device (slow results use `accepted` plus a later `result` event).

9. **Late responses:** can a response arrive after 5 s, for example under load? *App:* a
   response for a request that already timed out is dropped.

   **Answer:** The spec says to use a timeout of about 5 s per request. Whether responses can come later: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: responses take milliseconds; late only if the link stalls. After a timeout, call `status.get` rather than reconnect.

10. **`"v"` field:** is it required on every request, including `hello`? What happens when it
    is missing? *App:* always sends `"v":1`.

   **Answer:** The spec's request format always has `"v":1`, and a wrong version gets `unsupported_version` with `"supported":[1]`. A missing `v`, and `v` on hello: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `"v"` is required on every request, hello included; a missing `v` gets `bad_request`.

11. **`unsupported_version`:** does `supported` list every version the scanner speaks? If the
    scanner supports a newer protocol only, is "update the app" the right message? *App:*
    shows "update the app" whenever the versions differ.

   **Answer:** The spec says `unsupported_version` carries `"supported":[1]` and hello is used to show "update the app" when the protocol does not match. Whether `supported` lists every version: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `supported` lists every version. App version above the scanner's max: "update the scanner"; below its min: "update the app". *Conflicts with the app today (it always says "update the app").*

12. **`ok:true` with `result` missing:** can that happen for some commands? *App:* treats it as
    an empty result.

   **Answer:** The spec always shows `result` with `ok:true`. Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `ok:true` always has a `result` object (maybe `{}`).

## Events

13. **`seq` and `dropped` together:** when K events are left out, does `seq` jump by K, is
    `dropped:K` set, or both? *App:* treats either one as a gap and calls `status.get`.

   **Answer:** The spec says `"dropped":K` is present when K events were left out, and either a seq jump or `dropped` means: call status.get. Whether seq also jumps by K: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `seq` never jumps; it counts events actually sent, from 1. Left-out events get no seq; `"dropped":K` on the next event is the only loss signal.

14. **First event:** is the first event of each connection always `seq:1`? Is there an event
    right after connecting (for example a `state` event), or only after a change? *App:* a
    first event other than 1 is a gap.

   **Answer:** The spec says seq counts events on this connection from 1, so the first event is seq 1. Whether an event is sent right after connecting: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: nothing is sent on connect; the first event is seq 1 at the next change. Call `status.get` after connecting.

15. **Events before notifications are on:** does the scanner hold events until the CCCD is
    written, or are those events lost (and counted in `seq`)?

   **Answer:** Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: over BLE a connection opens when TX notifications are enabled; earlier events are neither sent nor counted.

16. **Warnings are not recoverable:** `status.get` has no list of active warnings, so after a
    reconnect or a gap the app cannot know which warnings still apply. Can `status.get`
    include active warnings, or can warnings be repeated? Is there a "warning cleared" event?

   **Answer:** The spec has no warning list in status.get and no "warning cleared" event, so active warnings cannot be recovered after a reconnect. The request for one stays open.

   **Scanner answer (2026-11-02):** Binding, new: `status.get` has `"warnings": [{kind,text}]`, the current or last recording's warnings, the 16 newest, emptied when a recording starts. No "cleared" event.

17. **The summary is not recoverable:** a `summary` event missed during a disconnect is lost.
    Can `status.get` include the last summary, or can there be a command for it?

   **Answer:** The spec sends the summary only as an event at the end of a recording, so a missed summary is lost. The request for a way to fetch it stays open.

   **Scanner answer (2026-11-02):** Binding, new: `status.get` has `"summary"`: the last recording's summary object, or null.

18. **`progress` while finalizing:** does it continue? Does `state` repeat chunks and points?

   **Answer:** The spec says progress comes at most once per second. Whether it continues while finalizing: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: no progress after `finalizing`; the last progress before it has the final counts. `state` events carry state, chunks, points, last_ok, location. **Refined:** the recording's totals (chunks and points, its last partial chunk included) are in the final `idle` (or `error`) state event; the last progress can be one partial chunk short. Show totals from the final state (the app does: a state event replaces the counts).

19. **`data` missing:** may an event have no `data`? *App:* treats it as empty.

   **Answer:** The spec's event format always has `data`. Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `data` is always an object (except the `result` event, which has none; see question 31).

## Field meanings

20. **`location`:** a path, a storage name, or something else? May it be empty?

   **Answer:** The spec says only that `location` is a string. Its meaning: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: the absolute path of the output directory, never empty.

21. **`last_ok`:** does it describe the last finished recording? What is it before the first
    recording?

   **Answer:** The spec says only that `last_ok` is a bool. Its meaning: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: the last finished recording was saved completely; true before the first recording.

22. **`free_bytes: null`:** does it mean "no storage mounted", "unknown", or something else?

   **Answer:** The spec says `free_bytes` is int or null. What null means: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: null means it could not be read; show "unknown".

23. **State `error`:** how does the scanner leave it? Is there a reason field?

   **Answer:** The spec lists `error` as a state. How it is left, and a reason: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `error` lasts until the next recording starts; the reason is in the warnings.

24. **Summary `imu_mode` and `decision`:** what values can they take, and what should a user be
    told about each? *App:* shows them as they are.

   **Answer:** The spec says both are strings. Their values: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `imu_mode` is `"sensor"` (normal) or `"host_aligned"` (fallback); `decision` is readable text, shown as is. **Refined:** `imu_mode` can also be `"auto"` (a recording too short to decide).

25. **Integer sizes:** can `points`, `imu_in` and the others go beyond 2^53? *App:* reads 64-bit
    integers.

   **Answer:** The spec says `int`. The size: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: unsigned 64-bit; reading 64-bit is right.

## Connection

26. **`too_many_clients`:** when exactly is it sent (on connect, on the first request)? Does
    the scanner then disconnect the second phone? *App:* treats it as "another phone is
    connected", closes its link and does not retry on its own.

   **Answer:** The spec says only one phone can be connected at a time and too_many_clients comes as an unsolicited error with `"id":null`. When it is sent, and whether the scanner then disconnects: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Provisional: one phone at a time; advertising stops while connected; a second phone gets `too_many_clients` (id null) on its first write and is disconnected.

27. **Idle timeout or keep-alive:** does the scanner drop a connection with no traffic?

   **Answer:** Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: no idle timeout, no keep-alive needed.

28. **Advertising:** how can several scanners be told apart (a serial number in the name,
    manufacturer data)? Is the service UUID in the advertisement or in the scan response?

   **Answer:** The spec says the scanner advertises the name "dLidar" and the service UUID. Telling several scanners apart, and advertisement vs. scan response: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Provisional: match the scanner by service UUID, not name; a short device id will be added to the name later. Confirmed: the 128-bit service UUID will always be in the advertisement packet itself (a phone-link-ble requirement); only the name may move to the scan response.

29. **Address type:** public or random, and does it stay the same across reboots? The app
    reconnects by address, and the bonding plan depends on it.

   **Answer:** Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Provisional: the controller address is public and stable. Privacy may come later; reconnect through the bond.

30. **UUID change:** the UUIDs may change once before v1 is frozen. Will the echo service and
    the real server always share the same UUIDs?

   **Answer:** The spec says the echo service uses the same UUIDs, and the UUIDs may change once before v1 is frozen.

   **Scanner answer (2026-11-02):** Binding: the echo service and the real server always share the UUIDs.

## Later phases

31. **Results that come later:** which event carries `"op"` (one name per command, or one
    shared name)? Is `op` a top-level field or inside `data`? If that event is dropped, how can
    the app get the result (a query by op id)?

   **Answer:** The spec says the command answers `{"accepted":true}` and a later event carries `"op":<the request id>` with the result (used from phase 4 on). The event name, where `op` sits, and recovery when the event is dropped: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: one event `"result"`, `op` at top level, shaped like a response: `{"event":"result","seq":9,"op":12,"ok":true,"result":{...}}` or `ok:false` plus `error`. Sent only to the asking connection, never left out. **Refined:** the `result` event has no `data` field; op, ok and result/error are at the top level.

32. **`recording.start` and `recording.stop`:** do they answer `{"accepted":false}` or the error
    `busy` when the scanner cannot accept? What is the difference?

   **Answer:** The spec says they answer `{"accepted":bool,"state":...}`, or the error `busy`. The difference between `accepted:false` and `busy`: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Provisional: `accepted:false` = nothing to do (start while recording, stop while idle); `busy` = not now (start while still saving).

   **Scanner answer (2026-11-03):** Binding. No arguments. The answer comes at once with the state the command saw: start from idle or error → `accepted:true`, then state events (preparing → recording, or error with a `sensor_failed` warning); start while preparing or recording → `accepted:false`; start while finalizing → error `busy`. Stop while preparing → `accepted:true`, the start-up is abandoned, no files, then idle; stop while recording → finalizing, then idle; stop while idle, error or finalizing → `accepted:false`. No `result` event follows. `hello` lists both commands; an older scanner answers `unknown_command`. Any bonded phone may send them; a stop from the phone and a button press at once save the recording once.

33. **Pairing:** when an unbonded phone writes a command that needs a bond, does it get an ATT
    error (insufficient authentication or encryption) or a protocol error? Do reads stay
    allowed without a bond? How many bonds does the scanner keep, and how are they cleared?
    What does the phone see when the pairing window is closed?

   **Answer:** The spec says these commands need a bonded phone: a separate pairing button opens a window of about 2 minutes (Just Works), and writes then need an encrypted link. The error an unbonded write gets, whether reads stay open, and bond limits: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding (decided 2026-11-02): every request needs the bond. From phone-link-ble on, RX is encrypt-write, so an unbonded phone gets ATT "insufficient encryption" on its first write and Android starts pairing itself. Pairing succeeds only while the scanner's pairing window is open (a pairing button later; today `pairing_at_start` opens it for 120 s when dLidar starts); outside it pairing fails. There is no protocol error "not_paired". A bond allow-list (about 4 phones) and clearing come in phase 3.

34. **Wi-Fi:** what `security` value suits WPA2/WPA3 transition networks? Are hidden SSIDs,
    WPA-Enterprise or WEP out of scope? Which PSK rules apply (8 to 63 characters, or a
    64-character hex key)? How are SSIDs that are not UTF-8 sent? What are the exact names of
    the result values (connected, wrong password, not found, timeout)?

   **Answer:** The spec says `security` is `wpa2-psk`, `wpa3-sae` or `open`, the result is connected, wrong password, not found or timeout, and the scanner never scans for networks itself. Transition networks, hidden SSIDs, enterprise/WEP, PSK rules and SSID encoding: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Provisional: transition networks use `"wpa2-psk"`. Out of scope: hidden SSIDs, Enterprise, WEP, non-UTF-8 SSIDs. PSK: 8-63 printable ASCII or 64 hex, else `bad_request`. Results: `connected`, `wrong_password`, `not_found`, `timeout`, `failed`.

   **Scanner answer (2026-11-03):** Binding (dLidar's docs/phone-protocol.md, "wifi.connect" and "wifi.status"). `wifi.connect {ssid, security, psk}` answers `{"accepted":true,"timeout_s":60}`, then exactly one `result` event with `op`: `ok:true` with `{ssid, ip}` (ip may be null), or `ok:false` with `wrong_password`, `not_found` (may show as `timeout`), `timeout` or `failed` (a specific readable message). Immediate errors: `bad_request` naming the argument (ssid 1-32 bytes, security, psk rules, no psk for open), `busy` while another join runs (any client). `wifi.status` answers `{state, ssid, ip, last}`: state `connected`, `connecting`, `disconnected` or `unavailable`; while a join runs, `connecting` with the target ssid, ip null and `last.result` `"pending"`. A network is saved only when joined, next to the saved ones; the same SSID again replaces its entry (a full join with a short Wi-Fi drop). On failure nothing is saved and the Pi rejoins its previous network. Allowed during a recording. hello lists both commands. Not yet measured: whether BLE survives the join, and how a wrong WPA3 password is reported.

35. **`log.get` paging:** what is the cursor, and what is the page size?

   **Answer:** The spec says only "log.get with paging". Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Not designed yet.

36. **Echo test service:** what is the format of the `info` answer? Does the `flood` SIZE
    count the newline?

   **Answer:** The spec says `info` returns the echo service's state including the MTU, and `flood N SIZE` sends N numbered messages (`seq=00001 xxx…`) of SIZE bytes. The `info` format, and whether SIZE counts the newline: Not in the spec; still open for the scanner side.

   **Scanner answer (2026-11-02):** Binding: `info mode=properties|acquire secure=0|1 mtu=N notifying=0|1 notify_fd=N devices=N`. flood SIZE includes the "\n" (12..4096), N <= 10000.

   **Observed (2026-11-02):** `info` answered `info mode=properties secure=1 mtu=517 notifying=1 notify_fd=-1 devices=1`; the fields are now described in the scanner answer. `flood 200 200` delivered 200/200 messages of 200 B, none missing or out of order, at about 37 KB/s; the app counted 200 B per message, matching the scanner answer that SIZE includes the newline.

37. **Hostname:** can `info.get` report the scanner's host name, as an optional
    `"hostname": "<name>"` field? The app shows it on the Info tab next to the IP address from
    `wifi.status`; until a scanner sends it, the app shows Unknown.

   **Answer:** Not in the spec; asked of the scanner side (2026-11-04).

   **Scanner answer (2026-11-04):** Binding (dLidar's docs/phone-protocol.md, build 5250d33): field `hostname`, a string, the short host name (`gethostname()` up to the first `.`), added after `protocol`. Optional: older builds leave it out, and it is left out when the name cannot be read. Protocol stays 1.

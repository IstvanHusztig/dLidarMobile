# Protocol v1: open questions for the scanner side

Questions the app team found while building milestone 1. Where the app needed an answer to
work, the **current app behaviour** is noted; it is a conservative choice, not a protocol
assumption. Each **Answer** gives what the spec says; where it says nothing, the answer
says so, and that part is still open for the scanner side to answer.

## Framing and limits

1. **Does `max_request_bytes` (4096) count the trailing `\n`?** *App:* counts it, so the
   largest request is 4095 bytes plus the newline.

   **Answer:** The spec says only "a request is at most 4096 bytes". Whether the newline counts: Not in the spec; still open for the scanner side.

2. **Does `max_message_bytes` (16384) count the trailing `\n`?** *App:* accepts 16384 bytes
   plus the newline and drops longer lines.

   **Answer:** The spec gives `max_message_bytes: 16384` in hello's limits, nothing more. Whether the newline counts: Not in the spec; still open for the scanner side.

3. **May the app send several messages in one write, or start a message in the middle of a
   write?** *App:* every request starts in a fresh write, and two requests never share one.

   **Answer:** The spec says the app splits its writes into pieces of at most MTU-3 bytes. Several messages in one write: Not in the spec; still open for the scanner side.

4. **Write vs. write-without-response:** does the scanner handle both the same way? Is there a
   buffer limit, or a need for flow control, when the app writes quickly without response?
   *App:* uses write-with-response.

   **Answer:** The spec says RX accepts both write and write-without-response. Buffer limits and flow control: Not in the spec; still open for the scanner side.

5. **MTU:** the app requests 247, but Android 14 and newer always request 517 for the first
   exchange. Does the scanner cap the MTU at a value of its own, and does it always split by
   the MTU negotiated on this connection? Can the scanner start the MTU exchange itself?

   **Answer:** The spec says the app requests MTU 247 and the scanner splits messages by MTU-3; the echo service's `info` reports the MTU. A cap, and whether the scanner starts the exchange: Not in the spec; still open for the scanner side.

6. **What does the scanner do with an oversized or unreadable line from the app?** The spec
   says `too_large` and "unreadable" arrive as `"id":null`. Does the request that caused it
   ever get its own response? *App:* that request times out after 5 s.

   **Answer:** The spec says too_large and unreadable requests get an unsolicited error with `"id":null`. Whether that request also gets its own response: Not in the spec; still open for the scanner side.

## Requests and responses

7. **Ids:** can the app reuse ids across connections, and after wrapping past 2147483647?
   *App:* counts from 1 on each connection, wraps to 0, and skips ids still pending.

   **Answer:** The spec says ids are integers 0..2147483647. Reuse across connections and wrapping: Not in the spec; still open for the scanner side.

8. **Ordering:** "responses come in request order". Does the scanner process requests one at
   a time? Can a slow command (later: `recording.start`) hold back the responses behind it?
   *App:* matches responses by id and only logs a response that arrives out of order.

   **Answer:** The spec says every request gets exactly one response, responses come in request order, and several requests may be in flight. Whether a slow command holds back later responses: Not in the spec; still open for the scanner side.

9. **Late responses:** can a response arrive after 5 s, for example under load? *App:* a
   response for a request that already timed out is dropped.

   **Answer:** The spec says to use a timeout of about 5 s per request. Whether responses can come later: Not in the spec; still open for the scanner side.

10. **`"v"` field:** is it required on every request, including `hello`? What happens when it
    is missing? *App:* always sends `"v":1`.

   **Answer:** The spec's request format always has `"v":1`, and a wrong version gets `unsupported_version` with `"supported":[1]`. A missing `v`, and `v` on hello: Not in the spec; still open for the scanner side.

11. **`unsupported_version`:** does `supported` list every version the scanner speaks? If the
    scanner supports a newer protocol only, is "update the app" the right message? *App:*
    shows "update the app" whenever the versions differ.

   **Answer:** The spec says `unsupported_version` carries `"supported":[1]` and hello is used to show "update the app" when the protocol does not match. Whether `supported` lists every version: Not in the spec; still open for the scanner side.

12. **`ok:true` with `result` missing:** can that happen for some commands? *App:* treats it as
    an empty result.

   **Answer:** The spec always shows `result` with `ok:true`. Not in the spec; still open for the scanner side.

## Events

13. **`seq` and `dropped` together:** when K events are left out, does `seq` jump by K, is
    `dropped:K` set, or both? *App:* treats either one as a gap and calls `status.get`.

   **Answer:** The spec says `"dropped":K` is present when K events were left out, and either a seq jump or `dropped` means: call status.get. Whether seq also jumps by K: Not in the spec; still open for the scanner side.

14. **First event:** is the first event of each connection always `seq:1`? Is there an event
    right after connecting (for example a `state` event), or only after a change? *App:* a
    first event other than 1 is a gap.

   **Answer:** The spec says seq counts events on this connection from 1, so the first event is seq 1. Whether an event is sent right after connecting: Not in the spec; still open for the scanner side.

15. **Events before notifications are on:** does the scanner hold events until the CCCD is
    written, or are those events lost (and counted in `seq`)?

   **Answer:** Not in the spec; still open for the scanner side.

16. **Warnings are not recoverable:** `status.get` has no list of active warnings, so after a
    reconnect or a gap the app cannot know which warnings still apply. Can `status.get`
    include active warnings, or can warnings be repeated? Is there a "warning cleared" event?

   **Answer:** The spec has no warning list in status.get and no "warning cleared" event, so active warnings cannot be recovered after a reconnect. The request for one stays open.

17. **The summary is not recoverable:** a `summary` event missed during a disconnect is lost.
    Can `status.get` include the last summary, or can there be a command for it?

   **Answer:** The spec sends the summary only as an event at the end of a recording, so a missed summary is lost. The request for a way to fetch it stays open.

18. **`progress` while finalizing:** does it continue? Does `state` repeat chunks and points?

   **Answer:** The spec says progress comes at most once per second. Whether it continues while finalizing: Not in the spec; still open for the scanner side.

19. **`data` missing:** may an event have no `data`? *App:* treats it as empty.

   **Answer:** The spec's event format always has `data`. Not in the spec; still open for the scanner side.

## Field meanings

20. **`location`:** a path, a storage name, or something else? May it be empty?

   **Answer:** The spec says only that `location` is a string. Its meaning: Not in the spec; still open for the scanner side.

21. **`last_ok`:** does it describe the last finished recording? What is it before the first
    recording?

   **Answer:** The spec says only that `last_ok` is a bool. Its meaning: Not in the spec; still open for the scanner side.

22. **`free_bytes: null`:** does it mean "no storage mounted", "unknown", or something else?

   **Answer:** The spec says `free_bytes` is int or null. What null means: Not in the spec; still open for the scanner side.

23. **State `error`:** how does the scanner leave it? Is there a reason field?

   **Answer:** The spec lists `error` as a state. How it is left, and a reason: Not in the spec; still open for the scanner side.

24. **Summary `imu_mode` and `decision`:** what values can they take, and what should a user be
    told about each? *App:* shows them as they are.

   **Answer:** The spec says both are strings. Their values: Not in the spec; still open for the scanner side.

25. **Integer sizes:** can `points`, `imu_in` and the others go beyond 2^53? *App:* reads 64-bit
    integers.

   **Answer:** The spec says `int`. The size: Not in the spec; still open for the scanner side.

## Connection

26. **`too_many_clients`:** when exactly is it sent (on connect, on the first request)? Does
    the scanner then disconnect the second phone? *App:* treats it as "another phone is
    connected", closes its link and does not retry on its own.

   **Answer:** The spec says only one phone can be connected at a time and too_many_clients comes as an unsolicited error with `"id":null`. When it is sent, and whether the scanner then disconnects: Not in the spec; still open for the scanner side.

27. **Idle timeout or keep-alive:** does the scanner drop a connection with no traffic?

   **Answer:** Not in the spec; still open for the scanner side.

28. **Advertising:** how can several scanners be told apart (a serial number in the name,
    manufacturer data)? Is the service UUID in the advertisement or in the scan response?

   **Answer:** The spec says the scanner advertises the name "dLidar" and the service UUID. Telling several scanners apart, and advertisement vs. scan response: Not in the spec; still open for the scanner side.

29. **Address type:** public or random, and does it stay the same across reboots? The app
    reconnects by address, and the bonding plan depends on it.

   **Answer:** Not in the spec; still open for the scanner side.

30. **UUID change:** the UUIDs may change once before v1 is frozen. Will the echo service and
    the real server always share the same UUIDs?

   **Answer:** The spec says the echo service uses the same UUIDs, and the UUIDs may change once before v1 is frozen.

## Later phases

31. **Results that come later:** which event carries `"op"` (one name per command, or one
    shared name)? Is `op` a top-level field or inside `data`? If that event is dropped, how can
    the app get the result (a query by op id)?

   **Answer:** The spec says the command answers `{"accepted":true}` and a later event carries `"op":<the request id>` with the result (used from phase 4 on). The event name, where `op` sits, and recovery when the event is dropped: Not in the spec; still open for the scanner side.

32. **`recording.start` and `recording.stop`:** do they answer `{"accepted":false}` or the error
    `busy` when the scanner cannot accept? What is the difference?

   **Answer:** The spec says they answer `{"accepted":bool,"state":...}`, or the error `busy`. The difference between `accepted:false` and `busy`: Not in the spec; still open for the scanner side.

33. **Pairing:** when an unbonded phone writes a command that needs a bond, does it get an ATT
    error (insufficient authentication or encryption) or a protocol error? Do reads stay
    allowed without a bond? How many bonds does the scanner keep, and how are they cleared?
    What does the phone see when the pairing window is closed?

   **Answer:** The spec says these commands need a bonded phone: a separate pairing button opens a window of about 2 minutes (Just Works), and writes then need an encrypted link. The error an unbonded write gets, whether reads stay open, and bond limits: Not in the spec; still open for the scanner side.

34. **Wi-Fi:** what `security` value suits WPA2/WPA3 transition networks? Are hidden SSIDs,
    WPA-Enterprise or WEP out of scope? Which PSK rules apply (8 to 63 characters, or a
    64-character hex key)? How are SSIDs that are not UTF-8 sent? What are the exact names of
    the result values (connected, wrong password, not found, timeout)?

   **Answer:** The spec says `security` is `wpa2-psk`, `wpa3-sae` or `open`, the result is connected, wrong password, not found or timeout, and the scanner never scans for networks itself. Transition networks, hidden SSIDs, enterprise/WEP, PSK rules and SSID encoding: Not in the spec; still open for the scanner side.

35. **`log.get` paging:** what is the cursor, and what is the page size?

   **Answer:** The spec says only "log.get with paging". Not in the spec; still open for the scanner side.

36. **Echo test service:** what is the format of the `info` answer? Does the `flood` SIZE
    count the newline?

   **Answer:** The spec says `info` returns the echo service's state including the MTU, and `flood N SIZE` sends N numbered messages (`seq=00001 xxx…`) of SIZE bytes. The `info` format, and whether SIZE counts the newline: Not in the spec; still open for the scanner side.


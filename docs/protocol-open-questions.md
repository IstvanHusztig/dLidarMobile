# Protocol v1: open questions for the scanner side

Questions the app team found while building milestone 1. Where the app needed an answer to
work, the **current app behaviour** is noted; it is a conservative choice, not a protocol
assumption. Please answer or correct each one by replacing its _open_ with the answer.

## Framing and limits

1. **Does `max_request_bytes` (4096) count the trailing `\n`?** *App:* counts it, so the
   largest request is 4095 bytes plus the newline.

   **Answer (scanner side):** _open_

2. **Does `max_message_bytes` (16384) count the trailing `\n`?** *App:* accepts 16384 bytes
   plus the newline and drops longer lines.

   **Answer (scanner side):** _open_

3. **May the app send several messages in one write, or start a message in the middle of a
   write?** *App:* every request starts in a fresh write, and two requests never share one.

   **Answer (scanner side):** _open_

4. **Write vs. write-without-response:** does the scanner handle both the same way? Is there a
   buffer limit, or a need for flow control, when the app writes quickly without response?
   *App:* uses write-with-response.

   **Answer (scanner side):** _open_

5. **MTU:** the app requests 247, but Android 14 and newer always request 517 for the first
   exchange. Does the scanner cap the MTU at a value of its own, and does it always split by
   the MTU negotiated on this connection? Can the scanner start the MTU exchange itself?

   **Answer (scanner side):** _open_

6. **What does the scanner do with an oversized or unreadable line from the app?** The spec
   says `too_large` and "unreadable" arrive as `"id":null`. Does the request that caused it
   ever get its own response? *App:* that request times out after 5 s.

   **Answer (scanner side):** _open_

## Requests and responses

7. **Ids:** can the app reuse ids across connections, and after wrapping past 2147483647?
   *App:* counts from 1 on each connection, wraps to 0, and skips ids still pending.

   **Answer (scanner side):** _open_

8. **Ordering:** "responses come in request order". Does the scanner process requests one at
   a time? Can a slow command (later: `recording.start`) hold back the responses behind it?
   *App:* matches responses by id and only logs a response that arrives out of order.

   **Answer (scanner side):** _open_

9. **Late responses:** can a response arrive after 5 s, for example under load? *App:* a
   response for a request that already timed out is dropped.

   **Answer (scanner side):** _open_

10. **`"v"` field:** is it required on every request, including `hello`? What happens when it
    is missing? *App:* always sends `"v":1`.

   **Answer (scanner side):** _open_

11. **`unsupported_version`:** does `supported` list every version the scanner speaks? If the
    scanner supports a newer protocol only, is "update the app" the right message? *App:*
    shows "update the app" whenever the versions differ.

   **Answer (scanner side):** _open_

12. **`ok:true` with `result` missing:** can that happen for some commands? *App:* treats it as
    an empty result.

   **Answer (scanner side):** _open_

## Events

13. **`seq` and `dropped` together:** when K events are left out, does `seq` jump by K, is
    `dropped:K` set, or both? *App:* treats either one as a gap and calls `status.get`.

   **Answer (scanner side):** _open_

14. **First event:** is the first event of each connection always `seq:1`? Is there an event
    right after connecting (for example a `state` event), or only after a change? *App:* a
    first event other than 1 is a gap.

   **Answer (scanner side):** _open_

15. **Events before notifications are on:** does the scanner hold events until the CCCD is
    written, or are those events lost (and counted in `seq`)?

   **Answer (scanner side):** _open_

16. **Warnings are not recoverable:** `status.get` has no list of active warnings, so after a
    reconnect or a gap the app cannot know which warnings still apply. Can `status.get`
    include active warnings, or can warnings be repeated? Is there a "warning cleared" event?

   **Answer (scanner side):** _open_

17. **The summary is not recoverable:** a `summary` event missed during a disconnect is lost.
    Can `status.get` include the last summary, or can there be a command for it?

   **Answer (scanner side):** _open_

18. **`progress` while finalizing:** does it continue? Does `state` repeat chunks and points?

   **Answer (scanner side):** _open_

19. **`data` missing:** may an event have no `data`? *App:* treats it as empty.

   **Answer (scanner side):** _open_

## Field meanings

20. **`location`:** a path, a storage name, or something else? May it be empty?

   **Answer (scanner side):** _open_

21. **`last_ok`:** does it describe the last finished recording? What is it before the first
    recording?

   **Answer (scanner side):** _open_

22. **`free_bytes: null`:** does it mean "no storage mounted", "unknown", or something else?

   **Answer (scanner side):** _open_

23. **State `error`:** how does the scanner leave it? Is there a reason field?

   **Answer (scanner side):** _open_

24. **Summary `imu_mode` and `decision`:** what values can they take, and what should a user be
    told about each? *App:* shows them as they are.

   **Answer (scanner side):** _open_

25. **Integer sizes:** can `points`, `imu_in` and the others go beyond 2^53? *App:* reads 64-bit
    integers.

   **Answer (scanner side):** _open_

## Connection

26. **`too_many_clients`:** when exactly is it sent (on connect, on the first request)? Does
    the scanner then disconnect the second phone? *App:* treats it as "another phone is
    connected", closes its link and does not retry on its own.

   **Answer (scanner side):** _open_

27. **Idle timeout or keep-alive:** does the scanner drop a connection with no traffic?

   **Answer (scanner side):** _open_

28. **Advertising:** how can several scanners be told apart (a serial number in the name,
    manufacturer data)? Is the service UUID in the advertisement or in the scan response?

   **Answer (scanner side):** _open_

29. **Address type:** public or random, and does it stay the same across reboots? The app
    reconnects by address, and the bonding plan depends on it.

   **Answer (scanner side):** _open_

30. **UUID change:** the UUIDs may change once before v1 is frozen. Will the echo service and
    the real server always share the same UUIDs?

   **Answer (scanner side):** _open_

## Later phases

31. **Results that come later:** which event carries `"op"` (one name per command, or one
    shared name)? Is `op` a top-level field or inside `data`? If that event is dropped, how can
    the app get the result (a query by op id)?

   **Answer (scanner side):** _open_

32. **`recording.start` and `recording.stop`:** do they answer `{"accepted":false}` or the error
    `busy` when the scanner cannot accept? What is the difference?

   **Answer (scanner side):** _open_

33. **Pairing:** when an unbonded phone writes a command that needs a bond, does it get an ATT
    error (insufficient authentication or encryption) or a protocol error? Do reads stay
    allowed without a bond? How many bonds does the scanner keep, and how are they cleared?
    What does the phone see when the pairing window is closed?

   **Answer (scanner side):** _open_

34. **Wi-Fi:** what `security` value suits WPA2/WPA3 transition networks? Are hidden SSIDs,
    WPA-Enterprise or WEP out of scope? Which PSK rules apply (8 to 63 characters, or a
    64-character hex key)? How are SSIDs that are not UTF-8 sent? What are the exact names of
    the result values (connected, wrong password, not found, timeout)?

   **Answer (scanner side):** _open_

35. **`log.get` paging:** what is the cursor, and what is the page size?

   **Answer (scanner side):** _open_

36. **Echo test service:** what is the format of the `info` answer? Does the `flood` SIZE
    count the newline?

   **Answer (scanner side):** _open_


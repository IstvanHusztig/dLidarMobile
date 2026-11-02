# dLidar Mobile — Architecture

The Android app connects to the dLidar handheld LiDAR scanner over BLE and speaks protocol v1:
newline-framed JSON with requests, responses and events.
This document sets the structure every piece of code follows: **Clean Architecture** layers and
the **SOLID** principles. When code disagrees with this document, either fix the code or change
this document in the same commit.

---

## 1. Layers and the dependency rule

Source dependencies point **inward only**. Inner layers know nothing about outer ones.

```
 ┌──────────────────────────────────────────────────────────────┐
 │ Frameworks & drivers   :infra:ble   :app (composition root)  │
 │ ┌──────────────────────────────────────────────────────────┐ │
 │ │ Presentation           :presentation (Compose + VMs)     │ │
 │ │ Interface adapters     :data:scanner   :data:protocol    │ │
 │ │ ┌──────────────────────────────────────────────────────┐ │ │
 │ │ │ Domain              :domain (entities, ports, UCs)   │ │ │
 │ │ └──────────────────────────────────────────────────────┘ │ │
 │ └──────────────────────────────────────────────────────────┘ │
 └──────────────────────────────────────────────────────────────┘
```

### Modules

| Module | Kind | Contents | May depend on |
|---|---|---|---|
| `:domain` | Kotlin/JVM | Entities, repository/service **ports**, use cases | kotlinx-coroutines-core only |
| `:data:protocol` | Kotlin/JVM | `ByteTransport` port, framing, codec, request dispatcher, event sequencer, session | kotlinx-coroutines, kotlinx-serialization-json |
| `:data:scanner` | Kotlin/JVM | `ScannerRepositoryImpl`, commands, event decoders, DTO→domain mappers, state reducer, reconnect policy | `:domain`, `:data:protocol` |
| `:infra:ble` | Android lib | `AndroidBleTransport`, the `GattConnection` seam, `BleScannerFinder`, `BlePermissions`, `BleUuids` (later the `BondManager` impl) | `:data:protocol`, `:domain` |
| `:infra:wifi` *(later)* | Android lib | `WifiNetworkScanner` impl, scan throttle | `:domain` |
| `:presentation` | Android lib | Compose screens, ViewModels, UI models | `:domain` **only** |
| `:debug:linktest` | Android lib, debug builds only | Raw-line console for the scanner's echo test service | `:data:protocol` |
| `:debug:traffic` | Android lib, debug builds only | In-memory log of protocol messages and link changes, with a screen | `:data:protocol` |
| `:app` | Android app | `Application`, manual DI composition root, navigation, permissions wiring | everything |
| `:testing` | Kotlin/JVM | `FakeScanner : ByteTransport`, `ByteTransportContract` | `:data:protocol`, `:domain` |

`:testing` is a `testImplementation` dependency of `:data:scanner`, `:presentation` and
`:infra:ble`, never a production dependency. Every later port (`BondManager`,
`WifiNetworkScanner`, `LogRepository`) is owned by `:domain`.

Rules:
- `android.*` imports are allowed only in `:infra:*`, `:presentation`, `:debug:*` and `:app`.
- `:data:protocol` knows nothing about the scanner's domain. It moves commands, responses and
  raw events and does not know what "recording" means.
- `:presentation` sees only use cases and domain entities. It never sees DTOs, JSON or BLE.
- The module graph enforces these rules (a forbidden import does not compile). Code review
  checks them too.

Toolchain: Gradle 9.8, AGP 9.5 (with its built-in Kotlin support, so Android modules do not
apply `kotlin-android`), Kotlin 2.4, compileSdk 37, minSdk 26, Java 17 bytecode.

---

## 2. Domain (`:domain`)

### Entities (immutable `data class` / `sealed interface`)
- `ScannerSnapshot`: the whole picture the UI shows. It holds `target`, `connection`,
  `compatibility`, `status`, `freeBytes`, `info`, `warnings` and `lastSummary`. Values stay after
  the link drops; `connection` says whether they are live.
- `ConnectionState`: `Disconnected`, `Connecting(attempt)`, `Connected`,
  `WaitingToRetry(nextAttempt, retryInMillis)`, `WaitingForBluetooth` (reconnects once Bluetooth
  is on), `Refused(reason)` (`too_many_clients`: another phone is connected) and
  `Failed(reason)` (permission missing, not a dLidar). The app does not retry on its own after
  `Refused` or `Failed`.
- `RecordingState`: `Idle`, `Preparing`, `Recording`, `Finalizing`, `Error`, `Unknown(raw)`.
- `ScannerStatus`: `state`, `chunks: Long`, `points: Long`, `lastOk`, `location`.
- `DeviceInfo`: `version`, `sensor: SensorType` (`UnitreeL2`, `LivoxMid360s`, `Unknown(raw)`),
  `sensorModel`, `recordingFormat`, `protocol`.
- `Warning`: `id` (assigned by the app), `kind: WarningKind` (known kinds plus `Unknown(raw)`),
  `text` (shown verbatim), `receivedAt`.
- `RecordingSummary`: `imuMode`, `decision`, `imuIn`, `imuOut`, `lostSamples`, `receivedAt`.
  `imuMode` and `decision` stay strings because their values are not documented.
- `Compatibility`: `Unknown`, `Compatible`, `UpdateAppRequired(serverProtocol)`.
- `ScannerCandidate`: `id` (an opaque handle), `name`, `rssi`.
- `Discovery`: `Found(candidates)` or `Failed(problem)`.

Every enum that comes from the scanner has an `Unknown(raw)` variant. That is how unknown values
are treated as generic ones.

### Ports (interfaces owned by the domain)
```kotlin
fun interface ScannerFinder { fun find(): Flow<Discovery> }
interface ScannerRepository {
    val snapshot: StateFlow<ScannerSnapshot>
    fun connect(candidate: ScannerCandidate)        // starts the lifecycle; progress shows in snapshot
    fun disconnect()                                // user intent; stops reconnecting
    suspend fun refreshStatus(): Outcome<Unit>      // request/response, so it returns an Outcome
    fun dismissWarning(warningId: Long)             // local state only; cannot fail
}
fun interface Clock { fun now(): Instant }
```
`connect` and `disconnect` only express intent. The connection lifecycle runs in a
`CoroutineScope` that `:app` injects into the repository, and lives as long as the application.

### Use cases (one class each, an `operator fun invoke`, no Android, no I/O details)
`FindScanners`, `ConnectScanner`, `DisconnectScanner`, `ObserveScanner`, `RefreshStatus`,
`DismissWarning`. They are thin today and share one file. Rules that belong to a use case go
here, not into ViewModels or repositories.
Later: `StartRecording`, `StopRecording`, `PairScanner`, `ScanWifiNetworks`, `ConfigureWifi`,
`ReadLog`.

### Error model
```kotlin
sealed interface Outcome<out T> { Ok(value); Failed(error: DomainError) }
sealed interface DomainError {
    NotConnected; LinkLost; Timeout; RequestTooLarge; Busy; InvalidResponse
    IncompatibleProtocol    // after "update the app": nothing is sent
    Remote(code, message)   // ok:false; unknown codes land here too, as generic errors
}
```
Unsolicited `id:null` errors are not results of a call. They change the snapshot (for example
`too_many_clients` → `ConnectionState.Refused`) or are logged.
Exceptions do not cross a layer boundary. Each layer turns its own failures into sealed results.
`CancellationException` is never swallowed.

---

## 3. Protocol layer (`:data:protocol`)

### Port to the transport (ISP: the smallest surface the layer needs)
```kotlin
interface ByteTransport {
    val link: StateFlow<LinkState>                  // Idle | Connecting | Up(connection) | Down(reason)
    suspend fun connect(target: String): ConnectResult   // one attempt; retrying is the caller's choice
    suspend fun disconnect()
}
interface ByteConnection {                          // valid while link is Up with it
    val maxWritePiece: Int                          // MTU - 3
    val incoming: Flow<ByteArray>                   // notifications in order; completes when the link ends
    suspend fun write(piece: ByteArray): WriteResult
}
```
The transport reports `Up` only after the MTU is negotiated **and** TX notifications are
enabled, so the protocol layer cannot write too early. Each connection has its own `incoming`,
so bytes from an old connection never reach a new session. A failed write fails the request
that sent it.

`ProtocolLimits` is the single place for `MAX_REQUEST_BYTES = 4096` (newline included),
`MAX_MESSAGE_BYTES = 16384` and `REQUEST_TIMEOUT = 5 s`. When `hello` reports `limits`, those
values replace the defaults for that session.

### Components (SRP: one reason to change each)
| Class | Responsibility |
|---|---|
| `FrameDecoder` | Collects bytes up to `\n` and decodes UTF-8 only once a line is complete. A line longer than the limit is reported as `Oversized` and dropped up to its `\n`; invalid UTF-8 is reported as `Malformed`. It does not rely on each message starting in a fresh notification, so it also handles several messages in one piece. |
| `FrameSplitter` | Turns one message plus `\n` into pieces of at most `maxWritePiece` bytes. Refuses a request over the limit before anything is sent. |
| `MessageCodec` | Line ↔ `Inbound` (`Response`, `UnsolicitedError`, `Event`, `Unparsed`) and request → line. Ignores unknown fields. Lines from the echo service become `Unparsed`. |
| `RequestDispatcher` | Ids within 0..2147483647, wrapping and skipping ids still pending; the table of pending calls; matching by `id`; failing everything when the link ends. Thread-safe. |
| `EventSequencer` | Tracks `seq` on one connection and reports a `SequenceGap` when `seq` is not last+1, is missing, or `dropped` is present. |
| `DefaultProtocolSession : ProtocolSession` | One connection, from `Up` to `Down`. One reader coroutine decodes everything in arrival order; a write mutex keeps the pieces of two requests from interleaving; each call has its timeout. Once a request's first piece is out, the rest is written even if the caller is cancelled; if a later piece fails, the session reports `StreamBroken`, because the scanner now holds a partial line, and the link is reopened. A cancelled call forgets its id. `hasReceivedMessages` and `awaitDrained()` let the repository judge how a link ended. Created by `ProtocolSessionFactory`, which is injected into the repository. |

*App choices, listed in the open questions:* a response that arrives after its timeout is
logged and dropped; a response out of request order is matched by id and logged.

### Ordering guarantee
Events go to a `SessionListener` and call results to an optional `onAnswer` callback. Both run
on the reader, in stream order and without suspending; only `StreamBroken` comes from the
writing call instead. The repository updates its state there,
so an older `status.get` result can never overwrite a newer `state` event.

### Typed commands (OCP)
```kotlin
interface ProtocolCommand<out R> {
    val name: String
    val sensitiveArgs: Set<String> get() = emptySet()   // redacted in logs, e.g. "psk"
    fun args(): JsonObject?
    fun decode(result: JsonObject): R
}
```
To add a command you add a new `ProtocolCommand` (today `HelloCommand`, `StatusGetCommand` and
`InfoGetCommand` in `:data:scanner`; later `RecordingStart`). The session, the codec and the
dispatcher do not change. A command sends exactly the args the scanner documents.
Events reach the listener as raw `ProtocolEvent`s; decoding them is a scanner concern (§4).

### Concurrency
- `RequestDispatcher` is synchronized; the decoder and the sequencer belong to the reader
  coroutine alone; writes go through a mutex. No other state is shared.
- Structured concurrency: a session's job is a child of the repository's per-connection scope,
  and cancelling that scope cancels everything belonging to the link.
- Timeouts and delays use the coroutine clock, so tests run in virtual time with `runTest`.

---

## 4. Scanner data layer (`:data:scanner`)

| Class | Responsibility |
|---|---|
| `ScannerRepositoryImpl : ScannerRepository` | Orchestrates the connection lifecycle. Each `connect()` starts a `Run`, which connects, runs sessions and reconnects. A run that is no longer current cannot change the snapshot, and a new run cancels every unfinished run at once and waits for all their cleanups. The session goes live for `refreshStatus()` only after `hello` allowed further requests. A session that ended with its link still up (a broken stream) releases the link before the backoff. When the link drops, it lets the reader finish (up to 1 s), so a `too_many_clients` sent just before the drop still counts as a refusal. Backoff starts over only after a link on which the scanner answered. After a protocol mismatch, `refreshStatus()` fails with `IncompatibleProtocol`. |
| `SessionHandshake` | Every link up: `hello` first, because its answer decides compatibility, then `status.get` (through the refresher) and `info.get` together. If `hello` returns `unknown_command` or times out, compatibility is `Unknown` and the rest still goes out, because `hello` is optional. On `unsupported_version` or a protocol other than 1, nothing more is sent. |
| `SessionSignalHandler` | Routes a session's signals: events become inputs through the decoders, gaps a refresh; `too_many_clients` and `StreamBroken` end the session. |
| `CallRetry` | *App policy:* `status.get` and `info.get` must get through on every connection, so a timeout or `busy` is retried after 1, 2, 4, 8 s. Other failures are not, because the answer would not change. |
| `ScannerEventDecoders` | Registry of `ScannerEventDecoder`s (`state`, `warning`, `progress`, `summary`). A new event is a new decoder (OCP). Unknown events are ignored, and events whose data does not fit are logged. |
| `ScannerStateReducer` | A pure function `(ScannerSnapshot, ScannerInput) -> ScannerSnapshot`. Inputs carry their own time, so the reducer needs no clock. *App choices:* a `state` event keeps the last `freeBytes`; `progress` before any status is dropped; warnings are capped at the newest 20; a different scanner starts from an empty snapshot. |
| `ProtocolMappers` | DTO → domain entity, including the `Unknown(raw)` mapping, and `CallFailure` → `DomainError`. |
| `StatusRefresher` | Merges refresh requests: at most one `status.get` in flight plus one queued; repeats it per `CallRetry`. |
| `ReconnectPolicy` | *App policy, not protocol:* backoff of 1, 2, 4 … 30 s. While Bluetooth is off it checks every 3 s (a local check). It does not retry when the user disconnected, a permission is missing, or the device is not a dLidar. |
| `CompatibilityChecker` | Turns the `hello` result into a `Compatibility`. `hello` is read leniently (only `protocol` and `limits`), so a hello whose other fields changed shape still reveals a mismatch. |

---

## 5. BLE infrastructure (`:infra:ble`)

- `BleUuids` is the only place the provisional service, RX and TX UUIDs (and the CCCD) are
  written down.
- `AndroidBleTransport : ByteTransport` runs the connection sequence: connect → discover
  services → check for the service with RX and TX → `requestMtu(247)`, using the negotiated
  value (or the default 23 if the request fails) → enable notifications and write the CCCD →
  `Up(mtu - 3)`. Connecting and setup each time out after 20 s. A connect that is cancelled
  or fails during setup closes its GATT connection.
- *Known limitation:* the switch to `WaitingForBluetooth` relies on Android reporting the link
  loss when the adapter is turned off. A device that skips that callback would stay
  `Connected` until a request fails. An adapter-state receiver can be added if that shows up
  in practice.
- Closing disconnects first and waits up to 2 s for the link to go down before releasing the
  client. Closing at once can leave the link up on the scanner, which would then refuse the
  phone's next connection.
- `GattConnection` is the seam between that sequence and Android: suspend operations, one at a
  time. `AndroidGattConnection` implements it over `BluetoothGatt`. An operation mutex keeps one
  GATT operation outstanding, and each callback completes only the operation of its own kind
  (so an MTU change the scanner starts cannot complete a write). It covers both the API 33+ and
  the older write and notification APIs. Notifications go into an unlimited channel so they
  stay in order.
- Writes use write-with-response, so GATT does the flow control (an app choice; open
  question 4).
- `BleScannerFinder : ScannerFinder` scans with filters on the service UUID or the name
  `dLidar`, and reports missing permissions, Bluetooth off and location off (Android 11 and
  older) as problems.
- `BlePermissions` gives the runtime permissions for each API level. `:app` asks the user.

---

## 6. Presentation (`:presentation`)

- MVVM with unidirectional data flow. A ViewModel exposes `StateFlow<UiState>` and takes
  `onAction(Action)`.
- ViewModels get their use cases through the constructor and depend on nothing else.
- Screens are stateless composables `(UiState, (Action) -> Unit)`, previewable and testable.
  Text comes from string resources; numbers go through the pure `Formatters`.
- Screens: `ConnectScreen` and `StatusScreen`. Fixing a problem (permissions, turning on
  Bluetooth, location settings) needs the Activity, so the screen hands it to `:app`.
- The echo test service (`anything`, `info`, `flood N SIZE`) does not speak protocol v1. Its
  console lives in `:debug:linktest`, which uses `ByteTransport`, `FrameSplitter` and
  `FrameDecoder` directly and is left out of release builds.
- The protocol traffic log lives in `:debug:traffic`. `DefaultProtocolSession` reports every
  complete message to a `TrafficListener` port (sent lines with sensitive args already
  redacted, received lines, dropped messages); `TrafficLog` keeps the last 1000 in memory,
  together with the link changes. `StatusScreen` shows a **Log** button only when `:app`
  passes a handler, which it does in debug builds.

---

## 7. Composition root (`:app`)

Manual dependency injection in `AppContainer`, with no DI framework. This is the only place
that creates concrete classes and connects them to their ports. `AppRoot` does the navigation
and the Android-only work (permission requests, system settings). The connection outlives the
Activity, so after recreation the app returns to the status screen instead of starting a new
search. `DebugTools` has a debug version (the link test and the traffic log, whose
`TrafficListener` goes into `DefaultProtocolSessionFactory`) and a release version
(`TrafficListener.None` and nothing else), selected by source set.

---

## 8. SOLID in this codebase

| Principle | How it is applied |
|---|---|
| **S** (single responsibility) | Every class in the tables above has one reason to change. Framing, id matching, seq tracking, event decoding, mapping and reducing are all separate. |
| **O** (open/closed) | New commands are new `ProtocolCommand`s; new events are new `ScannerEventDecoder`s; new use cases do not change existing ones. |
| **L** (Liskov substitution) | `AndroidBleTransport` and `FakeScanner` pass the same `ByteTransportContract` tests. |
| **I** (interface segregation) | Ports stay small: `ByteTransport` and `ByteConnection`, `ScannerFinder`, `Clock`. `CommandSender` (only `call`) is split out of `ProtocolSession` (start, limits, close), so code that only sends commands needs nothing more. The session's listener is a single-method `SessionListener`. |
| **D** (dependency inversion) | The domain owns its ports and the protocol layer owns `ByteTransport`. Outer layers implement them, and wiring happens only in `:app`. |

---

## 9. Data flow

```
Receive (one reader coroutine per connection, in arrival order):
  GATT notify ─► ByteConnection.incoming ─► FrameDecoder ─► MessageCodec
     ├─ Response ─────────► RequestDispatcher ─► onAnswer (state update) ─► call() returns
     ├─ Event ────────────► SessionListener ─► ScannerEventDecoders ─► ScannerStateReducer
     │                  └─► EventSequencer ─► gap ─► StatusRefresher ─► status.get
     ├─ UnsolicitedError ─► SessionListener (too_many_clients ─► Refused)
     └─ Unparsed ─────────► log
  StateFlow<ScannerSnapshot> ─► ObserveScanner ─► StatusViewModel ─► StatusScreen

Send:
  Action ─► ViewModel ─► use case ─► repository ─► ProtocolSession.call(command)
     ─► RequestDispatcher (id) ─► MessageCodec ─► FrameSplitter ─► ByteConnection.write()
```

---

## 10. Cross-cutting rules

- **Compatibility:** ignore unknown fields and events; map unknown enum values and error codes
  to generic variants; send only documented args.
- **Logging:** behind the `Logger` port. Args named in `ProtocolCommand.sensitiveArgs` are
  redacted before a request is logged; the codec has no list of secret names of its own. Debug
  messages, which may hold raw payloads, are written only in debug builds. The debug traffic
  log is a second channel: sent lines are redacted the same way, received lines are kept as
  they arrive.
- **Secrets:** passwords live only in memory (`CharArray`, cleared after the request is sent).
  Domain types override `toString` to hide them. They are never written to storage
  unencrypted.
- **Immutability:** state is exposed as a `StateFlow` of immutable values.
- **No protocol behaviour is invented.** Anything the spec leaves open goes into
  `docs/protocol-open-questions.md` and is handled conservatively in code.

---

## 11. Planned extensions (shape only, not built yet)

- **Results that arrive later (`op`):** `PendingOperations` in `:data:scanner` links an
  `{"accepted":true}` response to the later event that carries `"op": <id>`
  (`ProtocolEvent.raw` keeps that field reachable), and returns the result to the use case.
- **Pairing and bonds:** see [docs/design/pairing.md](docs/design/pairing.md).
- **Wi-Fi setup:** see [docs/design/wifi-setup.md](docs/design/wifi-setup.md).
- **`log.get` paging:** a `LogRepository` port with a paged `Flow`.

---

## 12. Testing strategy

| Layer | Tests |
|---|---|
| `:data:protocol` | Framing (split pieces, merged pieces, a multibyte character split across pieces, oversized lines, invalid UTF-8), codec (unknown fields, `id:null`, events, non-protocol lines), dispatcher (ids, wrapping, order), sequencer (jumps, `dropped`, missing seq), session (matching, several requests in flight, timeouts, late responses, error codes, link drop, size limit, `onAnswer` before later events). |
| `:data:scanner` | Mappers (unknown enums), compatibility, reconnect policy, event decoders, reducer, and the repository against `FakeScanner`: `status.get` after every connect and on gaps, merged refreshes, retried first fetches, reconnects and backoff, waiting for Bluetooth, `too_many_clients` (also just before a drop), update-app and a hello of unexpected shape, quick connect/disconnect sequences, unknown fields and values, and a whole recording (preparing → recording → progress → finalizing → idle → summary) at a small MTU. |
| `:testing` / `:infra:ble` | `ByteTransportContract` runs against `FakeScanner` and against `AndroidBleTransport` with a `FakeGattConnection`; the BLE setup order and its failures are tested on the JVM. `AndroidGattConnection` itself needs a device. |
| `:presentation` | `StatusViewModel` on the real repository and protocol stack against `FakeScanner`; `ConnectViewModel` with a fake finder; `StatusScreen` with Compose UI tests under Robolectric; `Formatters`. |
| `:debug:linktest` | `FloodMeter`. |
| `:debug:traffic` | `TrafficLog` (order, capacity, link changes, export limit). |

Every test runs on the JVM without a device. `FakeScanner` also records protocol violations by
the app (pieces over MTU-3, missing `"v":1`, undocumented args), and the repository tests
assert there are none.

---

## 13. Conventions

- Packages are `com.dlidar.mobile.<module>.<feature>`.
- Ports are named after the capability (`ScannerFinder`); implementations have a technology
  prefix (`BleScannerFinder`, `AndroidBleTransport`) or an `Impl`/`Default` prefix or suffix.
- DTOs carry a `Dto` suffix and stay in `:data:*`.
- One public top-level type per file, except sealed families and groups of tiny related types
  (the use cases, the DTOs, the v1 commands). `internal` is the default outside of ports.
- Every change goes through the master review (a Sonnet 5.5 reviewer) before it is pushed to
  `master`.

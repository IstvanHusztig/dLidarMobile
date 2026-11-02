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
| `:data:scanner` | Kotlin/JVM | `ScannerRepositoryImpl`, DTO→domain mappers, state reducer, reconnect policy | `:domain`, `:data:protocol` |
| `:infra:ble` | Android lib | `AndroidBleTransport`, GATT op queue, `BleScannerFinder`, `BleUuids` (later `BondManager` impl) | `:data:protocol`, `:domain` |
| `:infra:wifi` *(later)* | Android lib | `WifiNetworkScanner` impl, scan throttle | `:domain` |
| `:presentation` | Android lib | Compose screens, ViewModels, UI models | `:domain` **only** |
| `:debug:linktest` | Android lib, debug builds only | Raw-line console for the scanner's echo test service | `:data:protocol` |
| `:app` | Android app | `Application`, manual DI composition root, manifest, permissions wiring | everything |
| `:testing` | Kotlin/JVM | `FakeScanner : ByteTransport`, fixtures, fake clock | `:data:protocol`, `:domain` |

`:testing` is a `testImplementation` dependency of `:data:*`, `:presentation` and `:infra:ble`, never a
production dependency. Every later port (`BondManager`, `WifiNetworkScanner`, `LogRepository`) is
owned by `:domain`.

Rules:
- `android.*` imports are allowed only in `:infra:*`, `:presentation` and `:app`.
- `:data:protocol` knows nothing about the scanner's domain. It moves typed commands and events
  and does not know what "recording" means.
- `:presentation` sees only use cases and domain entities. It never sees DTOs, JSON or BLE.
- The module graph enforces these rules (a forbidden import does not compile). Code review checks them too.

---

## 2. Domain (`:domain`)

### Entities (immutable `data class` / `sealed interface`)
- `ScannerSnapshot`: the whole picture the UI shows. It holds `connection`, `status`, `info`,
  `freeBytes`, `warnings`, `lastSummary` and `compatibility`.
- `ConnectionState`: `Disconnected`, `Searching`, `Connecting`, `Connected`,
  `Reconnecting(attempt)`, `Refused(reason)` (for example `too_many_clients`: another phone is connected) and `Failed(reason)`.
- `RecordingState`: `Idle`, `Preparing`, `Recording`, `Finalizing`, `Error`, `Unknown(raw)`.
- `ScannerStatus`: `state`, `chunks: Long`, `points: Long`, `lastOk`, `location`.
- `DeviceInfo`: `version`, `sensor: SensorType` (`UnitreeL2`, `LivoxMid360s`, `Unknown(raw)`),
  `sensorModel`, `recordingFormat`, `protocol`.
- `Warning`: `kind: WarningKind` (known kinds plus `Unknown(raw)`), `text` (shown verbatim), `receivedAt`.
- `RecordingSummary`: `imuMode`, `decision`, `imuIn`, `imuOut`, `lostSamples`. `imuMode` and
  `decision` stay strings because their sets of values are not documented.
- `Compatibility`: `Compatible`, `UpdateAppRequired(serverProtocol)`, `Unknown`.
- `ScannerCandidate`: `id` (an opaque handle), `name`, `rssi`.

Every enum that comes from the scanner has an `Unknown(raw)` variant. That is how unknown values
are treated as generic ones.

### Ports (interfaces owned by the domain)
```kotlin
interface ScannerFinder      { fun find(): Flow<List<ScannerCandidate>> }
interface ScannerRepository  {
    val snapshot: StateFlow<ScannerSnapshot>
    fun connect(candidate: ScannerCandidate)        // starts the lifecycle, progress shows in snapshot
    fun disconnect()                                // user intent, stops auto-reconnect
    suspend fun refreshStatus(): Outcome<Unit>      // request/response, so it returns an Outcome
    fun dismissWarning(warning: Warning)            // local UI state only, cannot fail
}
interface Clock { fun now(): Instant }
```
`connect` and `disconnect` only express intent; the connection lifecycle runs in a
`CoroutineScope` that `:app` injects into the repository (application lifetime).

### Use cases (one public `operator fun invoke` each, no Android, no I/O details)
`FindScanners`, `ConnectScanner`, `DisconnectScanner`, `ObserveScanner`, `RefreshStatus`,
`DismissWarning`.
Later: `StartRecording`, `StopRecording`, `PairScanner`, `ScanWifiNetworks`, `ConfigureWifi`,
`ReadLog`.

### Error model
```kotlin
sealed interface Outcome<out T> { data class Ok<T>(val value: T); data class Failed(val error: DomainError) }
sealed interface DomainError {
    NotConnected; LinkLost; Timeout; RequestTooLarge; Busy
    Remote(code, message)          // ok:false with a known code
    Unknown(code, message)         // ok:false with an unknown code
}
```
Unsolicited `id:null` errors are not results of a call. They change the `ScannerSnapshot`
(for example `too_many_clients` → `ConnectionState.Refused`) or are logged.
Exceptions do not cross a layer boundary. Each layer turns its own failures into sealed results.
`CancellationException` is always rethrown.

---

## 3. Protocol layer (`:data:protocol`)

### Port to the transport (ISP: the smallest surface the layer needs)
```kotlin
interface ByteTransport {
    val link: StateFlow<LinkState>          // Down | Connecting | Up(maxWritePiece = MTU-3)
    val incoming: Flow<ByteArray>           // notification payloads, in arrival order
    suspend fun write(piece: ByteArray): WriteResult   // Ok | Failed(reason); one GATT write, size <= maxWritePiece
    suspend fun open(target: String); suspend fun close()
}
```
The transport reports `Up` only after the MTU is negotiated **and** TX notifications are enabled.
That way the protocol layer cannot write too early. A failed write fails the request that sent it.
A lost link is reported only through `link`.

`ProtocolLimits` is the single place for `MAX_REQUEST_BYTES = 4096`, `MAX_MESSAGE_BYTES = 16384`
and `REQUEST_TIMEOUT = 5 s`. When `hello` reports `limits`, those values replace the defaults for
that session.

### Components (SRP: one reason to change each)
| Class | Responsibility |
|---|---|
| `FrameDecoder` | Collects bytes up to `\n` and decodes UTF-8 only once a line is complete. If a line grows past `max_message_bytes` it reports `Oversized` and throws away bytes until the next `\n`. `reset()` clears it. The decoder does not rely on each message starting in a fresh notification, so it also handles several messages merged into one piece. |
| `FrameSplitter` | Turns one encoded message plus `\n` into pieces of at most `maxWritePiece` bytes. Refuses a request over 4096 bytes before it is sent. |
| `MessageCodec` | Line ↔ `Inbound` (`Response`, `UnsolicitedError`, `Event`, `Unparsed(line)`) and `Request` → line. Ignores unknown fields. |
| `RequestDispatcher` | Assigns ids within 0..2147483647, wrapping and skipping ids that are still pending, and keeps a table of pending requests. Matches each response by `id`, applies the per-request timeout, and fails every pending request when the link drops. *App choices, listed in the open questions:* a response that arrives late is logged and dropped; a response out of request order is accepted by id and logged as a protocol violation. |
| `EventSequencer` | Tracks `seq` for each connection and reports `Gap` when `seq` is not last+1 or when `dropped` is present. |
| `ProtocolSession` | Exists for one connection, from `Up` until `Down`. It owns one decoder, dispatcher and sequencer. A new link gets a new session, so no state carries over from the previous one. Sessions are created by `ProtocolSessionFactory`, which is injected into the repository. |
| `ProtocolClient` | The surface the outer layers use. It implements two small interfaces: `CommandSender` (`call(command)`) and `EventSource` (`events`, `gaps`, `unsolicitedErrors`). `Unparsed` lines are only logged. |

### Typed commands and events (OCP)
```kotlin
interface ProtocolCommand<R> {
    val name: String
    val sensitiveArgs: Set<String> get() = emptySet()   // redacted in logs, e.g. "psk"
    fun args(): JsonObject?
    fun decode(result: JsonObject): R
}
interface EventDecoder<E>    { val name: String; fun decode(data: JsonObject): E }
```
To add a command you add a new `ProtocolCommand` class (`Hello`, `StatusGet`, `InfoGet`, and
later `RecordingStart`). To add an event you register a new `EventDecoder`. You do not edit the
dispatcher, the codec or the session. Unknown events reach `events` as `ProtocolEvent.Unknown`,
and consumers ignore them.

A command sends exactly the args the scanner documents. If args are needed, the command class
declares them; nothing else is sent.

### Concurrency
- Each session runs on a single-threaded dispatcher (`Dispatchers.Default.limitedParallelism(1)`),
  so the pending table and the sequencer need no locks.
- Structured concurrency: a session's scope is a child of the repository scope, and cancelling it
  cancels everything that belongs to that link.
- Timeouts and delays use the coroutine clock, so tests can run in virtual time with `runTest`.

---

## 4. Scanner data layer (`:data:scanner`)

| Class | Responsibility |
|---|---|
| `ScannerRepositoryImpl : ScannerRepository` | Runs the connection lifecycle. On every `Up` it first calls `hello` because the answer decides compatibility. It then sends `info.get` and `status.get` together, both in flight. If `hello` returns `unknown_command`, compatibility becomes `Unknown` and the other calls still go out, because `hello` is optional. On `unsupported_version` or a protocol other than 1, the app stops and shows "update the app". It calls `status.get` on every gap. |
| `ScannerStateReducer` | A pure function `(ScannerSnapshot, ScannerInput) -> ScannerSnapshot`. Inputs carry their `receivedAt`, so the reducer does not need a clock. *App choices:* a `state` event updates the status but keeps the last `freeBytes`; `progress` updates the counts; `warning` appends to a list capped at 20 items; `summary` replaces the last summary. |
| `ProtocolMappers` | DTO → domain entity, including `Unknown(raw)` mapping. |
| `StatusRefresher` | Merges refresh requests: at most one `status.get` in flight plus one queued. |
| `ReconnectPolicy` | *App policy, not part of the protocol:* exponential backoff from 1 s to 30 s. It stops on a user disconnect. After `too_many_clients` (a documented error code) it waits for the user to retry instead of retrying on its own. |
| `CompatibilityChecker` | Reads the `hello` result, `unsupported_version` and `unknown_command` and produces a `Compatibility`. |

---

## 5. BLE infrastructure (`:infra:ble`)

- `BleUuids` is the only place the provisional service, RX and TX UUIDs (and the CCCD) are written down.
- `AndroidBleTransport : ByteTransport` connects in this order: `connectGatt(autoConnect=false,
  TRANSPORT_LE)` → discover services → `requestMtu(247)`, using the value from `onMtuChanged` →
  enable notifications and write the CCCD → `Up(mtu - 3)`.
- `GattOperationQueue` runs one GATT operation at a time, each with its own completion. It covers
  both the API 33+ callback signatures and the older ones.
- Notifications go into an unlimited channel so they stay in order.
- Writes use write-with-response by default, so GATT does the flow control. This is an app
  choice; the scanner accepts both write types. GATT errors map to `WriteResult.Failed` or to
  `LinkState.Down(reason)`.
- `BleScannerFinder : ScannerFinder` scans with a filter on the service UUID and checks the name `dLidar`.
- `BlePermissions` gives the list of required runtime permissions for each API level. `:app`
  asks the user for them.

---

## 6. Presentation (`:presentation`)

- MVVM with unidirectional data flow. A ViewModel exposes `StateFlow<UiState>` and takes
  `onAction(UiAction)`.
- ViewModels get their use cases through the constructor and depend on nothing else.
- Screens are stateless composables `(UiState, (UiAction) -> Unit)`, previewable and testable.
- Screens: `ConnectScreen` and `StatusScreen`.
- The echo test service (`anything`, `info`, `flood N SIZE`) does not speak protocol v1. Its
  console lives in `:debug:linktest`, which uses `ByteTransport`, `FrameSplitter` and `FrameDecoder`
  directly and is left out of release builds.

---

## 7. Composition root (`:app`)

Manual dependency injection in `AppContainer`, with no DI framework. This is the only place that
creates concrete classes and connects them to their ports. Debug builds can switch to
`FakeScanner` so the UI runs without hardware.

---

## 8. SOLID in this codebase

| Principle | How it is applied |
|---|---|
| **S** (single responsibility) | Every class in the tables above has one reason to change. Framing, id matching, seq tracking, mapping and reducing are all separate. |
| **O** (open/closed) | New commands and events are added as new `ProtocolCommand` and `EventDecoder` classes. Use cases are added without changing existing ones. |
| **L** (Liskov substitution) | `AndroidBleTransport` and `FakeScanner` pass the same `ByteTransportContract` tests. Every `ScannerRepository` implementation keeps the snapshot contract. |
| **I** (interface segregation) | Ports stay small (`ByteTransport`, `ScannerFinder`, `Clock`). A consumer gets only the part it needs: `CommandSender` for calls, `EventSource` for events. |
| **D** (dependency inversion) | The domain owns its ports and the protocol layer owns `ByteTransport`. Outer layers implement them, and wiring happens only in `:app`. |

---

## 9. Data flow

```
Receive:
  GATT notify ─► AndroidBleTransport.incoming ─► FrameDecoder ─► MessageCodec
     ├─ Response ─────────► RequestDispatcher ─► call() returns
     ├─ Event ────────────► EventSequencer ─► (Gap ─► StatusRefresher ─► status.get)
     │                                      └─► ProtocolClient.events
     ├─ UnsolicitedError ─► ProtocolClient.unsolicitedErrors
     └─ Unparsed ─────────► log
  ScannerRepositoryImpl ─► ScannerStateReducer ─► StateFlow<ScannerSnapshot>
     ─► ObserveScanner ─► StatusViewModel ─► StatusScreen

Send:
  UiAction ─► ViewModel ─► use case ─► repository ─► ProtocolClient.call(command)
     ─► RequestDispatcher (id, timeout) ─► MessageCodec ─► FrameSplitter ─► ByteTransport.write()
```

---

## 10. Cross-cutting rules

- **Compatibility:** ignore unknown fields and events; map unknown enum values and error codes to
  generic variants; send only documented args.
- **Logging:** behind a `Logger` port. Args named in `ProtocolCommand.sensitiveArgs` are
  redacted before a request is logged. The codec has no list of secret names of its own. Raw payloads are logged only in debug builds.
- **Secrets:** passwords live only in memory (`CharArray`, cleared after the request is sent).
  Domain types override `toString` to hide them. They are never written to storage unencrypted.
- **Immutability:** state is exposed as `StateFlow` of immutable values. Collections are never
  shared mutably.
- **No protocol behaviour is invented.** Anything the spec leaves open goes into
  `docs/protocol-open-questions.md` and is handled conservatively in code.

---

## 11. Planned extensions (shape only, not built yet)

- **Results that arrive later (`op`):** `PendingOperations` in `:data:scanner` links an
  `{"accepted":true}` response to the later event that carries `"op": <id>`, and returns a
  `Deferred` to the use case.
- **Pairing and bonds:** a `BondManager` port in `:domain`, implemented in `:infra:ble`
  (`createBond`, `ACTION_BOND_STATE_CHANGED`, insufficient-authentication errors). The use case
  `PairScanner` guides the user to press the scanner's pairing button.
- **Wi-Fi setup:** a new module `:infra:wifi` (`WifiManager` scan, permissions, a throttle of
  4 scans per 2 min) that implements a `WifiNetworkScanner` port. A pure `NetworkListMerger` in
  `:domain` merges SSIDs, keeps the strongest signal and hides hidden SSIDs. A
  `SecurityTypeResolver` maps scan capabilities to `wpa2-psk`, `wpa3-sae` or `open`.
- **`log.get` paging:** a `LogRepository` port with a paged `Flow`.

---

## 12. Testing strategy

| Layer | Tests |
|---|---|
| `:domain` | Use cases with fake ports, and pure-logic tests. |
| `:data:protocol` | Framing (split pieces, merged pieces, a multibyte character split across pieces, oversized lines), dispatcher (matching, several requests in flight, timeouts, late responses, `id:null` errors, link drop), sequencer (gaps, `dropped`), codec (unknown fields, unknown events). |
| `:data:scanner` | Reducer table tests, mappers (unknown enums), repository against `FakeScanner`: `status.get` after every connect and on gaps, compatibility, reconnects, and a whole recording (preparing → recording → progress → finalizing → idle → summary). |
| `:infra:ble` | `ByteTransportContract`, shared with `FakeScanner`, runs against `AndroidBleTransport` on a mocked `BluetoothGatt` under Robolectric. GATT queue ordering is tested with mocked callbacks. |
| `:presentation` | ViewModel tests (JVM) and Compose UI tests under Robolectric. |

Every test runs on the JVM without a device. Time-dependent tests use `runTest` virtual time.

---

## 13. Conventions

- Packages are `com.dlidar.mobile.<module>.<feature>`.
- Ports are named after the capability (`ScannerFinder`); implementations have a technology
  prefix (`BleScannerFinder`) or an `Impl` suffix.
- DTOs carry a `Dto` suffix and stay in `:data:*`.
- One public top-level type per file. `internal` is the default visibility outside of ports.
- Every change goes through the master review (a Sonnet 5.5 reviewer) before it is pushed to `master`.

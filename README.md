# dLidar Mobile

An Android app that connects over Bluetooth LE to the dLidar handheld LiDAR scanner and shows
its status: the recording state, chunks and points, free space, warnings, sensor and version
information, and the summary at the end of a recording. It reconnects on its own.

Kotlin, Jetpack Compose, coroutines and Flow; minSdk 26. The structure is described in
[architecture.md](architecture.md). Questions for the scanner side are in
[docs/protocol-open-questions.md](docs/protocol-open-questions.md).

## Requirements

- Android Studio (2026.2 or newer), or a JDK 17+ together with the Android SDK, platform 37.
  The Gradle wrapper (Gradle 9.8) runs on the JDK bundled with Android Studio.
- A phone with Bluetooth LE (Android 8.0 or newer) to run the app; the tests need no device.

## Build and test

```sh
# Windows: use gradlew.bat. Outside Android Studio, point JAVA_HOME at a JDK, for example
# "C:\Program Files\Android\Android Studio\jbr".
./gradlew test                 # every unit test (JVM, no device)
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug    # install on a connected phone
```

Android Studio creates `local.properties` with `sdk.dir`; on the command line, create it
yourself or set `ANDROID_HOME`.

## Run

1. Switch the scanner on and keep the phone close.
2. Open the app, grant the Bluetooth permission (on Android 11 and older, the location
   permission, and switch location on), and pick the scanner from the list.
3. The status screen updates live. If the link drops, the app reconnects and fetches the
   status again.

Only one phone can be connected at a time. If another phone is connected, the app says so and
waits until you tap Retry.

### Echo test service (debug builds)

Until the full server is ready, the scanner runs an echo service with the same UUIDs. In a
debug build, every scanner in the list has a **Link test** button. It sends raw lines (`text`
comes back as `echo text`, `info` returns the scanner's state), and **flood** runs
`flood N SIZE` and reports lines received, missing, out of order, and the throughput. The link
test uses the BLE link directly, so it runs while the status screen is disconnected.

### Traffic log (debug builds)

The status screen has a **Log** button in debug builds. It lists every protocol message sent
(`->`) and received (`<-`), dropped messages (`!!`) and link changes (`--`), newest at the
bottom. **Share** sends the log as text (the newest 100,000 characters), for example to report
what the real scanner answered. Passwords are redacted before they reach the log, and the log
is kept in memory only.

## Modules

| Module | What it holds |
|---|---|
| `domain` | Entities, ports, use cases (pure Kotlin) |
| `data/protocol` | Framing, codec, request/response matching, timeouts, event seq checks (pure Kotlin) |
| `data/scanner` | The repository: lifecycle, reconnects, state reduction (pure Kotlin) |
| `infra/ble` | The Android BLE transport, scanning, permissions |
| `presentation` | Compose screens and ViewModels |
| `debug/linktest` | The echo-service link test (debug builds only) |
| `debug/traffic` | The protocol traffic log (debug builds only) |
| `testing` | `FakeScanner`, which plays the scanner's side, and the transport contract tests |
| `app` | Composition root and navigation |

The provisional GATT UUIDs are in one place: `infra/ble/.../BleUuids.kt`.

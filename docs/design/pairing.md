# Design: pairing and bonds

The scanner side decided (open question 33) that **every request needs a bonded phone**. From
phone-link-ble on, RX is encrypt-write. An unbonded phone's first write is refused with ATT
"insufficient encryption"; Android then pairs on its own (Just Works) and repeats the write.
Pairing only succeeds while the scanner's pairing window is open: a pairing button later, and
today `pairing_at_start`, which opens it for 120 s when dLidar starts. There is no protocol
error such as `not_paired`. A bond allow-list (about 4 phones) and clearing come in phase 3.

## What is built (milestone 2)

| Layer | Piece |
|---|---|
| `:infra:ble` | `AndroidGattConnection` turns a write (RX or the TX CCCD) that still fails with status 5, 15 or 0x89 after Android's pairing attempt into `GattWrite.NotPaired`. Writes wait up to 30 s and the setup up to 45 s, so the user has time to confirm Android's pairing dialog. `AndroidBleTransport` then ends the link with `DownReason.NotPaired`. |
| `:data:scanner` | `ReconnectPolicy` does not retry `NotPaired` (retrying outside the window only fails again); the repository reports `ConnectionState.Failed(NotPaired)`. |
| `:presentation` | The status screen says: "This phone is not paired with the scanner. Open the scanner's pairing window, then tap Retry and confirm the pairing." Retry connects again, and Android pairs on the first write. |

The app does not call `createBond()` itself: Android's automatic pairing on the refused write
is enough, and it pairs with the device the app is already connected to.

## Flow

1. The app connects and turns notifications on; the first write is `hello`.
2. Not bonded: the write is refused, Android pairs on its own (with Just Works some phones still
   show a confirmation dialog) and repeats the write.
   - Window open → the bond is made, the write goes through, and everything continues as usual.
   - Window closed → the write fails, the link goes down with `NotPaired`, and the user is told
     to open the window and tap Retry.
3. Bonded: writes go through at once. Android keeps the keys; the app stores nothing secret.

## Later (phase 3, not built)

- **The scanner forgot the bond** (reset, or removed from its allow-list) while the phone still
  holds keys: encryption fails right after connecting, often as status 5, 15 or 0x89, or the
  link drops. The app will tell the user to remove the scanner in Android's Bluetooth settings
  and pair again. Android has no public API to remove a bond (`removeBond` is hidden), so the
  app does not use reflection.
- **Allow-list full** (about 4 phones): how the scanner reports it is not decided yet.
- **Several scanners:** bonds are kept per address; the address is public and stable (open
  question 29), so reconnecting by address keeps working.
- A `BondState` flow (from `ACTION_BOND_STATE_CHANGED`) could show "pairing…" while Android's
  dialog is open, instead of "Connected" with no values yet.

## Tests

`AndroidBleTransportTest` covers a CCCD write and an RX write refused for want of pairing (the
link ends with `NotPaired`); `ScannerRepositoryTest` checks that `NotPaired` is not retried; the
screen test checks the message and the Retry button. Android's pairing itself is only tested on
a device.

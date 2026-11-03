# Design: pairing and bonds

The scanner side decided (open question 33) that **every request needs a bonded phone**. RX is
encrypt-write and TX encrypt-notify: subscribing to TX makes the scanner send an SMP Security
Request, and an unencrypted RX write is refused with ATT "insufficient encryption". Android
opens a pairing dialog for each of these triggers, so the app bonds first (below) and the user
sees one dialog. Pairing only succeeds while the scanner's pairing window is open (120 s):
today `tools/pair.sh` on the scanner opens it, later a pairing button. There is no protocol
error such as `not_paired`. A bond allow-list (about 4 phones) and clearing come in phase 3.
The scanner disconnects a phone that has not subscribed to TX within 30 s, so the user has
about that long to confirm the dialog.

## What is built (milestone 2)

| Layer | Piece |
|---|---|
| `:infra:ble` | During setup, after the MTU and before the TX subscription, `AndroidGattBonder` checks `getBondState()`; when the phone is not bonded it calls `createBond()` and waits up to 30 s for `BOND_BONDED`, or `BOND_NONE` after `BOND_BONDING` (`ACTION_BOND_STATE_CHANGED`). `BOND_NONE` or the timeout fails the setup with `DownReason.NotPaired`. If `createBond()` cannot start, setup goes on and Android pairs on the first encrypted operation, as before. |
| `:infra:ble` | `AndroidGattConnection` turns a write (RX or the TX CCCD) that still fails with status 5, 15 or 0x89 after Android's pairing attempt into `GattWrite.NotPaired`. Writes wait up to 30 s and the setup up to 45 s, so the user has time to confirm Android's pairing dialog. `AndroidBleTransport` then ends the link with `DownReason.NotPaired`. |
| `:data:scanner` | `ReconnectPolicy` does not retry `NotPaired` (retrying outside the window only fails again); the repository reports `ConnectionState.Failed(NotPaired)`. |
| `:presentation` | The status screen says to run `tools/pair.sh` on the scanner (`--forget` when this phone was paired with it before), then tap Retry and confirm the pairing. Retry connects again and bonds. |

`createBond()` on a connected LE device pairs over the existing link.

## Flow

1. The app connects, discovers the services and negotiates the MTU.
2. Not bonded: the app calls `createBond()`; Android shows one dialog (Just Works still asks for
   a confirmation on most phones).
   - Window open → `BOND_BONDED`; notifications go on and the first write is `hello`.
   - Window closed → `BOND_NONE`, the link goes down with `NotPaired`, and the user is told to
     run `tools/pair.sh` and tap Retry.
3. Bonded: notifications and writes go through at once. Android keeps the keys; the app stores
   nothing secret.

## Later (phase 3, not built)

- **The phone forgot the bond but the scanner did not:** re-pairing fails ("DHKey check" on
  BlueZ, `JustWorksRepairing=never`) until `tools/pair.sh --forget` runs on the scanner; the
  not-paired message says so.
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

`AndroidBleTransportTest` covers bonding before the TX subscription, a failed bonding (the
setup fails with `NotPaired` before notifications are asked for), bonding that cannot start,
and a CCCD write and an RX write refused for want of pairing (the link ends with `NotPaired`);
`ScannerRepositoryTest` checks that `NotPaired` is not retried; the
screen test checks the message and the Retry button. Android's pairing itself is only tested on
a device.

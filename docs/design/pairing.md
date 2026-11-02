# Design: pairing and bonds (not built yet)

`recording.start` and `recording.stop` will need a bonded phone. The user presses a separate
pairing button on the scanner, which opens a pairing window of about 2 minutes. Pairing uses
Just Works, and writes then need an encrypted link.

## Layers

| Layer | Piece |
|---|---|
| `:domain` | `BondState` (`NotBonded`, `Bonding`, `Bonded`, `Failed(reason)`), the `BondManager` port (`bondState(target): Flow<BondState>`, `suspend fun bond(target): Outcome<Unit>`, `forget(target)`), and the use case `PairScanner` |
| `:infra:ble` | `AndroidBondManager`: `BluetoothDevice.createBond()`, listening to `ACTION_BOND_STATE_CHANGED`, and `getBondState()` |
| `:data:scanner` | Maps insufficient-authentication failures to `DomainError.PairingRequired` |
| `:presentation` | A pairing sheet that walks the user through the steps |

## Flow

1. The user taps **Pair** in the app. The app explains: "Press the pairing button on the
   scanner, then tap Continue."
2. The app calls `createBond()` on the connected device. With Just Works there is no PIN; on
   some phones the system still shows a confirmation dialog.
3. The app waits for `ACTION_BOND_STATE_CHANGED`, giving up after 2 minutes to match the
   window.
   - `BOND_BONDED` → done. If the link was not encrypted yet, the app reconnects so the next
     write runs over an encrypted link.
   - `BOND_NONE` after `BOND_BONDING` → failed. The most likely cause is that the window was
     closed: "Press the pairing button again."
4. The app stores nothing secret. Android keeps the keys; the app keeps only the address of the
   bonded scanner, so it can reconnect to it on the next start.

## Handling bonds

- **A write that needs a bond fails** (GATT status 5 or 15, or the protocol error the scanner
  side has not decided yet; open question 33): show "Pair this phone with the scanner" and
  start the flow above. Do not retry the write on its own.
- **The scanner forgot the bond** (it was reset), but the phone still holds keys: encryption
  fails, often as status 5, 15 or 137 right after connecting. After two failures in a row, tell
  the user to remove the scanner in Android's Bluetooth settings and pair again. Android has no
  public API to remove a bond (`removeBond` is hidden), so the app does not use reflection.
- **Several scanners:** bonds are kept per address, so the domain stores the bond state per
  `ScannerCandidate.id`.
- **Reads stay unbonded** unless the scanner side decides otherwise (open question 33), so
  milestone 1 keeps working without a bond.

## Tests

`BondManager` gets a fake in `:testing`, and the flow is tested at the use case and ViewModel
levels. Android's bond broadcasts are only tested on a device.

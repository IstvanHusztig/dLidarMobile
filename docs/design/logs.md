# Design: the scanner's log (built; verified on the device against the real scanner)

The Diagnostics tab of the status screen shows the scanner service's log, read over BLE with `log.get`
(open question 35). Only the service's lines are in it: dLidar's own and systemd's about the
service. A dLidar run started by hand over SSH is not.

## Layers

| Layer | Piece |
|---|---|
| `:domain` | `LogEntry(time, boot, level, source, text)`, `LogLevel` and `LogSource` (with `Unknown(raw)`), `LogThreshold` (`Info`, `Warning`, `Error`), the opaque `LogCursor`, `LogPage(entries, older)`, the port `ScannerLog` and the use case `ReadScannerLog`; `ScannerSnapshot.canReadLog` |
| `:data:scanner` | `LogGetCommand`, `LogMappers` and `ScannerLogImpl`, on the repository's live session (`LiveSessionSource`) |
| `:presentation` | `LogViewModel`, `LogScreen` (the Diagnostics tab) and `LogExport` (the text to share) |
| `:app` | The Log route: the share intent; in debug builds the protocol traffic log beside the scanner log |

## Reading

1. **Offered** only when `hello` lists `log.get` (`canReadLog`). An older scanner gets no
   request; the tab says to update the scanner.
2. **First page** when the Diagnostics tab is first shown, or once the scanner can answer if it is shown
   earlier: `{"limit":100,"level":<threshold>}`, newest line first.
3. **Older pages** with `before` = the previous answer's `more`, when the list comes within 5 rows
   of its end, and again after each page that added lines, so a short page on a tall screen is
   followed by the next. `more: null` is the start of the log.
4. **Short and empty pages.** A page always fits the 16 KiB message limit, and one request reads
   only a bounded part of the journal, so an answer can hold fewer lines than asked, even none,
   while `more` is not null. `ScannerLogImpl` asks again with the new cursor until it has lines
   or the start of the log, at most 5 requests per read (app choice); after that the tab offers
   "Load older lines".
5. **Refresh and level.** Pages stay stable while new lines arrive. Refresh, and a new level,
   read the newest page again and replace what is held, so lines never need merging and no entry
   id is needed. The old lines stay shown until the new page arrives.
6. **Entries.** One entry of an unexpected shape is skipped; the rest of the page is kept.
   Unknown levels and sources are shown as the scanner sends them.

## Showing

- Newest first. A header "Boot <id>" starts each run of lines from one boot, so a reboot is
  visible. The Pi has no RTC battery, so its clock can be wrong offline: the order is the
  list's, and the time is shown as the scanner sent it.
- Each line: the time (`MM-dd HH:mm:ss`, phone's zone), the level (warnings amber, errors red),
  `system` for systemd's lines, and the text as it is.
- Filter chips: All, Warnings, Errors (the lowest level returned).
- After the link drops the lines stay, marked "not live"; another scanner starts empty.
- Failures: `unavailable` shows the scanner's text; a failed first or older page offers Retry for
  that page.

## Sharing

`LogExport` writes the held lines oldest first, one per line:
`yyyy-MM-dd HH:mm:ss.SSS <boot> <LEVEL> <source>: <text>`. It is capped at 100,000 characters
(an Intent extra must stay well below the Binder limit); the oldest lines are left out first,
and the first line says how many.

## Found on the device

The first real page (2026-11-04) arrived corrupted: Android delivers at most 512 bytes of a
notification (the GATT maximum attribute length), but with MTU 517 the scanner sent 514, so 2
bytes were lost from every full notification. Every message over 512 bytes is affected. The
scanner now caps its notifications at min(MTU-3, 512), and the app caps its own write pieces
the same way.

With that fix (2026-11-04, Galaxy S25, MTU 517): the newest page and two older pages to the
start of the log, about 0.5 to 0.65 s each, nothing lost; the Warnings level in one request,
with headers for three boots; Share opened the system share sheet with the lines oldest first.

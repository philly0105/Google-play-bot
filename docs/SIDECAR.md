# Sidecar protocol

The app talks to `ClashFarmer.Native.exe serve` over its standard input and output. The sidecar
does the Windows work the JVM can't do cleanly: Windows Graphics Capture of the Clash window,
posted mouse and key input, PaddleOCR, and a few system queries. It holds no farming logic; every
decision stays in the phone code and the ported `AndroidTransport`.

The app starts one sidecar at launch and starts it again if it dies. The sidecar exits when its
standard input closes.

The Gradle task `publishSidecar` publishes it self-contained, OCR models included, into the app's
resources, so both `run` and the distributable have it at `sidecar/ClashFarmer.Native.exe` under
the `compose.application.resources.dir` system property.

## Framing

Both directions use the same frame:

```
u32 little-endian   JSON length
bytes               UTF-8 JSON
u32 little-endian   payload length, 0 for none
bytes               payload
```

Standard output carries frames only. The sidecar writes log lines to standard error, and the app
copies them into its own log.

A request is `{"id": 7, "op": "capture", ...arguments}`. Every request gets exactly one response,
`{"id": 7, "ok": true, ...result}` or `{"id": 7, "ok": false, "code": "no_window", "error": "..."}`.
Responses may arrive out of order. Requests run concurrently, but each lane runs one at a time:
input (`gesture`, `wheel`, `key`), capture, and OCR.

Error codes:

| Code | Meaning |
| --- | --- |
| `no_window` | No Clash of Clans window from Google Play Games. |
| `minimized` | The window is minimized and the request said not to restore it. |
| `timeout` | No frame arrived in time. |
| `bad_request` | Unknown op or a missing argument. |
| `failed` | Anything else; `error` says what. |

## Coordinates

Every point and box is in physical pixels of the game surface (`CROSVM_1`'s client area). Frame
pixels and input points are the same pixels, at any DPI.

## Ops

### `hello`

Returns `version`, `pid` and `ocrReady`. OCR models load in the background at start.

### `find`

Looks the window up again on every call; handles change when Google Play Games restarts.

Returns `found`, and when found: `top` and `game` (window handles as numbers), `title`, `iconic`,
`width` and `height` of the game surface, `foreground` (the Clash window is the active window), and
`monitorDpi`.

### `capture`

Arguments: `restore` (default true), `timeoutMs` (default 3000).

Returns `width`, `height` and `timestampMs` (Unix ms when the frame arrived), with the frame as the
payload: BGRA, top row first, no padding between rows.

The sidecar keeps one capture session per window and takes every frame as it arrives, so the
newest frame is always current. Windows Graphics Capture sends a frame only when the window
changes, so a still window's newest frame is still what it shows. The surface runs 4 px past the
window's frame on the right and bottom, and those columns and rows are copied from their
neighbours so the frame has the surface's size.

A minimized window sends no frames. With `restore` the sidecar restores it without activating it
(`SW_SHOWNOACTIVATE`) and waits for a frame from after the restore.

### `gesture`

Arguments: `strokes`, a list of `{"from": [x, y], "to": [x, y], "ms": n}`.

One finger: the button goes down at the first stroke's `from`, moves along each stroke in turn,
and comes up at the last stroke's `to`. Each stroke ends where the next starts. Moves go out about
every 8 ms, placed by elapsed time. A tap is one stroke whose `from` and `to` are the same, held
for `ms` (the phone holds 50 ms). A held swipe is the phone's `swipeStrokes`: the drag, then the
hold creeping 2 px on.

A gesture can't be cancelled once it starts, and the response comes when the button is up. On the
phone a gesture is cancelled only by a person touching the screen, and the ported transport's
pause checks run before a gesture goes out, as on the phone. A cancelled press on the PC would
still be a click, since a posted button-up can't be told apart from a real one.

### `wheel`

Arguments: `x`, `y`, `notches`, `intervalMs` (default 40). Negative notches zoom out, positive
zoom in, around the point. Clash zooms about 10% per notch.

The game surface turns a phone pinch-out into 25 notches out around the pinch's centre, past the
PC's zoom-out limit. See `docs/gpg-spike.md`.

### `key`

Arguments: `vk`, a virtual-key code. `27` (Esc) is Android Back.

### `ocr`

Arguments: `width`, `height`, with the image as the request payload in the same BGRA layout as a
frame.

Returns `lines`, each `{"text": "...", "box": [left, top, right, bottom], "score": 0.97}`, in the
image's pixels, top to bottom. The ML Kit shim gives each line its own text block.

### `resize`

Arguments: `width`, `height` of the game surface. Resizes the top-level window without activating
it. Returns the `width` and `height` the surface ended at; Google Play Games keeps it at 16:9.

### `launch`

Arguments: `url`. Opens it through the shell, as `Start-Process` does. Clash's URL is
`googleplaygames://launch/?id=com.supercell.clashofclans&lid=1&pid=1`. Google Play Games may bring
its window to the front.

### `close`

Sends `WM_CLOSE` to the Clash window.

### `power`

Returns `ac` (plugged in), `batteryPercent` (-1 when unknown) and `saver` (battery saver on).

### `keepAwake`

Arguments: `on`. While on, Windows keeps the system and the display awake through power requests
held by the sidecar. They lapse when it exits.

### `input`

Returns `idleMs` (since the person last used the mouse or keyboard anywhere), `gameForeground`
and `cursorInGame`. The service uses these to tell a person taking over Clash from someone working
in another window.

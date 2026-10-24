# Google Play Games spike, 2026-10-24

Measured on this PC with Blood loaded in Google Play Games (GPG), using the probe in
`native/ClashFarmer.Native` (`list`, `capture`, `click`, `drag`, `wheel`, `key`, `setwindow`, `show`,
`cover`, `ocr`, `ocr2`). Captures are in `audit-evidence/spike/` (gitignored).

## Windows

Everything belongs to the `crosvm` process:

| Window | Class | Notes |
| --- | --- | --- |
| Top level | `HwndWrapper[DefaultDomain;;<guid>]` | Title `Clash of Clans - <GPG profile name>`. Custom chrome: a 45 px title bar and an 80 px sidebar (physical pixels at 125%). |
| Game surface | `CROSVM_1`, title `crosvm` | Child of the top level. Always 16:9. |
| `subWin`, title `sub` | | Same rect as `CROSVM_1`, disabled. |
| `MetricsOverlayWndClass` | | Hidden. |

All of them are system-DPI-aware (awareness 1) at 96 DPI while sitting on the 120 DPI panel.
Handles change when GPG restarts, so find the game by process `crosvm`, class `CROSVM_1`, and a
top-level title starting with `Clash of Clans`.

Launching: `googleplaygames://launch/?id=com.supercell.clashofclans&lid=1&pid=1`. `cmd /c start`
splits the URL at `&`; use `explorer.exe "<url>"` or PowerShell `Start-Process`.

## Capture

- Windows Graphics Capture (WGC) on the top-level window works: the first frame takes 310-470 ms
  while the session starts, later frames about 15 ms.
- Crop each frame to `CROSVM_1`'s window rect, measured from the top level's DWM frame bounds. The
  child runs 4 px past the frame bounds on the right and bottom, so a 1740x979 child yields a
  1736x975 frame. The sidecar pads the frame back to the child's client size, so that frame pixels
  and input coordinates are the same pixels.
- Covered: works. With a topmost window over the whole game, WGC still returned the game and a
  posted tap still opened the Shop.
- Minimized: WGC delivers no frames, and PrintWindow returns a 195x30 stub. `ShowWindow(SW_SHOWNOACTIVATE)`
  restores the window without taking focus, and capture resumes at once.
- Not tested: a locked PC and a sleeping display. During runs the bot holds
  `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)` and treats a
  capture failure the way the phone treats a screen that is off.

## Input

All of these are `PostMessage` to `CROSVM_1`. None of them changed the foreground window (Chrome
stayed in front playing video) or moved the real cursor, so the SendInput fallback isn't needed.

- Tap: `WM_MOUSEMOVE`, `WM_LBUTTONDOWN` with `MK_LBUTTON`, an 80 ms hold, then `WM_LBUTTONUP`.
- Coordinates are physical pixels of `CROSVM_1`'s client area, even though crosvm is system-aware
  at 96 DPI on a 125% panel. Read as logical pixels, the Shop tap at x 1665 would have fallen off
  the button, yet it opened the Shop.
- Drag: `WM_MOUSEMOVE` with `MK_LBUTTON` every 10 ms pans the village. Releasing while still moving
  leaves a fling; the phone's end hold covers that.
- Wheel: `WM_MOUSEWHEEL` with screen coordinates in lParam and 120 per notch. It zooms around the
  posted point, with no Ctrl needed. Each notch is about 10%: three notches out gave 0.72x.
- Esc (`WM_KEYDOWN`/`WM_KEYUP` with `VK_ESCAPE` and its scan code) acts as Android Back: it closed the
  Shop.

## Resolution and layout

- GPG pins the game area to 16:9. A 1680x765 window gave a 1280x720 game area with bars at the
  sides, so the phone's 1600x720 (20:9) can't be reproduced.
- Clash's HUD scales with the game area. The Build button is 148 px wide at 1740 and 108 px at
  1280, a ratio of 1.37 against 1.36, so fractions of the game area hold at any 16:9 size.
- The PC runs the same Clash version as the phone (the 18.647 HUD, with Build at the bottom right),
  but the HUD sits at 16:9 with no notch inset, so the phone's fractions of 1600x720 don't carry
  over. About 60 lines hold fixed positions: `ScreenTargets.kt`, `Builders.kt`,
  `ArmyRecipes.kt`, `WallPanel.kt`, `UpgradeListNavigator.kt`, `PopupGuard.kt` and
  `VillageCamera.kt`. Each needs a PC value. Most recognition goes by OCR text inside wide bands,
  which should hold.
- Deploy points, perimeter segments and card regions come from the per-device calibration profile,
  so the PC calibrates them again, as the phone did.

## Zoom

- The PC's zoom-out limit is far wider than the phone's. At the limit the village is a small island
  in the scenery. The limit is reached within 20 notches.
- The limit plus 12 notches in frames the village about as the phone's minimum zoom did, with the
  walls filling the frame height. Both phone pinch call sites (`normalizeVillageCamera`,
  `zoomOutForDeployment`) only pinch out to the limit, so the PC transport turns every pinch-out
  into "out to the limit, then 12 in". That gives one fixed zoom and leaves the core alone.
- Not tested: the attack scene's limit.

## OCR

Run on one 1736x975 village frame:

| Engine | Result | Time |
| --- | --- | --- |
| Windows.Media.Ocr, raw | 11 lines. Missed Battle, Blood, gold and elixir; "5Hop", "3K 27M". | 109 ms |
| Windows OCR, 2x upscale with white-text mask | Read Battle, Blood and three counts, but "29 soo ooo", "3K 27M", "Syop". | 280-440 ms |
| PaddleOCR PP-OCRv5 latin (RapidOcrNet 4.2.0, ONNX Runtime) | Every HUD string right: Battle, Build, Blood, Shadow Elites, 30 741 200, 29 500 000, 167 280, 2 508, 3h 27M, 17h 17m, "SHOp". | 300-340 ms |

Decision: PaddleOCR in the sidecar, 4 threads, angle classifier off. With all 32 logical
processors ONNX Runtime thrashed, at 1-3 s per frame. RapidOcrNet resolves its model paths against
the working directory, so pass absolute paths. Its models and code are Apache-2.0.

## Clash notes

- With GPG idle in the background, Clash showed "Anyone there? You have been disconnected due to
  inactivity. RELOAD GAME". PopupGuard's reload handling covers this.

## Still open

- A locked PC and a sleeping display.
- The attack scene's zoom framing.
- Restarting Clash: `WM_CLOSE` to the top level, then relaunch through the URL.

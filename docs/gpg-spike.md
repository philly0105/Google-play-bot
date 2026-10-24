# Google Play Games spike, 2026-10-24

Measured on this PC with Blood loaded in Google Play Games (GPG), using the probe in
`native/ClashFarmer.Native` (`list`, `capture`, `click`, `drag`, `wheel`, `key`, `setwindow`, `show`,
`cover`, `ocr`, `ocr2`). Captures are in `audit-evidence/spike/` (gitignored).

## Windows

Everything belongs to the `crosvm` process:

| Window | Class | Notes |
| --- | --- | --- |
| Top level | `HwndWrapper[DefaultDomain;;<guid>]` | Title `Clash of Clans - <GPG profile name>`. Custom chrome: a 45 px title bar and an 80 px sidebar (physical pixels at 125%). |
| Game surface | `CROSVM_1`, title `crosvm` | Child of the top level. Keeps the aspect chosen at launch: 16:9 or 16:10 so far. |
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
stayed in front playing video) or moved the real cursor. They depend on Android keeping its touch
device on the game's display, which a relaunch into the running VM broke (see Restarting).

- Tap: `WM_MOUSEMOVE`, `WM_LBUTTONDOWN` with `MK_LBUTTON`, an 80 ms hold, then `WM_LBUTTONUP`.
- Coordinates are physical pixels of `CROSVM_1`'s client area, even though crosvm is system-aware
  at 96 DPI on a 125% panel. Read as logical pixels, the Shop tap at x 1665 would have fallen off
  the button, yet it opened the Shop.
- Drag: `WM_MOUSEMOVE` with `MK_LBUTTON` every 10 ms pans the village. Releasing while still moving
  leaves a fling; the phone's end hold covers that.
- Wheel: `WM_MOUSEWHEEL` with screen coordinates in lParam and 120 per notch, no Ctrl needed. Each
  notch is about 10%: three notches out gave 0.72x. Where it zooms around wasn't pinned down (see
  Zoom).
- Esc (`WM_KEYDOWN`/`WM_KEYUP` with `VK_ESCAPE` and its scan code) acts as Android Back: it closed the
  Shop.

## Resolution and layout

- GPG keeps the game area at the aspect it chose at launch. A 1680x765 window gave a 1280x720
  game area with bars at the sides, and on a 16:10 surface a request for 1740x978 gave 1565x978.
  So the phone's 1600x720 (20:9) can't be reproduced.
- Clash's HUD scales with the game area. The Build button is 148 px wide at 1740 and 108 px at
  1280, a ratio of 1.37 against 1.36, so fractions of the game area hold at any 16:9 size.
- The PC runs the same Clash version as the phone (the 18.647 HUD, with Build at the bottom right),
  but the HUD sits at 16:9 with no notch inset, so the phone's fractions of 1600x720 don't all
  carry over (see Screen geometry). Most recognition goes by OCR text inside wide bands, and most
  of those hold.
- Deploy points, perimeter segments and card regions come from the per-device calibration profile,
  so the PC calibrates them again, as the phone did.

## Zoom

The bot barely zooms. It navigates the village through the HUD buttons and the builder's upgrade
list, and the only home-village feature that uses the camera is resource collection
(`CollectionRunner`), which runs only when collectors are calibrated. So the PC keeps zoom simple
(the user's call on 24 October) rather than reproducing the phone's framing.

- The PC's zoom-out limit is far wider than the phone's. At the limit the village is a small island
  in the scenery. The limit is reached within 20 notches.
- Zooming out to the limit doesn't recentre the camera: across starts, the village centre at the
  limit sat anywhere from (720,440) to (920,500).
- Zooming in from the limit doesn't land in a repeatable place either. Three notches in from points
  posted at (300,250) fitted anchors near (306,260) and (309,195); one posted at the centre fitted
  one near the top left. So "out to the limit, then N in" isn't a fixed framing, and that plan is
  dropped.
- Plan: the PC transport turns a pinch-out into wheel notches out to the limit. The phone's
  `normalizeVillageCamera` then runs unchanged: at the limit its clamp drags push the camera into
  the pan limit, which is the phone's own approach. Collection registers the result against its
  reference before tapping, so a camera that doesn't match ends the phase untapped. Unproven until
  collectors are calibrated on the PC.
- The attack scene opens with the whole enemy base in view (`geo-scout.png`), which the phone's
  20:9 framing didn't. Deploy points are calibrated on the PC either way.

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

## Screen geometry

Captured at 1740x978 on 24 October, with PaddleOCR boxes beside each frame
(`audit-evidence/spike/geo-*.png` and `.json`). The phone's matching frames are in
`clash-farmer/audit-evidence/clash-18647/`.

- The HUD keeps its anchors. The phone drew it inside a 55 px safe-area inset on both sides; each
  element sits at the same distance from its edge (left, right, centre; top, bottom, middle),
  scaled by 1.14 at 1740x978, which is the surface height over 858. Battle, Build, the builder
  counter and Shop all land within 2 px of that prediction.
- Panels don't follow it. The upgrade list is a full-height panel at x 1272-1740 whose rows scale
  by about 1.10, so it shows six rows in progress where the phone showed five. The army screen
  scales by about 1.18. A selected building's buttons are the phone's own size (124 px wide,
  139.5 px apart), centred at x 870. So each screen's fixed positions are measured, not modelled.
- The phone's bands, points and regions in `ScreenTargets`, `Builders`, `ArmyRecipes`, `WallPanel`,
  `UpgradeListNavigator` and `PopupGuard` are fractions of 1600x720. Some miss on the PC. For
  example, the builder counter's band ends at y 0.803 and the PC counter sits at 0.807, and the
  army tabs sit just below their bands.
- Not captured: a battle with troops down, the surrender dialog, the result screen, the reward
  chest, the wall bulk panel and its confirm dialog, and "All builders are busy!". Blood's walls are
  maxed, and the battle screens need a real attack.

## Clash notes

- With GPG idle in the background, Clash showed "Anyone there? You have been disconnected due to
  inactivity. RELOAD GAME". PopupGuard's reload handling covers this. Esc on that dialog reloads
  the game as its button does, and the village behind it, Blood, Battle and Build included, stays
  readable, so a check for the plain village has to rule the dialog out by its words.
- Esc on the village, even with a building selected, opens "Confirm Exit: Do you want to quit the
  game? Cancel / Okay". A tap on empty scenery is what deselects.
- Esc on the army screen goes back to the attack menu, and Esc there goes back to the village.
- The attack menu shows two Find a Match buttons on Blood: the Battle card's (1700 gold) and the
  Ranked Battle card's (1/18 attacks). The phone's calibrated `find_match` anchor must be the
  Battle card's.
- The army screen opened from Find a Match shows the My Army tab with Attack!; opened from Edit
  Army, the same button reads Battle. Left of it is a green button with a gem icon, never pressed.
- Starting a search with a shield up showed no warning, and End Battle before any troop went
  straight home with the shield kept.
- GPG draws keyboard hints on the troop bar (1, Q, W, E, R, A, S).

## Restarting

Checked on 24 October on Blood, with the village behind the idle dialog and another app in front:

- `WM_CLOSE` to the top level hid the window within 0.25 s, with no prompt. `crosvm.exe` kept
  running, and the relaunched window had the same top-level and `CROSVM_1` handles.
- The URL showed the window again 6.3 s later without taking focus; the app in front stayed in
  front throughout. The first frame was black with the Google Play Games logo at the top, and by
  15 s Blood's village was up behind "Welcome Back Chief!".
- The surface came back at 1860x1162 (16:10), where it had been 1740x978 (16:9), on the same
  120 DPI panel. The HUD kept its edge anchors scaled by height, so the aspect is not fixed across
  launches and geometry has to come from the live surface size.
- Posted input broke. Android gave the game a new 2560x1600 display but left its touch device on
  the VM's 1280x1024 boot display (AndroidSerial.log: "Overriding the dimensions of MultiTouch
  Device ... to 1280x1024"), so posted taps did nothing. A second close and relaunch did the same.
  Making the window the foreground, or giving the surface focus inside crosvm, moved nothing.
- Sending the launch link again while Clash ran moved the touch device to the game's display.
  Taps then reached the game, but 40 to 75 px above where they were posted: RELOAD's text button
  missed at y 660 and was hit at 700, and a tap 58 px below an elixir bubble collected it. Drags
  kept their length on both axes, and x was right.
- After the PC restarted, a fresh start (Google Play Games not running) chose 1920x1080 again on
  the same panel. Android moved touch to the game's display within 3 s, and a tap at a gold
  bubble's centre collected it.
- A resize is not a relaunch. In the first session the window went to 20:9 and back to 1740x978
  between 00:00 and 00:03, and every tap after it landed: the 01:02 anchor tap, and the attack
  menu, Find a Match and End Battle between 01:18 and 01:20 (`audit-evidence/spike/`).
- So the app never closes the Clash window, and sends the launch link only when there is no Clash
  window. A window closed by hand still leaves the VM running, and the app's launch after that is
  the relaunch that broke input. Whether quitting Google Play Games from its tray icon gives a
  fresh start, as the restart did, is untested.

## Still open

- A locked PC and a sleeping display.

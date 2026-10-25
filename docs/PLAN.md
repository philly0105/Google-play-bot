# Clash Farmer PC - port plan

Port of the phone bot in `C:\Users\aideo\Projects\clash-farmer` (branch `ui-overhaul`, head
`eeaadba`, the build installed on the phone on 15 October 2026) to a Windows desktop app that farms
Clash of Clans running in Google Play Games for PC (GPG). Same features, same decisions, same
settings and history model. The PC bot replaces the phone bot.

Source of truth for behaviour: `clash-farmer/android/app/src/main/java/local/clashfarmer/`
(below: "the phone tree"). Read `clash-farmer/android/STATUS.md` before claiming anything works on
the phone side.

## What the user decided (2026-10-24)

- The bot runs in the background. The user keeps using the PC while it farms, so the bot must not
  move the real cursor or need the Clash window in front. Input goes to the GPG window directly.
  Only if GPG ignores that kind of input do we fall back to real mouse input, and we tell the user.
- Only the Blood account is signed in on the PC for now. Account switching is ported unchanged and
  proven once a second account is signed in. (Since 25 October the app runs one account, whatever
  is signed in, and reads its identity on the first supervised run, so a friend can use it on their
  own account. On this PC that account is still Blood.)
- The PC bot replaces the phone bot. No coordination with the phone.
- Full permission to drive Clash on this PC for Blood: inspect, calibrate, real attacks, unattended
  runs. Never spend gems. Blood's walls are maxed, so no wall purchase can be proven live; the
  "no wall left to buy" path can.

## Facts about GPG on this PC

- GPG 26.10.305.0 (beta channel), installed to `D:\D drive AI work\Play Games\current\`. Logs in
  `%LOCALAPPDATA%\Google\Play Games\Logs\`. Hypervisor WHPX. Clash version code 180647006 (18.647).
- The emulator boots with `androidboot.kiwi.adbproxy.enabled=0` and
  `enable_devmode_sideloading=false`: no ADB, no sideloading. The bot sees and drives Clash only
  through the Windows window.
- crosvm renders with DXGI flip model (`usedxgiflipmodel=true`), so GDI BitBlt/PrintWindow may come
  back black. Windows Graphics Capture (WGC) is the expected capture path.
- Monitors: primary 1920x1080 at 100%, laptop panel 2560x1600 at 125%. The app must be per-monitor
  DPI aware (v2) and work in physical pixels.
- The spike results (windows, capture, input, zoom, OCR, screen geometry) are in
  `docs/gpg-spike.md`.

## Architecture

One Gradle module `app` (Kotlin/JVM 21, Compose Multiplatform for Desktop), plus a C# sidecar.

```
app/src/main/kotlin/
  android/graphics/...        shims: Bitmap, BitmapFactory, Canvas, Color, Paint, Rect
  android/os/SystemClock.kt   shim
  com/google/mlkit/...        shim of the ML Kit text API the phone code calls, backed by OcrBackend
  com/google/android/gms/tasks/Task.kt   shim
  local/clashfarmer/...       the phone tree, copied (see file classes below)
  local/clashfarmer/desktop/  everything Windows-specific: native bridge, OCR backend, window
                              control, tray, notifications, Compose UI
app/src/main/resources/       remote.html, app icon
app/src/test/kotlin/          the phone tree's JVM tests, copied
native/ClashFarmer.Native/    C# .NET 8 sidecar: WGC capture, PaddleOCR, window input
```

### File classes

1. Verbatim: copied byte for byte from the phone tree. Everything with no `android`, `androidx`
   or `com.google` import, for example all of `history/`, `insight/`, most of `core/`,
   `accounts/` except `AccountSwitcher.kt`, the pure `recognition/` readers, `remote/RemoteApi.kt`,
   `service/FirstStop.kt`, `FrameStore.kt`, `PendingStart.kt`, and the pure `ui/` models
   (`DashboardLoader`, `DashboardScreen`, `DashboardState`, `FarmingSettingsEditor`,
   `components/AccountColor`, `ChartScale`, `Tone`, `UiText`).
2. Verbatim through shims: files whose only platform use is `android.graphics.*`,
   `android.os.SystemClock` or ML Kit, which the shims satisfy: `core/LocalRunner.kt`,
   `RunGuard.kt`, `VillageCamera.kt`, `CollectionRunner.kt`, `AndroidWallDevice.kt`,
   `accounts/AccountSwitcher.kt`, `recognition/ImageMatcher.kt`, `ScreenReader.kt`,
   `service/LatestFrame.kt`. They call into `FarmerService`, which exists on the desktop with the
   same members.
3. Ported: same class name, package and public members as the phone tree, desktop internals.
   `service/FarmerService.kt` (a plain class, no AccessibilityService), `service/AndroidTransport.kt`
   (all guard logic kept; only screenshot and gesture dispatch replaced by the native bridge),
   `service/FarmerOverlay.kt` (an always-on-top panel), `service/RunNotifier.kt` (`RunNotice` and
   `LiveStatus` stay verbatim; the notifier posts Windows notifications), `log/AppLog.kt`
   (a files directory instead of a Context), `remote/RemoteServer.kt`.
4. Rewritten in Compose: `MainActivity`, `CalibrationActivity`, `CalibrationView`, and every
   Android View file under `ui/`. Same tabs, same content, same actions, laid out for a desktop
   window.

Any edit to a class 1 or class 2 file is a divergence. Keep them rare, and record each one in
`docs/DIVERGENCES.md` with the reason. When a test targets a pure function that lives in a class 3
or class 4 file, move that function into a pure file in the same package with the same name so the
test compiles unchanged, and record the move.

### Phone concept -> PC equivalent

| Phone | PC |
| --- | --- |
| AccessibilityService `takeScreenshot` | WGC capture of the Clash window's client area via the sidecar |
| `dispatchGesture` tap/swipe/hold | Posted mouse messages to the GPG window (fallback: SendInput) |
| Two-finger pinch | Mouse wheel. The bot only pinches out to the zoom-out limit, so a pinch-out becomes wheel notches out to the limit |
| Global Back | Esc, which GPG maps to Android Back |
| Clash in foreground | The Clash GPG window exists, is not minimized and shows Clash |
| Screen off / locked | The session locked or the display off (the sidecar's `sessionState`) while capture delivers only old frames; the run stops with a plain reason. A still village is never read as this |
| A start set for later | The run's own keep-awake (system and display) from the time it is set until it fires, so the PC neither sleeps nor goes dark; at the start time a locked PC or a dark display refuses the start with a plain reason |
| Battery and heat guard | Laptop battery when unplugged |
| Restart Clash | Start it through GPG's launch link, only when there is no Clash window |
| Home (the takeover wait) | Nothing: Clash stays on the takeover dialog. The bot never closes the Clash window, since a relaunch into the running VM broke posted input (`docs/gpg-spike.md`, Restarting) |
| Floating overlay | Small always-on-top window with status, Pause and Stop |
| Notifications | Windows notifications from the tray icon |
| `/data/data/local.clashfarmer/files/` | `%LOCALAPPDATA%\ClashFarmerPC\files\` with the same layout |
| Sweat and Blood, seeded with the owner's names and tags | One account in Blood's id slot (`accounts/PcAccount.kt`). A fresh install seeds it as "Your village" |
| Identity templates shipped as app assets | `accounts\<id>\identity.png` and `identity.json` in the data folder, cut by the account's first supervised run and dropped again if the check straight after does not confirm it |
| Remote page on port 8765 over Tailscale | The same page and API from the PC, same key and peer rules. It listens only on the Tailscale adapter's addresses (IPv4 and tailnet IPv6) and 127.0.0.1, while Tailscale is connected (`desktop/RemotePage.kt`) |

### Resolution and screen geometry

GPG keeps the game area at an aspect it picks at launch (16:9 and 16:10 so far), so the phone's
1600x720 (20:9) can't be reproduced. Calibration is redone on the PC. A relaunch can change the
aspect (1740x978 came back as 1860x1162, and 1565x978 when 1740x978 was asked), and relaunching to
get the old one back breaks input until the PC restarts, so the bot works at the area it gets:

- Before every profile read, `FarmerService` brings Clash forward and, when the game area has the
  calibration's aspect, asks for the calibration's size (`fitSurface`); an area of another aspect
  is left at its own size. The sidecar caps a resize to the monitor's work area at the same aspect
  (`docs/SIDECAR.md`, resize), `preferredSurface` does the same sum for a new profile, and
  `PcProfiles.runSize` predicts the size a run reaches for Setup and the start check.
- `PcProfiles.derive` then moves the saved profile onto the size reached, with the anchors in
  `docs/PC-GEOMETRY.md`. At the calibrated size it returns the profile itself, so the owner's
  1740x978 runs are what they were. It refuses a shape outside `PcGeometry.isPc` or an area under
  720 rows, in words for the user. `RunGuard` and `LocalRunner` keep their equality checks, now
  against the moved size, and their stop texts are turned into sentences by
  `PcProfiles.plainStopReason`.
- The moved profile is never written to profile.json. Verification at another aspect lives in
  `accounts/<id>/surfaces.json` (`SurfaceVerification`): the moved profile counts as verified when
  the calibration is and its aspect is recorded there, which a supervised attack at that aspect
  does through the run's profile updater. Calibrate's Save deletes the file when it changes the
  points. A calibration not yet verified can't earn verification on a moved area, so an attack
  there, supervised or not, is refused with the advice to calibrate again on that area
  (`PcProfiles.startRefusal`).
- Calibrate, the tray check and Setup's Game window line use the same move (`docs/UI.md`).
- A new account can start from the standard positions, `app/src/main/resources/profile/
  pc-start-1740x978.json`: the owner's verified 1740x978 calibration with no collectors, wall or
  deployment points, and not verified. Calibrate moves it onto the game area shown with the same
  rule, the user checks the troop cards against their own tray, removes any hero or jump spell
  the army lacks (Not in my army), and saves, and it stays unverified
  until a supervised attack on that account.

The phone tree places taps and reads text with fractions of 1600x720, mostly in `ScreenTargets`,
`Builders`, `ArmyRecipes`, `WallPanel`, `UpgradeListNavigator` and `PopupGuard`. Some of them miss
on the PC (see the spike's Screen geometry section). Those files keep the phone values and gain PC
values measured on PC captures, and the frame's aspect picks between them. The phone's tests at
1600x720 run unchanged, PC tests run the same readers on PC captures, and each edited file is
listed in `docs/DIVERGENCES.md`.

## Build

`Build.ps1` at the repo root runs Gradle with the JDK and Gradle in `tools/` (gitignored). From a
git worktree it finds `tools/` in the main checkout. Default tasks: `:app:test` and the desktop
distributable. The sidecar builds with `dotnet` (SDK 8 is installed); the distributable publishes
it self-contained, without its .pdb, and copies the Visual C++ runtime DLLs from System32 beside
it, failing the build if one is missing.

The version is `appVersion` in `gradle.properties`. The build stamps it and the commit into
`build-info.properties` (`desktop/BuildInfo.kt`) and gives it to the exe. `Package.ps1` runs the
tests and the distributable and zips the app folder with `packaging/READ-ME-FIRST.txt` into
`dist\ClashFarmerPC-<version>-<date>.zip`.

## Safety rules for anyone driving the game

- Never spend gems. Never press a button whose dialog shows a gem price.
- Blood only. Don't sign accounts in or out.
- Never move the user's real cursor, except in the documented SendInput fallback.
- Bots violate Supercell's terms and risk a permanent ban; say so plainly where it matters.

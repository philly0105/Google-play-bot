# Compose UI

The phone's Android UI (file class 4 in `docs/PLAN.md`) is ported to Compose for Desktop under
`app/src/main/kotlin/local/clashfarmer/desktop/ui/`. It uses Compose Foundation only, no Material,
and adds no dependencies: Compose Desktop 1.8.2 and `kotlinx-coroutines-swing` are already on the
build. It compiles and runs, and `DashboardRenderTest` draws every tab, the account editor and
calibration off-screen (see Tests).

Every screen, sheet, editor, chart, chip, number and action of the phone UI is kept, with the
phone's wording except where it names Android things (see Wording). Nothing in
`local.clashfarmer.ui` was edited. The shared pure files there are used as they are:
`DashboardLoader`, `DashboardState`, the `FarmerTheme` tokens, `FarmingSettingsEditor`,
`AccountEditor.saveEnabled`, `Tab` and `TabBadge`, `components/` (`AccountColor`, `ChartScale`,
`ResourceBar.FULL_AT`, `Tone`, `UiText`, `ValueFit`) and `screens/` (`ComposerState`, `HomeCards`,
`SetupChecklist`, `StatsModel`), with `insight` for event and problem text.

## Files

Phone paths are under `android/app/src/main/java/local/clashfarmer/`; PC paths are under
`desktop/ui/`.

| Phone file | Compose file | Notes |
| --- | --- | --- |
| `MainActivity.kt` | `FarmerController.kt`, `FarmerWindow.kt` | The Activity's logic, and the window that draws it |
| `ui/DashboardScreen.kt` (the two interfaces) | `DashboardHost.kt` | No `activity` or `badgesChanged`; adds `filesDir`, `windowStatus` and `toast`; a screen has `@Composable Content()` in place of `view` |
| `ui/FarmerDashboard.kt` | `FarmerDashboard.kt`, `FarmerWindow.kt` | State in the first, drawing in the second |
| `CalibrationActivity.kt` | `calibration/CalibrationModel.kt`, `calibration/CalibrationTargets.kt`, `calibration/CalibrationScreen.kt` | |
| `CalibrationView.kt` | `calibration/CalibrationScreen.kt` | `FrameView` and `FrameFit` |
| `ui/FarmerTheme.kt` (the view factories) | `theme/FarmerColors.kt`, `theme/FarmerText.kt` | `Label`, `farmerStyle`, `FarmerSize`, `FarmerDesktopTheme` |
| `res/drawable/ic_farmer.xml` | `theme/FarmerIcon.kt` | `FarmerIcon` (an ImageVector) and `farmerIconPainter()` |
| `ui/AccountEditor.kt` | `AccountEditorSheet.kt` | `AccountEditorModel`, `AccountEditorSheet`, `digitsTyped` |
| `ui/RaidsSheet.kt`, `ui/TrayCheckSheet.kt`, `ui/CsvExport.kt` | Same names | |
| `ui/BarView.kt`, `ui/RingsView.kt` | `components/Meters.kt` | `FillBar`, `Rings` |
| `ui/screens/{Home,Run,Stats,Activity,Setup}Screen.kt` | `screens/`, same names | |
| `ui/components/BarChartView.kt` | `components/BarChart.kt` | `BarChart`, `ChartGroup`, `ChartBar` |
| `ui/components/HBarView.kt` | `components/HBarChart.kt` | `HBarChart`, `HBarRow` |
| `ui/components/Sheet.kt` | `components/Sheet.kt` | `Sheet`, `MessageDialog` (AlertDialog), `TimeDialog` (TimePickerDialog) |
| `ui/components/ResourceBar.kt` | `components/ResourceBar.kt` | `ResourceBar`, and `ResourceFormat` for the companion's pure parts |
| `ui/components/ScreenLayout.kt` | `components/ScreenLayout.kt` | `ScreenPage`, `ScreenTitle`, `Card`, `ToneCard`, `Divider`, `Gap` |
| `ui/components/Buttons.kt` | `components/Buttons.kt` | Adds `Modifier.hoverClick` and `TextLink` |
| `ui/components/Chip.kt` | `components/Chip.kt` | Adds `Dot` |
| `ui/components/{CheckRow,SectionHeader,Segmented,StatTile,Stepper,TabIcon,ToggleRow}.kt` | `components/`, same names | |
| Android's `EditText` | `components/TextInput.kt` | |
| None | `PcChecklist.kt` | Setup's Game window, Text reader, Tray icon and Remote page lines |

Pure logic that lived inside class 4 files is ported, not moved: `CALIBRATION_TARGETS` and
`applyTarget` (CalibrationActivity's target list and Apply), `FrameFit` (CalibrationView's fit),
`ResourceFormat` (ResourceBar's companion), `digitsTyped` (what Android's number keyboard let
through) and `parseClock` (the time dialog). No verbatim file needed any of it.

## Controller and window

`FarmerController` is MainActivity without the Activity. It owns the dashboard, reads the files
once a second on IO while the window is shown, binds on the Swing thread, and carries out every
action string. `FarmerWindowContent` draws it.

```kotlin
class FarmerController(
    filesDir: File,
    windowStatus: StateFlow<WindowStatus>,
    initialTab: Tab = Tab.HOME
) : DashboardHost {
    fun windowShown()                    // the phone's onResume: rebind now, then read every second
    fun windowHidden()                   // onPause: no reads while nobody can see the window
    fun externalAction(action: String)   // tray, overlay or Windows notification; logs new_intent_action
    fun close()                          // onDestroy
    // Read by FarmerWindowContent: dashboard, currentToast, alerts, editor, calibration
}

@Composable fun FarmerWindowContent(controller: FarmerController)
```

`Main.kt` builds it inside `application`, on the Swing thread, after `onServiceConnected()`, and
draws the window only while it is open, so `FarmerWindowContent` calls `windowShown()` when the
window opens and `windowHidden()` when it closes to the tray. The controller calls `AppLog.init`
again; the log replays its history only on the first call, so that is harmless.

The controller never sets `FarmerService.helperActivityVisible` and never touches the overlay's
visibility. The overlay is its own window, and the PC bot works the game in the background.

The PC runs only Blood. `pcAccounts` drops every other account from what the controller reads,
both the tick it binds and its starts, so Sweat's calibration and wall lines never sit in Setup
or Activity, Home has no second-account card, and Run greys out Both. Sweat's record and files
stay on disk. `Main.kt` selects Blood at each start, and calibration falls back to Blood. The
built-in "Both accounts" preset is kept and refused with "Both needs two accounts".

## Actions

Every action but `stop`, `pause`, `edit:` and `history` is refused while a run or an inspection
is on, with the toast "Wait for the current operation or stop the bot first." and the log event
`handle_action_busy`. Every action logs `handle_action`; a failure logs `handle_action_error` and
shows "Could not start" with the reason, such as the service's "The PC runs only Blood, not
Sweat".

| Action | What it does |
| --- | --- |
| `stop` | `FarmerService.stop(UserStop.DASHBOARD)` |
| `pause` | Resumes when paused, through `FarmerService.resumeFromNotification("dashboard")`; otherwise pauses, and only while a run is busy. Logs `pause_button` |
| `settings` | Opens Setup. The phone opened Android's accessibility settings |
| `launch_clash` | New. `FarmerService.launchClash()`, sent by the Game window line's fix |
| `calibrate` | Opens calibration for the selected account |
| `inspect` | Captures the game, saves `captures/inspect-<time>.png`, reads it, and shows the report |
| `supervised`, `walls` | One supervised attack, or walls only, on the selected account |
| `plan:<json>` | Checks readiness against the files again, starts the plan, keeps it as the last one |
| `select:<id>` | Selects the account |
| `switch_only:<id>` | Switches to the account without farming |
| `acknowledge_wall:<id>` | Asks "Acknowledge pending purchase", then clears the marker. Logs `pending_purchase_acknowledged` |
| `edit:<id>` | Opens the account editor; open during a run too, with Save off |
| `history` | Opens Stats |

The phone's `intent_action_rejected` ("replayed from recents") is gone: Windows has no recents
to replay an action from. The UI logs 16 event names, all of them ones `insight/EventText.kt`
knows, as `EventTextTest.everyLoggedEventHasAPlainSentence` requires.

## Service members

The UI calls `FarmerService.launchClash()` for Setup's Open Clash of Clans. It starts Clash on the
service's scope and returns at once, and sends nothing while a run is active or a Clash window
exists, following the rule in `docs/gpg-spike.md` (Restarting). Calibration calls
`captureNormalizedVillage(profile)`, whose helper-class parameter now defaults to null.

Everything else it calls is the phone's: `instance`, `isBusy`, `inspecting`, `runGuard`,
`latestFrame`, `overlay`, `pendingStart`, `cancelPendingStart`, `scheduleStart`, `startPlan`,
`startPlanCombined`, `startSwitchOnly`, `stop`, `captureClashGame`, `readDeviceHealth` and
`remoteKey`.

## Tray and status panel

Main adds a tray icon when Windows has a notification area. Its tooltip is the run's title and
phase, double-clicking it opens the window, and its menu holds Open Clash Farmer, Pause or Resume
and Stop while a run is busy (`FarmerService.pauseFromNotification`, `resumeFromNotification` and
`stopFromNotification`, the phone's ongoing notification buttons), Status panel, and Quit. Result
notices from `RunNotifier` show as Windows notifications from the icon.

Closing the window leaves the bot running behind the icon, as the phone's service outlived its
screen, and says so in a notification the first time. Quit stops any run and ends the app. With
no notification area, closing the window quits.

One app runs per files directory (`SingleInstance.kt`, the first thing `main` does). It holds an
OS lock on `app.lock`, which Windows drops when the process ends, crash or not, and listens on a
loopback port written to `app.port`. A second start sends "open" there and ends; the first opens
its window as the tray's Open does. If the first never answers, the second says "Clash Farmer is
already running. Open it from its icon in the notification area." A loopback port that can't be
opened, as when security software blocks it, gets its own start message rather than the one for a
files directory that can't be written. Opening the window, from the
tray or a second start, sets it always-on-top for a moment, since Windows otherwise leaves a
window the person didn't click behind the active one.

A failure nobody caught (`CrashReport.kt`) goes to `logs/desktop.log` with its stack and shows a
dialog with OK and Open logs folder; the text names the log once it exists. One on the main
thread, which starts the app and runs its windows, ends the app. One elsewhere leaves it running
and shows at most once every 10 minutes. A failure inside the main window closes that window, as
Compose's own handler does, and the tray opens a fresh one; its dialog shows every time, so a
window that fails as it opens never just vanishes. CrashReport and DesktopServices share one
`DesktopLog` for the file, so they can't both rotate it at once. At start, a files directory that
can't be written or a missing sidecar exe gets its own message saying what to do.

`StatusPanel.kt` draws `FarmerOverlay.panel` as a window over the others in the bottom right
corner of the main screen, while a run is busy or a start is set and the tray's Status panel is
ticked. That item starts unticked at every launch, since the user found the pill in the way. The
panel never takes focus and has no taskbar button.

## Changes from the phone

Shell:

- A tab rail down the left, 88 dp, in place of the bottom bar. The run strip sits across the top
  of every tab but Home while a run is active, as on the phone.
- Toasts show at the foot of the page, one at a time, for 2 s or 3.5 s.
- Sheets are dialogs 680 dp wide. Escape closes them, as Back did; a click outside does not, so
  an editor never loses its edits to a stray click.
- Alerts show one at a time, the newest first; closing one shows the one under it, as Android
  stacked them. Escape and a click outside close one without its action, and its text can be
  selected and copied.
- Controls are 40 dp tall and the main action 44 dp, down from the 48 dp finger target. Buttons
  show a hand cursor and a hover wash. Pages are at most 880 dp wide, forms 640 dp.

Home:

- The live frame keeps the frame's own shape: the frame's size, else the measured game surface,
  else 16:9 until either is known. The phone fixed its box at 2.22:1, which was its own 1600x720
  frame, so this is the same rule. A 16:10 surface such as 1860x1162 gives a taller box, about
  530 dp in the 1100x760 window; Pause and Stop stay in view and the page scrolls.
- Frames are decoded off the UI thread and drawn through `toComposeImageBitmap()`.

Run:

- Presets wrap onto as many lines as they need; the phone scrolled them sideways. A right-click
  offers "Rename or delete", the phone's long press.
- Start at and End by open `TimeDialog`: type HH:MM (8:30, 0830 and 8.30 also read) or use the
  hour and minute steppers. Enter or Set confirms.

Stats:

- Export opens a save dialog on Downloads with the export's file name and writes through a
  temporary file, so a failed write keeps an older file. The toast names the folder chosen.

Setup:

- The machine section is "This PC". Its Game window and Tray icon lines replace the phone's
  accessibility and notification lines, Text reader is new, and Remote page replaces the phone's
  remote line; battery and the per-account lines are kept.
- Game window reads the window watcher. Before the first lookup it is not read and not counted.
  A failed lookup is red with the reason. No window is amber, "Not open", with the fix "Open
  Clash of Clans", which sends `launch_clash`. A window closed by hand is red, "Closed", with no
  fix: Google Play Games keeps the game running hidden, and opening it again is the relaunch that
  broke input, so the line says to restart the PC. A minimized window is fine. A found window gives
  the game area at its live size, such as "Found. The game area is 1860x1162."; nothing in the UI
  assumes 16:9.
- Text reader is the sidecar's OCR, from `SidecarStatus`, which asks the sidecar's hello every
  3 s while the models load and every 60 s after. Not read or "Loading" is not counted. A failed
  load is red, "Failed", with the sidecar's reason (usually a DLL that would not load) and what to
  do: unzip again into a new folder, then install the Visual C++ Redistributable (x64).
- Tray icon is `java.awt.SystemTray.isSupported()`: "On", or amber "Off" with no fix.
- Remote page replaces the phone's remote line and is never counted: "Off (Tailscale not
  connected)" in grey; amber when port 8765 is taken, or when Windows won't bind the Tailscale
  address yet (as after sleep), each with its own text and retry time; or "On" with the Tailscale
  IPv4 address.
- The battery line is the service's last sidecar reading. The machine lines are read on IO and
  kept for 30 s.
- Copy link shows only while the remote page is on, and puts the link on the AWT clipboard. The
  key is never shown or logged.
- The foot of the page names the build ("Clash Farmer PC 1.1.0 (commit). Settings and logs are
  in ...", `BuildInfo.kt`) beside Open logs folder.

Tray check:

- The picture cards wrap to the sheet's width; the phone fitted four to a row.

Inspect and captures:

- `inspect`, the tray check and calibration capture the window where it is, without the phone's
  return-to-activity step.
- Resume: the phone brought Clash to the front and resumed 1.5 s later. The PC never brings the
  window forward; it starts Clash only when the window is gone, as the tray's Resume does, and
  resumes 1.5 s later.
- The inspect report shows in a monospace alert.

Account editor:

- A number box drops anything but digits as it is typed or pasted, which is all the phone's
  number keyboard offered. Text already in the box stays, so a stored "2.5" still shows and Save
  can say why it is refused.

Calibration:

- It fills the window in place of the tabs while open: the frame on the left, a 340 dp sidebar
  with the target list, actions and status on the right.
- File reads, writes and captures run on IO, with every button but Back off until they end.
- The default profile, before the saved one is read and for an account without one, is at the
  game surface's live size when the watcher has measured it, else the phone's 1600x720. This also
  lets `captureNormalizedVillage` find the display it waits for on a new account.
- Marks on the frame are sized in dp, not the phone's pixels.
- On a PC frame, a landscape one from 1.3:1 to under 2:1 (`PcGeometry.isPc`), the boxes Apply
  draws around a click are the PC's size: the phone's times 1.13 at 1740x978, scaled with the
  frame's height. That is a loot row 168x34 px, the countdown 114x38, the fee 100x34, a balance
  220x40 and a card area 75x116, where the phone's are 148x30, 100x34, 88x30, 194x36 and 67x102.
  The PC draws the scout's panels, the village balances and the tray's cards that much larger
  (`audit-evidence/spike/geo-scout.png` and `geo-home.png`). A wall price's box is the phone's
  100x30 at 1740x978, as the PC draws a selected building's buttons at the phone's pixel size
  there; no price has been read on the PC to confirm it. A phone frame keeps the phone's boxes.

## Wording

| Phone | PC |
| --- | --- |
| "Enable Accessibility Service first!", "Accessibility Service is not enabled!", "Turn on the accessibility service in Setup", "Turn on the accessibility service first." | "The bot service is not running." |
| "Switching to Clash and capturing..." and "Switching to Clash and capturing game frame..." (overlay status) | "Capturing the game screen..." |
| "Selected: None (tap image)" | "Selected: None (click the image)" |
| "Tap a point on the screenshot first" | "Click a point on the screenshot first" |
| The 38 calibration targets' "Tap ..." | "Click ..."; the viewport corners drop "(excluding status bar)" and "(excluding nav bar)" |
| "... is no longer on this phone" | "... is no longer on this PC" |
| "Export failed. Check free space on the phone." | "Export failed. Check free space on the disk." |
| "Saved to Downloads" | "Saved to <folder>", which is "Saved to Downloads" when that folder is kept |
| "The phone must be on and unlocked then" | "The PC must be on and unlocked then" |
| Setup's "Phone" | "This PC" |
| "Runs on this phone. ..." and "No accounts are set up on this phone." | "this PC" |
| "Saves the run as set now. Tap it later to load it; press and hold to rename or delete." | "Saves the run as set now. Click it later to load it; right-click it to rename or delete." |
| "Tap a bar to see that session's raids.", "Tap a row for that battle's details.", "Tap one for everything it recorded." | "Click ..." |

Gone with Android: the MediaStore names and messages, "Could not open that screen.", the clip
label "Clash Farmer remote link" and its sensitive flag, and the `intent_action_rejected` reason.

The phone's screen-reader labels are kept: "Increase" and "Decrease" on the steppers, "Bar
chart", "<section>, show or hide" on the editor's section headers, and each number box's label.

Phone wording remains in shared files the UI shows but this port must not edit (line numbers as
of main at b514d40):

- `core/RunPlan.kt:284` "Turn on the accessibility service in Setup", the readiness reason when
  the service is not connected.
- `core/Policy.kt:330` and `insight/CalibrationHealth.kt:77`, `:220` "on this phone".
- `core/DeviceGuard.kt:32` "Phone too hot"; `ui/screens/HomeCards.kt:117` "Let the phone cool or
  charge".
- `core/AppSwitchLatch.HOLD_TIMEOUT_REASON` (the notification shade).
- `core/TrayCheck.kt:113` "tap Attack", and `:253`-`254` "Tap the middle of the right card again
  in Calibrate".
- `insight/EventText.kt:168`-`171` (the notification shade), `:192`, `:444`-`445`, `:530`,
  `:546` ("Phone guard"), `:575`, `:579` ("the phone was locked") and `:600` ("a touch on the
  screen"); `insight/Problems.kt:127`, `:129`, `:165`.

`ui/screens/SetupChecklist.kt:95` "Starts with the accessibility service." is no longer shown:
`PcChecklist.remote` draws Setup's remote line.

## Tests

| Test | Covers | Tests |
| --- | --- | --- |
| `desktop/ui/PcChecklistTest.kt` | The Game window, Text reader, Tray icon and Remote page lines, including a 1860x1162 surface, a window closed by hand and a reader that failed to load, and how they count toward ready, the tab dot and the primary fix | 15 |
| `desktop/SingleInstanceTest.kt` | The lock per files directory, its release, a later start raising the first, the same from a second JVM, and the start messages | 10 |
| `desktop/CrashHandlerTest.kt` | Which failures end the app, the dialog's text, its 10-minute limit, and window failures that skip it | 5 |
| `desktop/BuildInfoTest.kt` | The version and About text, and the stamped version matching gradle.properties | 3 |
| `desktop/ui/AccountEditorInputTest.kt` | `digitsTyped` and the editor's box order | 7 |
| `desktop/ui/calibration/CalibrationTest.kt` | The 38 targets, `applyTarget` on phone and PC frames, its PC boxes against the OCR of PC captures, and `FrameFit` at 16:9 and 16:10 | 14 |
| `desktop/ui/components/ParseClockTest.kt` | `parseClock` | 3 |
| `desktop/ui/DashboardRenderTest.kt` | `pcAccounts`, and every tab, the account editor and calibration drawn off-screen at 1100x760 into `app/build/ui-renders` | 2 |
| `desktop/RunTrayTest.kt` | `RunNotifier` through `RunTray`, and the panel's status text, idle shape and Stop | 5 |

## Open points

- A window closed by hand reads as "Closed" from the sidecar's `visible` flag, following the
  spike's finding that closing hides the window. That has not been seen live, since closing it
  breaks input until the PC restarts. Whether quitting Google Play Games from its tray icon also
  gives a clean start is untested, so the line names only the restart.
- The remote link on the clipboard can be kept by Windows clipboard history or cloud clipboard.
  Android marked the clip sensitive; Java has no such mark.
- Every screen draws off-screen without a layout failure. The window has run on this PC since 24
  October, and runs posted their notifications through the tray. The user has seen the status
  panel's pill during a run, but nobody has checked the tray icon or the panel's open card on the
  screen.

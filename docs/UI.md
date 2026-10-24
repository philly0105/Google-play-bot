# Compose UI

The phone's Android UI (file class 4 in `docs/PLAN.md`) is ported to Compose for Desktop under
`app/src/main/kotlin/local/clashfarmer/desktop/ui/`. It uses Compose Foundation only, no Material,
and adds no dependencies: Compose Desktop 1.8.2 and `kotlinx-coroutines-swing` are already on the
build. None of it has been compiled or run yet; the agent sandbox that wrote it blocks Gradle.

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
| None | `PcChecklist.kt` | Setup's Game window and Tray icon lines |

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

Main.kt builds it inside `application`, on the Swing thread, after `onServiceConnected()`:

```kotlin
application {
    val controller = remember { FarmerController(services.filesDir, services.host.window) }
    Window(
        onCloseRequest = { controller.close(); service.onDestroy(); services.close(); exitApplication() },
        title = "Clash Farmer PC",
        icon = farmerIconPainter(),
        state = rememberWindowState(size = DpSize(1100.dp, 760.dp))
    ) {
        FarmerWindowContent(controller)
    }
}
```

`FarmerWindowContent` calls `windowShown()` when it enters the composition and `windowHidden()`
when it leaves. A window hidden to the tray without leaving the composition should call them
itself. Tray menu items send through `externalAction`, for example
`Item("Stop") { controller.externalAction("stop") }`. The controller calls `AppLog.init` again;
the log replays its history only on the first call, so that is harmless.

The controller never sets `FarmerService.helperActivityVisible` and never touches the overlay's
visibility. The overlay is its own window, and the PC bot works the game in the background.

## Actions

Every action but `stop`, `pause`, `edit:` and `history` is refused while a run or an inspection
is on, with the toast "Wait for the current operation or stop the bot first." and the log event
`handle_action_busy`. Every action logs `handle_action`; a failure logs `handle_action_error` and
shows "Could not start" with the reason, such as the service's "The PC runs only Blood, not
Sweat".

| Action | What it does |
| --- | --- |
| `stop` | `FarmerService.stop(UserStop.DASHBOARD)` |
| `pause` | Resumes when paused; otherwise pauses, and only while a run is busy. Logs `pause_button` |
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

## Stream B

The UI needs one new FarmerService member, marked `// stream B:` in `FarmerController.kt`:

- `fun launchClash()`. Opens Clash of Clans in Google Play Games through the sidecar's `launch` op
  on the service's scope and returns at once. It should send nothing when a Clash window exists,
  following the rule in `docs/gpg-spike.md` (Restarting).

Optional: `captureNormalizedVillage(profile, returnToActivityClass: Class<*>)` could default
`returnToActivityClass` to null, as `captureClashGame` does. Calibration passes
`CalibrationModel::class.java`, which the PC ignores, so it compiles as it is.

Everything else the UI calls exists on main: `instance`, `isBusy`, `inspecting`, `runGuard`,
`latestFrame`, `overlay`, `pendingStart`, `cancelPendingStart`, `scheduleStart`, `startPlan`,
`startPlanCombined`, `startSwitchOnly`, `stop`, `captureClashGame`, `readDeviceHealth` and
`remoteKey`.

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
  accessibility and notification lines; battery, remote and the per-account lines are kept.
- Game window reads the window watcher. Before the first lookup it is not read and not counted.
  A failed lookup is red with the reason. No window is amber, "Not open", with the fix "Open
  Clash of Clans", which sends `launch_clash`. A minimized window is fine. A found window gives
  the game area at its live size, such as "Found. The game area is 1860x1162."; nothing in the UI
  assumes 16:9.
- Tray icon is `java.awt.SystemTray.isSupported()`: "On", or amber "Off" with no fix.
- The battery line is the service's last sidecar reading. The machine lines are read on IO and
  kept for 30 s.
- Copy link puts the remote link on the AWT clipboard. The key is never shown or logged.

Tray check:

- The picture cards wrap to the sheet's width; the phone fitted four to a row.

Inspect and captures:

- `inspect`, the tray check and calibration capture the window where it is, without the phone's
  return-to-activity step. Resume only resumes: the phone brought Clash to the front and resumed
  1.5 s later, and the PC never takes focus.
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
- `ui/screens/SetupChecklist.kt:95` "Starts with the accessibility service.", on Setup's remote
  line.

## Tests

| Test | Covers | Tests |
| --- | --- | --- |
| `desktop/ui/PcChecklistTest.kt` | The Game window and Tray icon lines, including a 1860x1162 surface, and how they count toward ready, the tab dot and the primary fix | 10 |
| `desktop/ui/AccountEditorInputTest.kt` | `digitsTyped` and the editor's box order | 7 |
| `desktop/ui/calibration/CalibrationTest.kt` | The 38 targets, `applyTarget`, and `FrameFit` at 16:9 and 16:10 | 11 |
| `desktop/ui/components/ParseClockTest.kt` | `parseClock` | 3 |

## Open points

- The Game window fix and the hand-closed window. `GameWindow.Find()` in the sidecar does not
  check visibility, so a Clash window closed by hand (hidden, with crosvm still running) should
  still read as found, and the line shows "Open" for a window nobody can see. The fix is not
  offered then, which avoids the relaunch that broke input, but the line is wrong. A `visible`
  flag from `find` would let it say the window is closed and how to start clean.
- Resume. The dashboard's Resume calls `runGuard.resume()` and nothing else, as the brief asked.
  Main's `FarmerService.resumeFromNotification()`, public for the tray, first starts Clash when
  its window is gone and resumes after a wait. So a run paused with the window gone resumes into
  a missing window from the dashboard but not from the tray.
- The remote link on the clipboard can be kept by Windows clipboard history or cloud clipboard.
  Android marked the clip sensitive; Java has no such mark.
- Apply's fixed pixel boxes are the phone's 1600x720 sizes and do not scale with the surface: the
  card area about 67 px, a loot row 148x30, the countdown 100x34, the fee 88x30, a balance 194x36
  and a wall cost 100x30.
- Not compiled or run. The likeliest compile errors are in Compose API details checked against
  memory rather than the jars, listed in the hand-off report.

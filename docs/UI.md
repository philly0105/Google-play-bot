# Compose UI

The phone's Android UI (file class 4 in `docs/PLAN.md`) is ported to Compose for Desktop under
`app/src/main/kotlin/local/clashfarmer/desktop/ui/`. It uses Compose Foundation only, no Material,
and adds no dependencies: Compose Desktop 1.8.2 and `kotlinx-coroutines-swing` are already on the
build. It compiles and runs, and `DashboardRenderTest` draws every tab, the account editor,
calibration and the whole window off-screen (see Tests).

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

`Main.kt` builds it inside `application`, on the Swing thread, after `onServiceConnected()`, and
draws the window only while it is open, so `FarmerWindowContent` calls `windowShown()` when the
window opens and `windowHidden()` when it closes to the tray. The controller calls `AppLog.init`
again; the log replays its history only on the first call, so that is harmless.

The controller never sets `FarmerService.helperActivityVisible` and never touches the overlay's
visibility. The overlay is its own window, and the PC bot works the game in the background.

The PC runs only Blood. `pcAccounts` drops every other account from what the controller reads,
both the tick it binds and its starts, so Sweat's calibration and wall lines never sit in Setup
or Activity, and Home has no second-account card. With one account Run hides the account
picker, the Both preset and the Switch account tool (`shownPresets`), and Stats hides its
All/account filter. Sweat's record and files stay on disk. `Main.kt` selects Blood at each start, and calibration falls back to Blood. The
built-in "Both accounts" preset is kept and refused with "Both needs two accounts".

## Actions

Every action but `stop`, `pause`, `edit:` and `history` is refused while a run or an inspection
is on, with the toast "Wait for the current operation or stop the bot first." and the log event
`handle_action_busy`. Every action logs `handle_action`; a failure logs `handle_action_error` and
shows an alert titled for what was asked (`actionFailure`): "Could not start the run" for
`plan:`, `supervised` and `walls`, "Could not choose that account" for `select:` and
`switch_only:`, "Could not clear the marker" for `acknowledge_wall:`, else "Something went
wrong". Its message is the reason when the code worded one, such as the service's "The PC runs
only Blood, not Sweat."; any other exception reads "Something went wrong. The Technical log on
the Activity tab has the details." and its text stays in the log.

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
- Tab moves between controls, and the one with focus has a 2 dp ring: gold, or the text colour
  on a gold button and on the selected segment. A click focuses a control too, so the ring shows
  only after a key press and goes at the next mouse press (`KeyboardNav`, watched on the window's
  and each dialog's root). Enter or Space presses it. Up and Down step a stepper, Left and Right
  move a segmented choice. Ctrl+1 to Ctrl+5 open the tabs, and Ctrl+Tab and Ctrl+Shift+Tab step
  through them, wherever focus is (`tabForKey`). A tab change drops the old page with whatever
  had focus in it, so the window takes focus back then and the shortcuts keep working.
- A disabled main button keeps the glass fill with faint text, not a dimmed gold.
- Home's and Run's start buttons sit in a footer under a hairline that stays put while the page
  scrolls. When Start is off, the reason shows above it in plain words, with a link to the fix
  where there is one (`StartFix`): "Calibrate Blood >" opens calibration on the account, "Run a
  supervised attack >" opens Run's tools (from Home, the Run tab), and a reason that names Setup
  opens Setup. With
  the bot not running the reason reads "Clash Farmer is still starting..." in place of the
  readiness check's accessibility line. The toasts that say so stay up for the long time.
- The save dialog of Export CSV and the tray menu are Windows' own and follow its theme, not the
  app's dark one. That is known and kept.

Home:

- The live frame keeps the frame's own shape: the frame's size, else the measured game surface,
  else 16:9 until either is known. The phone fixed its box at 2.22:1, which was its own 1600x720
  frame, so this is the same rule. A 16:10 surface such as 1860x1162 gives a taller box, about
  530 dp in the 1100x760 window; Pause and Stop stay in view and the page scrolls.
- Frames are decoded off the UI thread and drawn through `toComposeImageBitmap()`.
- While idle, the chip by the title is the game window's state (`gameChip`), toned as Setup's
  Game window line: "Clash open" or "Clash minimized" green, "Looking for Clash" grey, "Clash
  not open" amber, "Clash closed" or "Clash not found" red. An amber or red chip opens Setup.
  While the bot is not up yet it reads an amber "Starting" instead, which opens nothing
  (`headerChip`). While a run is going it shows the run's state as before.
- A resource the bot has not read shows "Not read" on its bar and a faint dash in its tile, not
  a zero.

Run:

- Presets wrap onto as many lines as they need; the phone scrolled them sideways. A right-click
  offers "Rename or delete", the phone's long press, and hovering one says so.
- The steppers fill the form's width.
- The one-attack tool is "Supervised attack (one attack you watch)", the name the reasons and
  Setup use.
- Start at and End by open `TimeDialog`: type HH:MM (8:30, 0830 and 8.30 also read) or use the
  hour and minute steppers. Enter or Set confirms.

Stats:

- Export opens a save dialog on Downloads with the export's file name and writes through a
  temporary file, so a failed write keeps an older file. The toast names the folder chosen.
- The title row holds Export CSV at its right, as a small button.

Activity:

- The Problems tab reads "Problems (2)" with the count.
- Clear resolved is a small button on the right and clears only past events (`clearable`). A
  calibration or settings finding stays until it is fixed, since it is still true and the
  incomplete ones stop runs; on the phone Clear resolved hid it for 30 days. Each one still has
  its own Dismiss in the Problems view, for an advisory finding such as uneven card spacing.
- A calibration or settings finding's button goes to its fix (`findingFix`): "Calibrate" opens
  calibration on the account when the calibration is missing, incomplete, has copied points or
  uneven card spacing; "Open Run" when it is not yet verified, which takes a supervised attack;
  "Open Setup" for an unreachable goal or the wall setup.

Raids:

- A battle's ids sit under Details, headed "For a bug report: select an id to copy it.", below
  what the battle did.

Setup:

- The machine section is "This PC". Its Game window and Tray icon lines replace the phone's
  accessibility and notification lines; battery, remote and the per-account lines are kept.
- Game window reads the window watcher. Before the first lookup it is not read and not counted.
  A failed lookup is red with the reason. No window is amber, "Not open", with the fix "Open
  Clash of Clans", which sends `launch_clash`. A window closed by hand is red, "Closed", with no
  fix: Google Play Games keeps the game running hidden, and opening it again is the relaunch that
  broke input, so the line says to restart the PC. A minimized window is fine. A found window gives
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
| "Enable Accessibility Service first!", "Accessibility Service is not enabled!", "Turn on the accessibility service in Setup", "Turn on the accessibility service first." | "Clash Farmer is still starting. If this stays, quit it from the tray icon and open it again." |
| "Test one attack (supervised)" | "Supervised attack (one attack you watch)"; the overlay says "Starting a supervised attack on Blood..." and "Starting walls only on Blood..." |
| "Inspect Result: VILLAGE_HOME" | "What the bot sees"; the report says "village home" |
| "Inspect failed: <exception>" | "Inspect could not read the game window. Make sure Clash is open, then try again." |
| "Please calibrate the profile first.", "Unknown account", "Account not found" | "Blood is not calibrated. Open Setup and calibrate it first.", "That account is gone. Choose one on Home." |
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

The stop reasons, the battery guard's lines and "on this phone" in `insight/EventText.kt`,
`insight/Problems.kt`, `insight/CalibrationHealth.kt`, `core/Policy.kt`, `ui/screens/HomeCards.kt`
and `service/RunNotice.kt` now say PC things; each edit has a row in `docs/DIVERGENCES.md`. The
notifications and the status panel show a stop through `EventText.stopLine`, not the raw reason.
A start set for later that did not run keeps its "Farm at 07:00: " and gives its reason in PC
words (`RunNotifier.scheduledReason`): "Clash Farmer was closed before the start time; set the
start again" for the helper phrases, and a refused calibration as Run words it.

Phone wording remains in shared files (line numbers as of 3c438fb):

- `core/RunPlan.kt:284` "Turn on the accessibility service in Setup", the readiness reason when
  the service is not connected. Home and Run show "Clash Farmer is still starting..." in its
  place (`StartFix.plainReason`).
- `core/DeviceGuard.kt:32` "Phone too hot". The PC has no temperature reading, so it cannot fire.
- `core/AppSwitchLatch.HOLD_TIMEOUT_REASON` and `insight/EventText.kt:168`-`171` and `:577` (the
  notification shade), which the PC does not detect.
- `insight/EventText.kt:612` "a touch on the screen cancelled a tap".
- `core/TrayCheck.kt:113` "tap Attack", and `:253`-`254` "Tap the middle of the right card again
  in Calibrate".
- `ui/screens/SetupChecklist.kt:95` "Starts with the accessibility service.", on Setup's remote
  line.

## Tests

| Test | Covers | Tests |
| --- | --- | --- |
| `desktop/ui/PcChecklistTest.kt` | The Game window and Tray icon lines, including a 1860x1162 surface and a window closed by hand, and how they count toward ready, the tab dot and the primary fix | 11 |
| `desktop/ui/AccountEditorInputTest.kt` | `digitsTyped` and the editor's box order | 7 |
| `desktop/ui/calibration/CalibrationTest.kt` | The 38 targets, `applyTarget` on phone and PC frames, its PC boxes against the OCR of PC captures, and `FrameFit` at 16:9 and 16:10 | 14 |
| `desktop/ui/components/ParseClockTest.kt` | `parseClock` | 3 |
| `desktop/ui/DashboardRenderTest.kt` | `pcAccounts` and `shownPresets` with Blood alone; every tab, the account editor and calibration drawn off-screen at 1100x760, and the whole window at 100%, at 150% in 1280x650 dp and with a run going, into `app/build/ui-renders`; `tabForKey`, and the tab keys after the page holding focus is dropped | 5 |
| `desktop/ui/StartFixTest.kt` | `StartFix` for each readiness reason, `plainReason`, `shownPresets`, `gameChip` and `headerChip` | 9 |
| `desktop/ui/ControllerTextTest.kt` | `actionFailure`'s titles and messages, and `readableState` | 3 |
| `desktop/ui/ActivityLinksTest.kt` | `clearable` and `findingFix` | 2 |
| `desktop/ui/components/KeyControlsTest.kt` | `stepKey` and `nextOption` | 2 |
| `desktop/ui/components/FocusRingTest.kt` | A clicked button drawn without its focus ring until a key is pressed (`KeyboardNav`) | 1 |
| `desktop/RunTrayTest.kt` | `RunNotifier` through `RunTray`, its stop notice in `stopLine`'s words, the scheduled-start notice's `scheduledReason`, and the panel's status text, plain lines, idle shape and Stop | 10 |

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

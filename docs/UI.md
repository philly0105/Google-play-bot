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
| None | `PcChecklist.kt` | Setup's Game window, Windows, Text reader, Tray icon, Power and Remote page lines |
| None | `FirstRun.kt` | The one-time "Before you start" dialog and the ban warning Setup shows too |

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

@Composable fun FarmerWindowContent(controller: FarmerController, onQuit: () -> Unit)
```

On a fresh install `FarmerWindowContent` shows "Before you start" (`FirstRun.kt`) over
everything while `first-run.json` is missing from the files directory: the ban risk in one
sentence, then three numbered steps (open Clash of Clans in Google Play Games, calibrate your
account in Setup, run one supervised attack and watch it). "I understand" writes the file and
opens Setup; Quit calls `onQuit`, Main's quit. Escape and a click outside do nothing
(`MessageDialog(buttonsOnly = true)`), so the flag is written only on purpose. The owner's data
folder had no flag either, so the owner sees it once.

`Main.kt` builds it inside `application`, on the Swing thread, after `onServiceConnected()`, and
draws the window only while it is open, so `FarmerWindowContent` calls `windowShown()` when the
window opens and `windowHidden()` when it closes to the tray. The controller calls `AppLog.init`
again; the log replays its history only on the first call, so that is harmless.

The controller never sets `FarmerService.helperActivityVisible` and never touches the overlay's
visibility. The overlay is its own window, and the PC bot works the game in the background.

The PC runs one account, `PcAccount.ID` (Blood's id slot, so the owner's files stay put).
`pcAccounts` drops every other account from what the controller reads, both the tick it binds and
its starts, so the owner's second phone account never sits in Setup or Activity, and Home has no
second-account card. With one account Run hides the account picker, the Both preset and the
Switch account tool (`shownPresets`), and Stats hides its All/account filter. That account's
record and files stay on disk. `Main.kt` calls `PcAccount.seedIfFresh` before `ensureMigrated`,
so a fresh install gets one neutral account, "Your village", and never the phone's two; then it
selects `PcAccount.ID` at each start, and calibration falls back to it. The built-in "Both
accounts" preset is kept and refused with "Both needs two accounts".

## Actions

Every action but `stop`, `pause`, `edit:` and `history` is refused while a run or an inspection
is on, with the toast "Wait for the current operation or stop the bot first." and the log event
`handle_action_busy`. Every action logs `handle_action`; a failure logs `handle_action_error` and
shows an alert titled for what was asked (`actionFailure`): "Could not start the run" for
`plan:`, `supervised` and `walls`, "Could not choose that account" for `select:` and
`switch_only:`, "Could not clear the marker" for `acknowledge_wall:`, else "Something went
wrong". Its message is the reason when the code worded one, such as the service's "Clash Farmer
runs one account on this PC, and Sweat is not it." or, for an unattended start before the
account is read, "Run one supervised attack first so the bot can read your account."; any other
exception reads "Something went wrong. The Technical log on the Activity tab has the details."
and its text stays in the log.

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
| `switch_only:<id>` | Checks the account without farming (`startSwitchOnly(readAccount = true)`), reading it first if it has not been read |
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
`startPlanCombined`, `startSwitchOnly`, `stop`, `captureClashGame` and `readDeviceHealth`. The
PC adds `hasBattery` for Setup's Power line, and Setup reads the remote key from its file
(`RemoteLink.key`) rather than `remoteKey`, which a new link leaves behind.

## Tray and status panel

Main adds a tray icon when Windows has a notification area. Its tooltip is the run's title and
phase, double-clicking it opens the window, and its menu holds Open Clash Farmer, Pause or Resume
and Stop while a run is busy (`FarmerService.pauseFromNotification`, `resumeFromNotification` and
`stopFromNotification`, the phone's ongoing notification buttons), Status panel, and Quit. Result
notices from `RunNotifier` show as Windows notifications from the icon.

Closing the window leaves the bot running behind the icon, as the phone's service outlived its
screen, and says so in a notification the first time. Quit stops any run and ends the app. With
no notification area, closing the window quits. While a run is busy (`FarmerService.isBusy`),
Quit, and closing the window with no notification area, open the window on "Stop the run and
quit?" first; "Stop and quit" quits, Cancel or Escape keeps the run going.

The window's title is "Clash Farmer", and "Clash Farmer - <phase>" while a run is live, so the
taskbar tells a working bot from an idle one (`windowTitle` in `Main.kt`).

The window opens where it was left (`WindowPlacement.kt`, `window.json` in the files directory):
its position, size and whether it was maximized are saved when it closes to the tray or the app
quits. A first start centres it at 1100x760, shrunk to 92% of the main screen's work area where
that is smaller, so a 1080p laptop at 150% (about 1280x688 above the taskbar) sees the footer. A
saved window is kept on its monitor while 100x100 of it is still on one, moved inside that
monitor's work area and shrunk to fit it; one left on a monitor since unplugged is centred on the
main one. The window can't be dragged smaller than 860x600, or the work area where that is less.

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
  accessibility and notification lines, Text reader is new, and Remote page replaces the phone's
  remote line, Power replaces battery and heat, and the per-account lines are kept.
- Game window reads the window watcher. Before the first lookup it is not read and not counted.
  A failed lookup is red with the reason. No window is amber, "Not open", with the fix "Open
  Clash of Clans", which sends `launch_clash`. A window closed by hand is red, "Closed", with no
  fix: Google Play Games keeps the game running hidden, and opening it again is the relaunch that
  broke input, so the line says to restart the PC. A window seen hidden and then shown again by
  the same Google Play Games process is that relaunch: red, "Reopened", with the same restart
  advice and no fix, and a start refuses while it shows (`WindowStatus.reopenedInRunningVm`; a
  new process clears it). Only a window that process had shown before counts, so a fresh
  Google Play Games that creates its window hidden and shows it once the game loads is never
  flagged. A minimized window is fine. A full-screen window (no title bar, not maximized, and
  covering its whole monitor) is amber, "Full screen", and says to turn full screen off in
  Google Play Games; a maximized window never counts, even with a taskbar that hides itself. A found window gives the game
  area at its live size, such as "Found. The game area is 1860x1162."; nothing in the UI assumes
  16:9.
- With an account picked on Home and calibrated, a found window's line sets the game area beside
  that calibration, at the size a run would reach (`PcProfiles.runSize`: at the calibration's
  shape, its size shrunk to what the monitor holds; at another shape, the game area's own size,
  which a run leaves alone). The same shape is green, and says when the window will be resized or
  the screen holds less; a screen that holds less with a calibration not yet verified is amber,
  "New size", saying to calibrate again. Another shape is green once a supervised attack has
  worked there (surfaces.json), else amber, "New shape", saying to run one supervised attack, or
  to calibrate again when the calibration itself is not verified. A shape narrower than 1.3:1 or
  2:1 and wider (`PcGeometry.isPc`), or a game under 720 rows, is red, "Can't run".
- The account lines count verification as a run would on the game area shown
  (`PcChecklist.verifiedHere`), so at an unverified shape they ask for a supervised attack.
- With no window, the line also reads the sidecar's `pcInfo` (every 30 s): no Google Play Games
  is red, "Not installed", with the download address and no fix; a start through Google Play
  Games that failed is amber, "Did not open", until a window appears; no Clash of Clans shortcut
  is amber, "Not installed", and keeps the open fix, since the shortcut check can miss. A
  shortcut scan that could not finish says nothing about Clash.
- Windows is an amber line, "Windows 10", below Game window on a build below 22000: Windows 10
  draws a yellow frame around a captured window, and the bot was tested on Windows 11 only. It
  does not count toward ready. Windows 11 shows no line.
- Text reader is the sidecar's OCR, from `SidecarStatus`, which asks the sidecar's hello every
  3 s while the models load and every 60 s after. Not read or "Loading" is not counted. A failed
  load is red, "Failed", with the sidecar's reason (usually a DLL that would not load) and what to
  do: unzip again into a new folder, then install the Visual C++ Redistributable (x64).
- Tray icon is `java.awt.SystemTray.isSupported()`: "On", or amber "Off" with no fix.
- Power replaces the phone's battery and heat line. A PC with no battery (the sidecar's
  `power.hasBattery`, `FarmerService.hasBattery`) is green, "No battery", says the battery guard
  never stops a run, and is not counted. A laptop's line gives the level and whether it is plugged
  in, green and counted; at 15% or less on battery it is red and says to plug the laptop in.
  Not read is grey and not counted. The PC reads no temperature, so the line says nothing of heat.
- Remote page replaces the phone's remote line and is never counted: without Tailscale it is a
  grey "Optional" that says the page is not needed to farm and how to turn it on; amber when port
  8765 is taken, or when Windows won't bind the Tailscale address yet (as after sleep), each with
  its own text and retry time; or "On" with the Tailscale IPv4 address, saying the copied link
  works like a password.
- Each account's block opens with an Account line (`PcChecklist.account`) saying whether the bot
  has read the account off Clash's Settings (`PcAccount.enrolled`, read with the other files on
  IO). Not read is amber, "Not read", with no fix, and says the first supervised attack reads the
  village name and tag and other runs wait until then. Read is green, names the village and points
  to the block's Read account again button, shown only once the account is read. That button
  (`PcAccount.forget`, refused with "Wait for the current run to finish." during a run) drops the
  identity template, toasts "The next supervised attack will read your account again." and turns
  the line amber. The block's header shows the tag only once the account is read, so a fresh
  install never shows a placeholder tag.
- The Power line is the service's last sidecar reading. The machine lines are read on IO and
  kept for 30 s.
- An amber card at the top of the page, under the title, says the ban risk
  (`FirstRun.BAN_WARNING`); the phone's warning sat at the foot of the page.
- Copy link and New remote link show only while the remote page is on. Copy link puts the link
  on the AWT clipboard, with the key read from `remote_key.txt`. New remote link asks first ("Make
  a new remote link?"), then writes a fresh key and swaps it into the running page
  (`RemoteLink.renew`, `RemotePage.use`), so the old link stops working at once, and copies the
  new link. The key is never shown or logged.
- The foot of the page names the build ("Clash Farmer PC 1.1.0 (commit). Settings and logs are
  in ...", `BuildInfo.kt`) beside Open logs folder.

Tray check:

- The picture cards wrap to the sheet's width; the phone fitted four to a row.
- A frame of another size than the calibration is checked with the calibration moved onto it, as
  a run moves it (`TrayCheckSheet.profileAt`). The phone refused it as a screen still turning; on
  a 16:10 game area every check stopped there. A game area too small to read says so.

Inspect and captures:

- `inspect`, the tray check and calibration capture the window where it is, without the phone's
  return-to-activity step.
- Resume: the phone brought Clash to the front and resumed 1.5 s later. The PC never brings the
  window forward; it starts Clash only when the window is gone, as the tray's Resume does, and
  resumes 1.5 s later.
- The inspect report shows in a monospace alert titled "What the bot sees". It leads with the
  screen in words ("The game shows the village.", `readableState`) and the saved picture's name,
  and keeps the raw reading (state name, game area, text read) under "Details" (`inspectReport`).

Account editor:

- A number box drops anything but digits as it is typed or pasted, which is all the phone's
  number keyboard offered. Text already in the box stays, so a stored "2.5" still shows and Save
  can say why it is refused.
- Close and Escape ask "Discard your changes?" (Discard, Keep editing) when a box or switch
  differs from what the sheet opened with; an untouched sheet closes at once. The phone's sheet
  closed without asking.

Calibration:

- It fills the window in place of the tabs while open: the frame on the left, a 340 dp sidebar
  with the title ("Calibrate Blood"), "N of 36 points set", the target picker, actions and status
  on the right.
- The 38 targets keep the phone's names but are listed by the game screen they are clicked on
  (game area, village, attack menu and army, scout screen, battle tray, battle map, battle result,
  wall panel, collectors), with no numbers. The list shows a header per screen, a check on each
  set target, "Needed" on what an attack still lacks (`CalibrationTarget.required`, the same set
  `validateForAttack` checks), and on a PC profile the game area's corners as "Set by Capture",
  which can't be picked: a capture fills the frame, and a stray click there would shrink it.
- The troop cards are named for what the attack does with them, not the owner's army: "Main troop
  card (e.g. Valkyries)", dropped all around the base; "Spell cast inside (e.g. Rage)", cast five
  times; "Jump spell card (optional)"; and the heroes "(if you have it)". The keys in the profile
  are unchanged. On the jump spell and the heroes a "Not in my army" button removes the card's
  point and area (`withoutCard`), since the attack uses every hero and jump card that is set and
  the tray check stops a run on a count badge in a hero's slot.
- Start from the standard positions loads `profile/pc-start-1740x978.json`, the owner's verified
  1740x978 calibration shipped with no collectors, wall points or deployment points and not
  verified, moved onto the game area shown with the run's rule (`StarterProfile.onto`, which calls
  `PcProfiles.derive`). What belongs to the account's village (grass, wall panel, wall progress,
  collectors) is kept from the profile being edited, moved onto that game area first when it is
  another size, which drops its collectors as a capture does. It asks first when there is a saved
  calibration or unsaved points, then picks the main troop card, since the tray is the one place an
  army differs, and its toast says to remove any hero or spell the army lacks. Nothing
  is written until Save, and the result is unverified until a supervised attack. Setup's
  calibration row for an account with no calibration says to start there
  (`StarterProfile.setupLine`).
- The sidebar shows the selected target's saved point ("Set at x 307, y 903") and draws it, and
  its box, in green on the frame when the frame is the profile's size. Arrow keys move the point
  Apply would save by 1 frame pixel, 10 with Shift, starting from the saved point; Enter applies.
  While a point waits for Apply, the screen takes Enter before the button that has focus
  (`takesEnter`), so a pick from the list and the arrows never press Capture or Save again. An
  edge end applied on this visit counts as set at once, though Apply put the edge's other end on
  the same point. A 128 dp loupe in
  the frame's top-left corner (top-right when the point is under it) shows 32 frame pixels around
  the pointer, or the last click, without smoothing.
- Back and Escape ask "Leave without saving?" when points were set or moved since the last save.
  Retake camera reference asks before it clears the saved collectors. Clear collectors and Retake
  sit under "Start over".
- The status lines are plain: the game area's size, what an attack still needs, the collectors
  and camera reference, and whether a supervised attack has confirmed the calibration. Failed
  captures and saves give a sentence saying what to do (`saveFailure`), and the error goes to
  the log.
- File reads, writes and captures run on IO, with every button but Back off until they end.
- The default profile, before the saved one is read and for an account without one, is at the
  game surface's live size when the watcher has measured it, capped to what the monitor holds,
  else the phone's 1600x720. This also lets `captureNormalizedVillage` find the display it waits
  for on a new account.
- A capture of another size moves the points onto it with the run's rule (`refit`, which calls
  `PcProfiles.derive`) and leaves them unverified; the phone kept the points and changed only the
  size. A toast says the points were moved and, when there were any, that the collectors were
  cleared. A profile that can't be moved, such as the phone's default, is relabelled as before. A
  capture at the profile's own size makes a PC profile's game area the whole frame again, with a
  toast, so a game area an earlier build shrank through a corner click is repaired.
- The camera reference follows: a frame from `captureNormalizedVillage` of another size moves the
  profile instead of failing. Show camera reference never changes a saved reference: one of
  another size than the profile being edited is shown with a note (`referenceMismatch`), and no
  point can be marked on it; collectors can be marked again once Retake sets it aside with its
  collectors and captures a new one.
- Apply refuses a click on a frame of another size than the profile being edited and says to
  press Capture game screen (`frameMismatch`), since the click is a pixel of that frame. Besides
  such a camera reference, that is the frozen.png of a capture at another size whose move was
  never saved, which the screen shows again when it opens.
- Save refuses a game area a run can't use. Replacing a saved calibration of one shape with one of
  another asks first ("Save at another shape?"). A save that changes the points also deletes the
  account's surfaces.json, since the shapes in it were verified on the old points.
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
| "Inspect Result: VILLAGE_HOME" | "What the bot sees"; the report says "The game shows the village." and puts the raw fields under "Details" |
| "Inspect failed: <exception>" | "Inspect could not read the game window. Make sure Clash is open, then try again." |
| "Please calibrate the profile first.", "Unknown account", "Account not found" | "Blood is not calibrated. Open Setup and calibrate it first.", "That account is gone. Choose one on Home." |
| "Switching to Clash and capturing..." and "Switching to Clash and capturing game frame..." (overlay status) | "Capturing the game screen..." |
| "Device calibration" | "Calibrate Blood" |
| "Selected: None (tap image)", "Selected: (x, y)" | "Click the screenshot to place this point.", "New point: x 870, y 489. Press Apply or Enter to set it.", or where the target is set |
| The status block's "Res: ... \| VP: [...]", "State Anchors: n \| Troop Anchors: n", "Verified: false (earned locally via live attack)" | "Game area 1740x978", "Still needed to attack: ...", "No collectors, no camera reference", "Not confirmed yet: after saving, run one supervised attack." |
| "Tap a point on the screenshot first" | "Click a point on the screenshot first" |
| The 38 calibration targets' "1. Viewport Top-Left", "Tap ..." | Sentence-case names with no numbers ("Attack! button", "Main troop card (e.g. Valkyries)"); "Click ..."; the viewport corners are "Game area, top-left corner" and drop "(excluding status bar)" and "(excluding nav bar)" |
| "Apply selected point", "Viewport: WxH", "ROI: [...]" | "Apply point", "Game area WxH", "Card area" |
| "Saved <target> to (x, y)", "Profile saved (Verified: false)", "Capture failed: <exception>", "Validation error: <exception>", "Reference failed: <exception>" | "Attack! button: set at x 90, y 900. Save when you're done.", "Saved. Run one supervised attack to confirm it.", "Could not capture the game. Make sure Clash is open and not minimised, then try again.", "Could not save: a point is outside the game area. ...", "Could not capture the camera reference. ..." |
| "... is no longer on this phone" | "... is no longer on this PC" |
| "Export failed. Check free space on the phone." | "Export failed. Check free space on the disk." |
| "Saved to Downloads" | "Saved to <folder>", which is "Saved to Downloads" when that folder is kept |
| "The phone must be on and unlocked then" | "The PC and its screen stay on until then. Leave it unlocked" (the app holds the PC and its screen awake while a start is set, as during a run) |
| PendingStart's "The phone was asleep at the start time" | "The PC was asleep at the start time" (`FarmerService.PC_ASLEEP`) |
| "The phone's screen is off" and "The phone is locked" at the start time | "The PC was locked or its screen was off at the start time. Leave it unlocked with the screen on" (`FarmerService.PC_LOCKED_AT_START`) |
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

`ui/screens/SetupChecklist.kt:95` "Starts with the accessibility service." is no longer shown:
`PcChecklist.remote` draws Setup's remote line.

## Tests

| Test | Covers | Tests |
| --- | --- | --- |
| `desktop/ui/PcChecklistTest.kt` | The Game window, Windows, Text reader, Tray icon, Remote page and Account lines, including a 1860x1162 surface, a window closed by hand or opened again, full screen, Google Play Games or Clash missing, a failed start, the Windows 10 line and a reader that failed to load and an account not read yet, and how they count toward ready, the tab dot and the primary fix; the game area against the calibration (same size, same shape, capped, capped and unverified, new shape, verified shape, unverified calibration, too small, too small at another shape, unsupported shape), `runSize` and `verifiedHere` | 34 |
| `desktop/SingleInstanceTest.kt` | The lock per files directory, its release, a later start raising the first, the same from a second JVM, and the start messages | 10 |
| `desktop/CrashHandlerTest.kt` | Which failures end the app, the dialog's text, its 10-minute limit, and window failures that skip it | 5 |
| `desktop/BuildInfoTest.kt` | The version and About text, and the stamped version matching gradle.properties | 3 |
| `desktop/ui/TrayCheckSheetTest.kt` | `profileAt`: the calibration itself, moved to 16:10, and a game area too small | 3 |
| `desktop/ui/calibration/CalibrationSizeTest.kt` | `refit` at the same size, with a shrunk game area, 16:10, a capped size, the empty default, a profile that can't be moved and a frame too small; `saveQuestion`, `saveProblem`, `referenceMismatch` and `SurfaceVerification.calibrationSaved` | 10 |
| `desktop/ui/AccountEditorInputTest.kt` | `digitsTyped` and the editor's box order | 7 |
| `desktop/ui/AccountEditorCloseTest.kt` | Close on an untouched sheet, the question after a changed box or switch, Keep editing, and a change put back | 3 |
| `desktop/ui/calibration/CalibrationTest.kt` | The 38 targets grouped by screen, their plain labels and the army's names, the needed set against `validateForAttack`, `isSet` (with edge ends applied on the visit), `savedPoint`, `savedBox`, `targetEnabled`, `targetRows`, `withoutCard` against `AttackPlanner.buildDeployment`, `calibrationKey`, `takesEnter` and `saveFailure`; `applyTarget` on phone and PC frames, its PC boxes against the OCR of PC captures, and `FrameFit` at 16:9 and 16:10 | 25 |
| `desktop/ui/calibration/StarterProfileTest.kt` | The shipped standard positions, `onto` at the same size, over a profile of another size, at 16:10 and an unworkable size, `sizeFor`, and Setup's row for an account with no calibration (`setupLine`) | 7 |
| `desktop/ui/components/ParseClockTest.kt` | `parseClock` | 3 |
| `desktop/ui/DashboardRenderTest.kt` | `pcAccounts` and `shownPresets` with Blood alone; every tab, the account editor and calibration (empty, and with a screenshot, a clicked point and the loupe) drawn off-screen at 1100x760, and the whole window at 100%, at 150% in 1280x650 dp and with a run going, into `app/build/ui-renders`; `tabForKey`, and the tab keys after the page holding focus is dropped | 5 |
| `desktop/ui/StartFixTest.kt` | `StartFix` for each readiness reason, `plainReason`, `shownPresets`, `gameChip` and `headerChip` | 9 |
| `desktop/ui/ControllerTextTest.kt` | `actionFailure`'s titles and messages, `readableState` and `inspectReport` | 4 |
| `desktop/ui/ActivityLinksTest.kt` | `clearable` and `findingFix` | 2 |
| `desktop/ui/components/KeyControlsTest.kt` | `stepKey` and `nextOption` | 2 |
| `desktop/ui/components/FocusRingTest.kt` | A clicked button drawn without its focus ring until a key is pressed (`KeyboardNav`) | 1 |
| `desktop/RunTrayTest.kt` | `RunNotifier` through `RunTray`, its stop notice in `stopLine`'s words, the scheduled-start notice's `scheduledReason`, and the panel's status text, plain lines, idle shape and Stop | 10 |

## Open points

- A window closed by hand reads as "Closed" from the sidecar's `visible` flag, following the
  spike's finding that closing hides the window. That has not been seen live, since closing it
  breaks input until the PC restarts. Whether quitting Google Play Games from its tray icon also
  gives a clean start is untested, so the line names only the restart. "Reopened" (same
  process, shown, hidden, then shown) has not been seen live either. A window the app first
  saw hidden, as when the app starts after the window was closed, is not flagged when it is
  opened again.
- The full-screen check (no title bar, not maximized, the window's frame covers its monitor),
  the Clash shortcut scan and the Windows 10 yellow frame have not been seen live. If Google Play
  Games' full screen keeps a title bar style or the maximized state, the line never shows.
- The remote link on the clipboard can be kept by Windows clipboard history or cloud clipboard.
  Android marked the clip sensitive; Java has no such mark.
- Every screen draws off-screen without a layout failure. The window has run on this PC since 24
  October, and runs posted their notifications through the tray. The user has seen the status
  panel's pill during a run, but nobody has checked the tray icon or the panel's open card on the
  screen.

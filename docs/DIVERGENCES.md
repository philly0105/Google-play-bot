# Divergences from the phone tree

Every edit to a file that `docs/PLAN.md` classes as verbatim or verbatim-through-shims, and every
pure function moved out of a ported or rewritten file. One entry per change: the file, what
changed, and why the PC needed it.

Paths are relative to `app/src/main/kotlin/local/clashfarmer/`. Moved code is copied line for line
from the phone file at `eeaadba`; the new file adds only a package line, the imports the moved
lines use, and for the three rows marked "New object" a short KDoc and the `object` wrapper.

| File | Change | Why |
| --- | --- | --- |
| `ui/DashboardScreen.kt` | Removed the `DashboardHost` and `DashboardScreen` interfaces. `TabBadge` and `Tab` stay. | Both interfaces name `android.app.Activity` or `android.view.View`; they are the View shell's contract, which the Compose UI replaces. `SetupChecklist.badge` needs `TabBadge`. |
| `ui/components/UiText.kt` | Removed the `android.widget.TextView.update` extension and its KDoc. | It extends an Android View; only the View screens called it. |
| `ui/screens/HomeCards.kt` | Moved `HomeCards` out of `ui/screens/HomeScreen.kt` (lines 52-308). | `HomeCardsTest` targets it; `HomeScreen` is a View (class 4). |
| `ui/screens/ComposerState.kt` | Moved `ComposerState` out of `ui/screens/RunScreen.kt` (lines 47-289). | `ComposerStateTest` targets it; `RunScreen` is a View. |
| `ui/screens/SetupChecklist.kt` | Moved `CheckLine` and `SetupChecklist` out of `ui/screens/SetupScreen.kt` (lines 51-222). | `SetupChecklistTest` targets them; `SetupScreen` is a View. |
| `ui/screens/StatsModel.kt` | Moved `StatsModel` out of `ui/screens/StatsScreen.kt` (lines 47-174). | `StatsModelTest` targets it, and verbatim `remote/RemoteApi.kt` calls `StatsModel.coverage`. |
| `ui/components/ValueFit.kt` | Moved `ValueFit` out of `ui/components/StatTile.kt` (lines 103-114, plus its `kotlin.math.ceil` import). | `ValueFitTest` targets it; `StatTile` is a View. |
| `ui/components/ResourceBar.kt` | New object holding only `FULL_AT = 0.98f`, from the companion of the `ResourceBar` View. | `HomeCards.storageFull` reads `ResourceBar.FULL_AT`. |
| `ui/FarmerTheme.kt` | New object with the token lines 22-73 and `withAlpha` (lines 82-83). The View factories and the `dp`, `dpf` and `spf` extensions are left out. | Verbatim `Tone` and `AccountColor` read the tokens, and `AccountColorTest` reads `acctA` and `acctB`. |
| `ui/AccountEditor.kt` | New object with only `saveEnabled`, from the companion of the `AccountEditor` sheet. | `AccountEditorTest` calls `AccountEditor.saveEnabled`; the sheet is a View. |
| `app/src/test/kotlin/local/clashfarmer/insight/EventTextTest.kt` (a test) | Line 22: `File("src/main/java")` is now `File("src/main/kotlin")`. No assertion changed. | The test reads every logged event name from the main source root, which this repo keeps in `src/main/kotlin`. See `docs/TESTS.md`. |
| `core/PcGeometry.kt` (new, PC only) | `isPc(viewport)` is true for a landscape aspect from 1.3:1 up to, not including, 2:1; `pick(viewport, phone, pc)` returns the PC value then and the phone's otherwise. `length`, `x`, `y`, `point`, `region` and `xFraction` place a PC value, given in pixels of 1740x978 with the edge it keeps to (`Anchor.LEFT`, `CENTRE` or `RIGHT`), on a frame of any size: its distance from that edge and its height from the top scale with the frame's height over 978. | Google Play Games draws the game at the aspect of the monitor it launched on and keeps it through a resize: 1740x978 (16:9), and after a relaunch 1860x1162, resized to 1565x978 (16:10). The rows below keep every phone value and add a PC one beside it, chosen through this object. The phone's tests use 1600x720, 2400x1080, 800x360, 1550x720, 1500x620 and 1700x720, all 2.15:1 or wider, and 720x1600, so they keep the phone values; RunGuardTest's 1920x1080 counts as the PC's, and that test only compares sizes. `docs/PC-GEOMETRY.md` lists each value with its anchor and its evidence. |
| `core/Policy.kt` | `QUIT_BODY` takes "che" for "the"; `QUIT_TITLE` lets the t of "Exit" go missing; two last `STATE_RULES` entries read " baftle " and " batfle " with " build " as HOME. | PaddleOCR read the PC's quit prompt as "Do you want Go quit Che game?" and "CONfiRM Exi+", and the village's Battle as "Baftle" (`audit-evidence/spike/geo-after-deselect.json`) and, on six village frames at 1860x1162, "Batfle" (`audit-evidence/live/20261024-030816-profile/00-home.json` among them). These are spellings, not positions, so they apply at every aspect; each only widens a match the phone already makes. |
| `core/Builders.kt` | `PC_CORNER` and `PC_CORNER_BUILDERS_LEFT`, the counters' line above Build and the split between the builders' counter and the goblin's, in pixels of 1740x978 kept from the right edge. `inCorner` and `readCorner` use them on the PC. | The phone's fractions miss the PC's "0/6" at x 1576-1642, y 774-804 of 1740x978 (`audit-evidence/spike/geo-home.json`): the HUD keeps to the bottom-right corner and scales with the height. At 1860x1162 it reads at x 1667-1746, y 920-954 (`audit-evidence/live/20261024-034034-dragmeasure/4a-right150.json`), within 2 px of where the anchoring puts it. |
| `core/WallPanel.kt` | `addOneByPitch` steps `PC_BUTTON_PITCH` on the PC, 140 px at 1740x978 scaled with the height; `priceRegions` takes the PC's half width and offsets above the label, scaled the same way. | At 1740x978 the PC draws a selected building's buttons at the phone's pixel size (`audit-evidence/spike/geo-selected.json`). That they scale with the height like the rest of the HUD is an assumption: they have not been read at 16:10. The price regions are an estimate: no purchase button has been read on the PC. |
| `core/UpgradeListNavigator.kt` | `upgradeListCardShowing` takes the PC's column, x 1272-1740 and y 63-873 of 1740x978, and its flush edge, x 1694, all kept from the right edge. | With the phone's fractions every village frame on the PC passed for an open list: the balances "9 693 255", "167 280" and "2 508" end by x 1678, right of the phone's edge scaled (1631). At 1860x1162 they end by x 1783, and the flush edge is at 1805. |
| `recognition/ScreenTargets.kt` | `Band` takes an optional PC band, which `RELOAD_BUTTON` has. `BATTLE_READS` adds "baftle" and "batfle". `builderCounterPoint`, `upgradeListClosePoint` and the panel branch of `upgradePopupRegion` place PC values kept from the right edge. `isOwnOverlay` is false on the PC. | Positions from `audit-evidence/spike/geo-home.json` and `geo-list-*.json`. The idle dialog is Android's, centred: its RELOAD GAME reads at (577, 545) of 1740x978 (`audit-evidence/live/20261024-025411-look/frame.png`) and (651, 660) of 1860x1162 (`audit-evidence/live/20261024-031222-focuscheck/0-before.json`), y 0.56-0.57, above the phone band's top at 0.78. The PC's overlay is a window of its own that the capture never holds, and the corner it covered holds the game's name, clan and loot. |
| `recognition/ArmyRecipes.kt` | `Band` takes an optional PC band, which `MY_ARMY_LABEL`, `SAVED_RECIPES_LABEL`, `BUTTON_COLUMN` and `BOOST_ROW` have. `titleBoxes`, `find` and `describe` take `PC_TITLE_LEFT_MIN` and `PC_TITLE_COLUMN`, x at 1740x978 kept from the centre, and `PC_USE_CENTRE_MIN`; `listSwipe` takes a PC swipe kept from the centre. | The PC draws the army screen as the phone's scaled about 1.185 around the centre (`audit-evidence/spike/geo-army.json` and `geo-recipes.json`), and the phone's bands miss both tab labels there. The button column and the Boost row hold at 16:9. At 16:10 the Use buttons leave the column if the screen keeps its scale by the height, and two of the Boost row's four labels leave the row either way it scales; no 16:10 capture of the screen exists, so the PC bands take both ways. |
| `recognition/UpgradeListReader.kt` | On the PC, `rowsOf` drops boxes that end within `PC_ICON_COLUMN` of the panel's left edge, 80 px at 1740x978 scaled with the height, not 50; `rowOf` leaves boxes that start at x 1696 of 1740x978 or later, kept from the right edge, out of the price, and samples a Wall row's currency icon from there to the frame's edge. | With 50, the PC's level badges "se" and "20" (ending at x 1336 and 1344) were glued onto the card names after them, which would lose a Wall row. The coin read as a "0" of its own made the price "4 400 000 0", and the icon region after the price missed the coin on 5 of 9 priced cards, where now all 9 read gold (`audit-evidence/spike/geo-list-end.json` and `.png`). |

## Shims and platform differences

These change no copied file, but the copied code runs on them, so they can change behaviour.

- `android.graphics.Bitmap.createBitmap(source, x, y, w, h)` and `createScaledBitmap` always return
  a new mutable bitmap. Android returns the source itself when it is immutable and the request
  covers all of it unscaled. `ScreenReader.observe` recycles what these return, so the Android
  shortcut would recycle the caller's frame when a viewport is clamped to the whole frame.
- Bilinear `createScaledBitmap` interpolates unpremultiplied channels, where Skia interpolates
  premultiplied ones. They agree on opaque pixels, which is every screen frame.
- `android.os.SystemClock.elapsedRealtime()` and `uptimeMillis()` both read `System.nanoTime()`.
  There is no deep sleep on the PC to tell them apart.
- `Context`, `PowerManager` and `AccessibilityService` shims exist only for the constants and the
  one property that verbatim code names: `Context.POWER_SERVICE` and `PowerManager.isInteractive`
  in `remote/RemoteServer.kt`, and `AccessibilityService.GLOBAL_ACTION_BACK` (fully qualified) in
  `core/LocalRunner.kt`. With them, `RemoteServer.kt`, which PLAN.md lists as ported (class 3),
  stays verbatim (class 2).
- The ML Kit shim returns each OCR line as its own `TextBlock` with one `Line`; ML Kit groups
  lines into blocks. `Text.text` joins them with newlines, as ML Kit does.
- `org.json` is json.org's `org.json:json:20240303`, the same artifact the phone's JVM tests used,
  so tests are unaffected. The phone app ran on Android's org.json, which differs at runtime:
  Android keeps keys in insertion order (json.org's `JSONObject` is a `HashMap`, so files and log
  lines the PC writes have keys in arbitrary order), and Android's `optString` returns `"null"` for
  an explicit JSON null where json.org returns the fallback.

## Class 3 ports

The ported files keep the phone's class names, packages and public members, with the exceptions
below. Bodies marked `TODO("stream B: ...")` say what the phone's body does; stream B ports them
from the phone file at `eeaadba`. `TODO` throws `NotImplementedError`, an `Error`, so the
`catch (e: Exception)` blocks in the copied code do not swallow it.

- `log/AppLog.kt`: `init(filesDir: File)` takes the files directory where the phone took a
  Context, and the two `android.util.Log` calls print to `System.err` with the stack trace. The
  rest is the phone file.
- `service/AndroidTransport.kt`: every guard is the phone's (the capture mutex and retries, pause
  stamps, `clearToSend`, held gestures, `resendingAfterPause`, point checks, action tokens,
  `swipeStrokes`, `afterDrag`). The constructor takes a third parameter, `surface: GameSurface`
  (`desktop/platform/GameSurface.kt`), and the screenshot, the gesture dispatch and a new `back()`
  go to it where the phone called the AccessibilityService.
  - Strokes are `GestureStroke(from, to, startMs, durationMs, willContinue, continues)`: a point
    when `from == to`, else a straight line, as the phone's `Path`s were. The held swipe's second
    stroke names the stroke it carries on in `continues`, where the phone called `continueStroke`.
    `GameSurface` documents Android's dispatch rules for the PC implementation to follow.
  - The screenshot answers through a callback, not a suspend function as the brief suggested, so
    `awaitCallbackWithin` still recycles a frame that arrives after its deadline. The surface hands
    over a software bitmap, so the phone's hardware-buffer copy is gone. A failure carries a message
    and a retryable flag in place of Android's error code; the phone retried "interval too short"
    and "internal error".
  - The 1.1 s floor between screenshots, Android 11's limit, is kept. The PC may not need it.
- `service/FarmerService.kt`: a plain class, `FarmerService(filesDir: File)`.
  - Line for line from the phone: the companion's public constants, `instance` and `isConnected`;
    the fields; the shade hold and app-switch latch (`latchAction`, `gateAction`,
    `holdForSystemWindow`, `watchHold`, `holdIfShadeOver`, `releaseSystemWindowHold`,
    `releaseHoldIfClashBack`); `awaitClearToAct`, `heldMs`, `awaitResume`, `waitWhilePaused`;
    `stopBetweenRuns`, `stop`; `saveFailureSnapshot`; `backIfClear`, `shopStillUp`,
    `cancelQuitPrompt`; `clashLooksForeground`, `getActiveForegroundPackage`; the thin start
    methods (`startFarmingAccount`, `startTimedFarmingAccount`, `startAttacksOnlyAccount`,
    `startEventAccount`, `startSupervisedAccount`, `startWallPhaseAccount`, `startPlan`,
    `startPlanCombined`) and `startWallPhase`; the top-level `awaitClashRead`.
  - `TODO` bodies with the phone's signatures: `onServiceConnected`, `onDestroy`, `scheduleStart`,
    `cancelPendingStart`, `dropPendingStart`, `readDeviceHealth`, `startCombinedRun`,
    `startSwitchOnly`, `startGuardedAccountSession`, `startFarming`, `ensureClashForeground`,
    `captureClashGame`, `captureNormalizedVillage`, `readForeground`, `windowSummary`,
    `currentDisplayViewport`, `isDeviceUnlocked`.
  - Left out: `onAccessibilityEvent`, `onInterrupt`, `announcedForegroundPackage`, the screen-off
    and notification receivers, the companion's private `RESUME_AFTER_MS`, `PENDING_SLICE_MS`,
    `IMMERSIVE_WAIT_MS`, `CLASH_READ_WAIT_MS` and `remoteServer`, and the private members only the
    `TODO` bodies use: `pendingJob`, `pendingLock`, `pendingStore`, `notificationResume`,
    `claimPause`, `firePendingStart`, `replaced`, `refusePendingStart`, `refuseWhilePending`,
    `clockTime`, `pauseFromNotification`, `resumeFromNotification`, `beginRunSnapshots`,
    `runSingleAccountSession`, `verifiedAccountId`, `farmRestarting`, `withAccountContext`,
    `waitForTakenVillage`, `recoverToVillage`, `waitOutMaintenance`, `recordAccountResult`,
    `awaitImmersiveClash`, `clashCoversDisplay`.
  - Added for the copied callers, in place of Android members: `packageName`
    (`"local.clashfarmer"`, the phone's application id, which `AppSwitchLatch` takes as the
    helper's own package); `assets` (`desktop/platform/ClasspathAssets`, which opens
    `app/src/main/resources`, for `AccountSwitcher`'s identity images); `getSystemService(name)`
    (`TODO`; `RemoteServer` asks it for the PowerManager); `performGlobalAction(action)` (Back goes
    to `transport.back()`; Home is a `TODO`, used only by the takeover wait).
  - Until stream B lands: nothing sets `instance` or `transport`; `stop()` with nothing running
    reaches the `dropPendingStart` `TODO`; `awaitClearToAct`, `backIfClear` and
    `clashLooksForeground` reach the `readForeground` or `isDeviceUnlocked` `TODO`, so no run can
    act.
- `service/RunNotifier.kt` and `service/RunNotice.kt`: `RunNotice` and `LiveStatus` moved verbatim
  (phone lines 26-200, with the imports they use) into `RunNotice.kt`. `RunNotifier` is a
  no-argument stub: the companion's public constants and `withdraws` are the phone's, `post`,
  `postLive` and `cancelLive` do nothing, and `withdraw` returns false. Left out with Android's
  notification plumbing: the Context parameter, `controlAction` and `command(Intent)`.
- `service/FarmerOverlay.kt`: a stub without the Context parameter. `show`, `updateStatus`,
  `setVisible` and `dismiss` do nothing, and `hideForCapture` runs its action. `OverlayGeometry` is
  verbatim (phone lines 492-533). The phone's View classes `StatusBubble` and `PauseGlyph` are left
  out.
- `remote/RemoteServer.kt` is verbatim through shims (above), not ported or stubbed. It holds
  `ServiceRemoteBackend`, which calls `FarmerService`.

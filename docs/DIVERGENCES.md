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

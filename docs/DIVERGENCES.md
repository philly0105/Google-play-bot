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
| `insight/EventText.kt` | Added sentences for `surface_resized`, `surface_aspect_differs` and `surface_resize_failed`, after `gesture_held`. | `FarmerService.fitSurface` logs them when it sizes the game window back to its calibration, and `EventTextTest` requires a sentence for every logged event. |
| `app/src/test/kotlin/local/clashfarmer/insight/EventTextTest.kt` (a test) | Line 22: `File("src/main/java")` is now `File("src/main/kotlin")`. No assertion changed. | The test reads every logged event name from the main source root, which this repo keeps in `src/main/kotlin`. See `docs/TESTS.md`. |

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
  one property that copied code names: `Context.POWER_SERVICE` and `PowerManager.isInteractive`
  in `remote/RemoteServer.kt` and in `FarmerService`'s start set for later,
  `AccessibilityService.GLOBAL_ACTION_BACK` (fully qualified) in `core/LocalRunner.kt`, and
  `GLOBAL_ACTION_HOME` in `FarmerService`'s takeover wait. `FarmerService` answers them through its
  own `getSystemService` and `performGlobalAction`. With them, `RemoteServer.kt`, which PLAN.md
  lists as ported (class 3), stays verbatim (class 2).
- The ML Kit shim returns each OCR line as its own `TextBlock` with one `Line`; ML Kit groups
  lines into blocks. `Text.text` joins them with newlines, as ML Kit does.
- `org.json` is json.org's `org.json:json:20240303`, the same artifact the phone's JVM tests used,
  so tests are unaffected. The phone app ran on Android's org.json, which differs at runtime:
  Android keeps keys in insertion order (json.org's `JSONObject` is a `HashMap`, so files and log
  lines the PC writes have keys in arbitrary order), and Android's `optString` returns `"null"` for
  an explicit JSON null where json.org returns the fallback.

## Class 3 ports

The ported files keep the phone's class names, packages and public members, with the exceptions
below.

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
- `service/FarmerService.kt`: a plain class, `FarmerService(filesDir: File, host: ClashHost,
  surface: GameSurface)`. `host` (`desktop/platform/ClashHost.kt`) takes the place of Android's
  system services: the Clash window as `ClashWindowWatcher` last saw it, starting and closing
  Clash, the battery, and keeping the PC awake. `surface` goes to `AndroidTransport`.
  `onServiceConnected` and `onDestroy` are plain methods for the app to call. `onServiceConnected`
  builds the transport through the internal `connectTransport`, which tests call alone, and
  passes `filesDir` to `AppLog.init`, `RunNotifier()` and `FarmerOverlay` without a Context.
  - The rest is the phone file line for line, including pending starts, the combined,
    switch-only and single-account sessions, restarts, the takeover wait, recovery to the
    village, maintenance waits, run snapshots and result recording. The departures follow.
  - Foreground: `readForeground` gives `CLASH_PACKAGE` (source "window") while the Clash window
    exists, minimized or behind other windows, and null ("no window") without one. The capture
    restores a minimized window without focus and input goes to the window directly, so the
    window need not be in front. `helperActivityVisible` stays false, and `windowSummary`
    describes the Clash window alone. With no shade to read, the app-switch latch never holds; a
    window that vanishes mid-run fails the next capture's `checkCanAct`, which a restartable run
    recovers from.
  - Display and lock: `currentDisplayViewport` is the game surface's size, kept while the window
    is minimized and null with no window. `isDeviceUnlocked` is always true, and
    `getSystemService(Context.POWER_SERVICE)` gives a PowerManager whose `isInteractive` is always
    true. A locked or sleeping PC is left to the captures: one that fails stops or recovers the
    run. Whether capture fails on a locked PC, rather than returning black frames, is untested.
  - Surface size: the run guard holds each run to the profile's size, as the phone did, but a PC
    window can be resized by hand or open at another size. `fitSurface` runs after
    `ensureClashForeground` wherever a run token follows (the account check, the switch-only run,
    the reopening after the takeover wait, a recovery and `captureNormalizedVillage`) and sizes the
    surface back to the profile's when the aspects agree within 1%. Google Play Games keeps the
    aspect it chose at launch, so a surface of another aspect is left for the guard to refuse. It
    waits up to 2 s for the window watcher to see the new size. A resize kept taps landing in the
    spike, where a relaunch did not (`docs/gpg-spike.md`, Restarting).
  - Accounts: the PC runs only Blood, the user's call on 24 October. `startCombinedRun`,
    `startSwitchOnly` and `startGuardedAccountSession` refuse any other account before they start,
    so the account check never switches accounts in Clash. `startSwitchOnly` checks an account
    with no calibration at the surface's own size, where the phone defaulted to 1600x720, the one
    display its switcher supports.
  - Battery: `readDeviceHealth` returns the reading a loop started by `onServiceConnected` takes
    from `host.power()` every 30 s in `serviceScope`. The phone read a sticky broadcast on the
    spot; here each reading is a sidecar round trip, and `LocalRunner` and `RemoteServer` call
    this from threads that must not wait on it. Level is `batteryPercent` (null for Windows' -1),
    charging is `ac`, and `tempC` is null, so the heat guard never trips. A failed read leaves
    null and logs `device_health_failed` once per outage.
  - Starting Clash: `ensureClashForeground` calls `host.launch()` when the read is not Clash. The
    phone launched when `clashLooksForeground()` failed, but that passes a null read, which on the
    PC means no window. Before deciding, it waits up to 10 s for a window lookup that worked: before
    the watcher's first lookup, and while the sidecar restarts, the read is null even with Clash
    open, and a second launch may bring Clash to the front. A launch that throws stops the start.
    A window closed by hand (the sidecar's `visible` false) stops the start with `CLASH_CLOSED`
    and launches nothing: the game runs on hidden, and a launch then is the relaunch that broke
    input. The wait for the read to name Clash is gone, since the window read already does;
    `awaitClashRead` stays for `ClashReadTest`. The blank-frame wait is the phone's.
  - Home: `performGlobalAction(GLOBAL_ACTION_HOME)`, used only by the takeover wait, does nothing
    and returns true, as Android did on dispatch. The phone put Clash in the background without
    stopping it; on the PC Clash stays on the takeover dialog, the reopening after the wait finds
    the window and launches nothing, and the account check presses the dialog's Reload as before.
    Closing the window and starting Clash again broke posted input (`docs/gpg-spike.md`,
    Restarting), so the PC never closes it. Back is `transport.back()`.
  - Keeping awake: the phone held no wake lock and set no keep-screen-on flag, but a PC left alone
    sleeps. Each run job (`startCombinedRun`, `startSwitchOnly`, `startGuardedAccountSession`, the
    legacy `startFarming`) calls `host.keepAwake(true)` as it starts, and `keepAwake(false)` goes
    out when the last one ends, on a scope `onDestroy` does not cancel. A start set for later and
    Inspect do not hold the PC awake.
  - Leaving the app: `captureClashGame` and `captureNormalizedVillage` keep
    `returnToActivityClass` and ignore it, with no helper screen to reopen. `captureClashGame` no
    longer waits for Clash to fill the display: the PC has no status bar or rotation to wait out.
  - The notification's buttons: `pauseFromNotification` and `resumeFromNotification` are public,
    and the new `stopFromNotification` stops with the reason the control receiver gave, all for
    the tray. Resume calls `host.launch()` only when there is no Clash window, since Google Play
    Games may bring the window it starts to the front, and does so inside its job before the
    wait, since the call suspends. Its new `source` parameter, "notification" by default, goes in
    the `pause_button` line and the hold's reason; the dashboard's Resume passes "dashboard".
  - Left out: `onAccessibilityEvent`, `announcedForegroundPackage`, `onInterrupt`, the screen-off
    receiver (Windows has no matching broadcast; a sleeping PC fails the next capture instead),
    the notification control receiver, `awaitImmersiveClash`, `clashCoversDisplay`,
    `IMMERSIVE_WAIT_MS` and `CLASH_READ_WAIT_MS`.
  - Added for the copied callers, in place of Android members: `packageName`
    (`"local.clashfarmer"`, the phone's application id, which `AppSwitchLatch` takes as the
    helper's own package); `assets` (`desktop/platform/ClasspathAssets`, which opens
    `app/src/main/resources`, for `AccountSwitcher`'s identity images and `remote.html`);
    `getSystemService(name)` and `performGlobalAction(action)`, above.
  - Every call to `RunNotifier` and `FarmerOverlay` is kept.
    Failures only the PC has (keeping awake, the Resume launch, Setup's launch) print to
    `System.err`, since `insight/EventText.kt` describes no event for them.
  - Added `launchClash()`, Setup's Open Clash of Clans: it starts Clash and returns at once, and
    sends nothing while a run is active or a Clash window exists. `captureNormalizedVillage`'s
    `returnToActivityClass` defaults to null, as `captureClashGame`'s does.
- `service/RunNotifier.kt` and `service/RunNotice.kt`: `RunNotice` and `LiveStatus` moved verbatim
  (phone lines 26-200, with the imports they use) into `RunNotice.kt`. `RunNotifier` takes no
  argument and writes to `RunTray`, which Main's tray icon reads: `post` queues a Windows
  notification and logs `notification_posted` (or `notification_skipped` without a notification
  area), and `postLive` and `cancelLive` set the tooltip's status, with the phone's 10 s hold after
  a result and its show-only-what-changed rule. `withdraw` returns false: Java cannot take back a
  notification Windows has shown. The companion's public constants and `withdraws` are the
  phone's. Left out with Android's notification plumbing: the Context parameter, the channels,
  `controlAction` and `command(Intent)`; the tray's Pause, Resume and Stop call `FarmerService`.
- `service/FarmerOverlay.kt`: no Context parameter. The ticker, `tick`, `lastEarned`,
  `updateStatus`'s five seconds and `setVisible` are the phone's, run on the Swing thread, and
  publish a `Panel` that `desktop/ui/StatusPanel.kt` draws as an always-on-top window that never
  takes focus, in the bottom right corner of the main screen. It shows only while a run is busy or
  a start is set: the phone's idle "Ready" pill would sit over every window, and the tray icon opens
  the app instead. The card's folded state is kept per launch, not in preferences, and
  `hideForCapture` only runs its action, since the capture is of the Clash window alone.
  `OverlayGeometry` is verbatim (phone lines 492-533). `StatusBubble` and `PauseGlyph` are drawn in
  Compose in `StatusPanel.kt`.
- `remote/RemoteServer.kt` is verbatim through shims (above), not ported or stubbed. It holds
  `ServiceRemoteBackend`, which calls `FarmerService`.

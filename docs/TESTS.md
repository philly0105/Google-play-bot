# Tests

The phone's JVM unit tests, copied from `C:\Users\aideo\Projects\clash-farmer` at `eeaadba`, and
the PC's own tests under `app/src/test/kotlin/local/clashfarmer/desktop/`. None of them needs
Clash, Google Play Games or the sidecar. On 25 October the suite ran 1799 tests, the phone's 1635
and the PC's 164, and none failed.

Run them with `.\Build.ps1 -Tasks ':app:test'`. The test task ends with one line,
`Tests: N ran, N passed, N failed, N skipped`.

## Copied

| Phone path (under `android/`) | PC path | Files | Same blob as the phone |
| --- | --- | --- | --- |
| `app/src/test/java/` | `app/src/test/kotlin/` | 113: 110 test classes and the helpers `core/WallPhone.kt`, `insight/LogFixtures.kt`, `recognition/UpgradeListFixture.kt` | 112; `insight/EventTextTest.kt` has one edited line (below) |
| `app/src/test/resources/` | `app/src/test/resources/` | 23: `hero-cards/` 7, `result-frames/` 15, `upgrade-list/` 1 | 23 |
| `calibration/live-2026-09-17/phone-profile.json` | `calibration/live-2026-09-17/phone-profile.json` at the repo root | 1 | 1 |

`@Test` methods: 1635 of the phone's 1635, in 110 files. Neither tree has an `@Ignore`.

"Same blob" means the hash git stores for the copied file equals the phone's at `eeaadba`.

The calibration fixture sits one level higher than on the phone because `PhoneProfileTest` opens
`../calibration/live-2026-09-17/phone-profile.json` from the module directory, which is
`android/app` on the phone and `app` here.

## Left out

`android/app/src/androidTest/`: `RecognitionFixtureRunner.kt` and the 14 screenshots it reads from
`assets/recognition/`. They are instrumented tests that need an Android device. A JVM version that
reads the same screenshots through the desktop shims is not written.

## Edited

`insight/EventTextTest.kt`, line 22: `File("src/main/java")` is now `File("src/main/kotlin")`.
`sourceEventNames` collects every event name the app logs by scanning the main source root, which
the phone keeps in `src/main/java` and this repo in `src/main/kotlin`. No assertion changed. The
edit is also in `docs/DIVERGENCES.md`.

## PC tests

164 `@Test` methods in 22 files under `desktop/`. Their fixtures are in
`app/src/test/resources/pc-frames/`: the sidecar's OCR of frames captured from Blood's game window
at 1740x978 and 1860x1162, and a few PNG crops.

| Test | Covers | Tests |
| --- | --- | --- |
| `PcGeometryTest` | `PcGeometry`, and the phone's readers on captured PC frames: the village, the quit prompt, the builder counter and list, a Wall row, the army screen, the idle dialog, the scout, the result screen, the selection buttons, and the 16:10 frames | 20 |
| `FarmerServicePcTest` | The ported `FarmerService`: the Clash window as the foreground app, the viewport, the battery, launching Clash, sizing the game surface, refusing every account but Blood, and the daily pruning | 15 |
| `PcAccountSwitcherTest` | Blood's identity check on the profile menu's Settings strip | 6 |
| `PcBattleDamageTest` | The battle's damage with the PC's End Battle label between the word and the value | 2 |
| `PcBattleResultTest` | The result screen's loot rows and stars at the PC's lengths | 3 |
| `PcRatePromptTest` | LATER on Play's rating dialog | 3 |
| `PcStarBonusNoticeTest` | Okay on Clash's Star Bonus notice | 4 |
| `PcTrayBadgeTest` | The tray badges' cut levels on the PC and the phone, through a fake OCR backend | 2 |
| `DesktopLogTest` | The desktop log file, and one log per file for every writer | 2 |
| `RunTrayTest` | `RunNotifier` through the tray, and the status panel | 5 |
| `SingleInstanceTest` | One app per files directory: the lock in one JVM for the same and separate directories, its release, and a later start raising the first; the same against a second JVM holding the lock; a port file that can't be written; and the start messages | 10 |
| `RemotePageTest` | Where the remote page listens, with a fake Tailscale address: off without it, on its IPv4, tailnet IPv6 and loopback addresses, following it as it comes, goes and changes, the 10-minute retry after a taken port against the next-check retry after any other failed bind, reopening a listener that stopped, and detection by adapter name; plus the real server on loopback, free and taken, and Java's own bind failures | 19 |
| `CrashHandlerTest` | Uncaught failures: which end the app, the dialog's text, its 10-minute limit, and window failures that skip it | 5 |
| `BuildInfoTest` | The version and About text, and the stamped version matching `gradle.properties` | 3 |
| `platform/ClashWindowWatcherTest` | The Clash window lookup | 4 |
| `platform/SidecarGameSurfaceTest` | Taps, swipes, the pinch, Back and frames through the sidecar | 9 |
| `sidecar/SidecarTest` | The sidecar protocol, against `FakeSidecar`, including hello's text reader error | 11 |
| `ui/` (five files) | The Compose UI; see `docs/UI.md` | 41 |

## When they run

- Gradle runs the tests from the module directory, `app/`. `EventTextTest` and `PhoneProfileTest`
  open files relative to it. `DailyLogFileTest` names `File("logs")` but only computes file names;
  nothing is written there.
- `EventTextTest.everyLoggedEventHasAPlainSentence` needs every event name logged under
  `src/main/kotlin` (outside `insight/`) to be one `EventText` knows, and more than 150 of them.
  The tree logs 249 names: 246 of the phone's 251, and `surface_resized`, `surface_aspect_differs`
  and `surface_resize_failed`, which the PC's `FarmerService` logs when it sizes the game. The
  phone's other five come from Android-only code: `app_switched_recovering`,
  `capture_layout_wait_timeout`, `notification_failed` and `screen_off_receiver_failed` from its
  `FarmerService` and `RunNotifier`, and `intent_action_rejected` from `MainActivity`. Any new
  `log("...")` in desktop code, the Compose UI included, needs a sentence in `insight/EventText.kt`
  or this test fails.
- The phone's tests reach the class 3 files only through members kept line for line:
  `FarmerService.CLASH_PACKAGE`, the top-level `awaitClashRead`, `RunNotifier.withdraws`,
  `OverlayGeometry`, `RunNotice`, `LiveStatus`, and `AndroidTransport.kt`'s `swipeStrokes`,
  `SwipeStroke`, `afterDrag` and `AfterDrag`. None reaches a `TODO` body.
- Several tests call `internal` members (`RunNotifier.withdraws`, `AccountEditor.saveEnabled`, the
  swipe helpers). That works because the tests are in the same Gradle module as the code.
- The phone's tests import nothing from `android.*` or ML Kit. Of the main files that do, they call only
  parts that touch no shim except the `SystemClock` one: `RunGuard`, the companion functions of
  `ScreenReader` and `AccountSwitcher`, `ImageMatcher`'s pixel arithmetic, and the top-level
  `runWithReader` (`core/LocalRunner.kt`) and `wallSignature` (`core/AndroidWallDevice.kt`). No
  test needs a real OCR backend.

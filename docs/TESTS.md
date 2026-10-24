# Tests

The phone's JVM unit tests, copied from `C:\Users\aideo\Projects\clash-farmer` at `eeaadba`. None
of this has been compiled or run yet: the agent sandbox that copied the tests blocks Gradle. The
counts below count source files and annotations, not results.

Run them with `.\Build.ps1 :app:test`. The test task ends with one line,
`Tests: N ran, N passed, N failed, N skipped`, to hold against the 1635 below.

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

## When they run

- Gradle runs the tests from the module directory, `app/`. `EventTextTest` and `PhoneProfileTest`
  open files relative to it. `DailyLogFileTest` names `File("logs")` but only computes file names;
  nothing is written there.
- `EventTextTest.everyLoggedEventHasAPlainSentence` needs every event name logged under
  `src/main/kotlin` (outside `insight/`) to be one `EventText` knows, and more than 150 of them.
  The tree as committed logs 210 of the phone's 251 names and none the phone lacks. The other 41
  come from `MainActivity.kt`, `CalibrationActivity.kt` and `ui/TrayCheckSheet.kt` (to be rewritten
  in Compose) and from the `FarmerService` and `RunNotifier` bodies that are stubs for now. Any new
  `log("...")` in desktop code, the Compose UI included, needs a sentence in `insight/EventText.kt`
  or this test fails.
- The tests reach the class 3 files only through members kept line for line:
  `FarmerService.CLASH_PACKAGE`, the top-level `awaitClashRead`, `RunNotifier.withdraws`,
  `OverlayGeometry`, `RunNotice`, `LiveStatus`, and `AndroidTransport.kt`'s `swipeStrokes`,
  `SwipeStroke`, `afterDrag` and `AfterDrag`. None reaches a `TODO` body.
- Several tests call `internal` members (`RunNotifier.withdraws`, `AccountEditor.saveEnabled`, the
  swipe helpers). That works because the tests are in the same Gradle module as the code.
- The tests import nothing from `android.*` or ML Kit. Of the main files that do, they call only
  parts that touch no shim except the `SystemClock` one: `RunGuard`, the companion functions of
  `ScreenReader` and `AccountSwitcher`, `ImageMatcher`'s pixel arithmetic, and the top-level
  `runWithReader` (`core/LocalRunner.kt`) and `wallSignature` (`core/AndroidWallDevice.kt`). No
  test needs an OCR backend.

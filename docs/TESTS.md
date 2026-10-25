# Tests

The phone's JVM unit tests, copied from `C:\Users\aideo\Projects\clash-farmer` at `eeaadba`, and
the PC's own tests under `app/src/test/kotlin/local/clashfarmer/desktop/`. None of them needs
Clash, Google Play Games or the sidecar. On 25 October the suite ran 1780 tests, the phone's 1635
and the PC's 145 (130 under `desktop/` and `accounts/PcAccountTest.kt`'s 15), and none failed.

Run them with `.\Build.ps1 -Tasks ':app:test'`. The test task ends with one line,
`Tests: N ran, N passed, N failed, N skipped`.

## Copied

| Phone path (under `android/`) | PC path | Files | Same blob as the phone |
| --- | --- | --- | --- |
| `app/src/test/java/` | `app/src/test/kotlin/` | 113: 110 test classes and the helpers `core/WallPhone.kt`, `insight/LogFixtures.kt`, `recognition/UpgradeListFixture.kt` | 111; `insight/EventTextTest.kt` and `accounts/AccountStoreTest.kt` have edited lines (below) |
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

`accounts/AccountStoreTest.kt`, the seeding test: the two accounts' Supercell names and tags are
`AccountStore`'s placeholders, "Supercell ID 1", "#UNREAD1", "Supercell ID 2" and "#UNREAD2",
since the app no longer ships the owner's (`docs/DIVERGENCES.md`).

## PC tests

130 `@Test` methods in 18 files under `desktop/`, and 15 in `accounts/PcAccountTest.kt`. Their
fixtures are in `app/src/test/resources/pc-frames/`: the sidecar's OCR of frames captured from
Blood's game window at 1740x978 and 1860x1162, and a few PNG crops. `identity/pc/blood.png` is
Blood's Settings strip at 1740x978, the template the app shipped before each account cut its own;
`identity/pc/blood-1860x1162.png` and `pc-frames/profile/strip-village-1860x1162.png` are the
same strip on the Settings tab and the village at 1860x1162
(`audit-evidence/live/20261025-034323-profile3`). They are test data only.

| Test | Covers | Tests |
| --- | --- | --- |
| `PcGeometryTest` | `PcGeometry`, and the phone's readers on captured PC frames: the village, the quit prompt, the builder counter and list, a Wall row, the army screen, the idle dialog, the scout, the result screen, the selection buttons, and the 16:10 frames | 20 |
| `FarmerServicePcTest` | The ported `FarmerService`: the Clash window as the foreground app, the viewport, the battery, launching Clash, sizing the game surface, refusing every account but the PC's, and refusing unattended starts, and the remote page's account check, before the account is read | 18 |
| `PcAccountSwitcherTest` | The identity check on the profile menu's Settings strip: the Settings tab by its words, and a template cut at 1740x978 or 1860x1162 matching the strip at both sizes and not the village or the profile tab, and the real 1740x978 and 1860x1162 strips matching each other both ways within the cross-size limit while the real 16:10 village does not | 11 |
| `accounts/PcAccountTest` (outside `desktop/`) | The fresh install's neutral account and its no-op on an existing index, the template and frame files, a damaged template counting as not read, `forget` and `undoRead`, the name and tag read off the strip, and the refusal texts | 15 |
| `PcBattleDamageTest` | The battle's damage with the PC's End Battle label between the word and the value | 2 |
| `PcBattleResultTest` | The result screen's loot rows and stars at the PC's lengths | 3 |
| `PcRatePromptTest` | LATER on Play's rating dialog | 3 |
| `PcStarBonusNoticeTest` | Okay on Clash's Star Bonus notice | 4 |
| `PcTrayBadgeTest` | The tray badges' cut levels on the PC and the phone, through a fake OCR backend | 2 |
| `DesktopLogTest` | The desktop log file | 1 |
| `RunTrayTest` | `RunNotifier` through the tray, and the status panel | 5 |
| `platform/ClashWindowWatcherTest` | The Clash window lookup | 4 |
| `platform/SidecarGameSurfaceTest` | Taps, swipes, the pinch, Back and frames through the sidecar | 9 |
| `sidecar/SidecarTest` | The sidecar protocol, against `FakeSidecar` | 9 |
| `ui/` (five files) | The Compose UI; see `docs/UI.md` | 39 |

## When they run

- Gradle runs the tests from the module directory, `app/`. `EventTextTest` and `PhoneProfileTest`
  open files relative to it. `DailyLogFileTest` names `File("logs")` but only computes file names;
  nothing is written there.
- `EventTextTest.everyLoggedEventHasAPlainSentence` needs every event name logged under
  `src/main/kotlin` (outside `insight/`) to be one `EventText` knows, and more than 150 of them.
  The tree logs 251 names: 246 of the phone's 251, `surface_resized`, `surface_aspect_differs`
  and `surface_resize_failed`, which the PC's `FarmerService` logs when it sizes the game, and
  `account_identity_enrolled` and `account_identity_forgotten`, which `AccountSwitcher` logs when it
reads an account and when it drops a read the check did not confirm (Setup's Read account again
logs the second too). The
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

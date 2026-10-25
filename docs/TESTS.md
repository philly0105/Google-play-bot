# Tests

The phone's JVM unit tests, copied from `C:\Users\aideo\Projects\clash-farmer` at `eeaadba`, and
the PC's own tests under `app/src/test/kotlin/local/clashfarmer/desktop/`. None of them needs
Clash, Google Play Games or the sidecar. On 25 October the suite ran 2022 tests, 1637 copied
from the phone and the PC's 385 (364 under `desktop/` and `accounts/PcAccountTest.kt`'s 21), and
none failed.

Run them with `.\Build.ps1 -Tasks ':app:test'`. The test task ends with one line,
`Tests: N ran, N passed, N failed, N skipped`.

## Copied

| Phone path (under `android/`) | PC path | Files | Same blob as the phone |
| --- | --- | --- | --- |
| `app/src/test/java/` | `app/src/test/kotlin/` | 113: 110 test classes and the helpers `core/WallPhone.kt`, `insight/LogFixtures.kt`, `recognition/UpgradeListFixture.kt` | 107; six are edited (below) |
| `app/src/test/resources/` | `app/src/test/resources/` | 23: `hero-cards/` 7, `result-frames/` 15, `upgrade-list/` 1 | 23 |
| `calibration/live-2026-09-17/phone-profile.json` | `calibration/live-2026-09-17/phone-profile.json` at the repo root | 1 | 1 |

`@Test` methods: 1637 in 110 files, the phone's 1635 and two added to `insight/EventTextTest.kt`.
Neither tree has an `@Ignore`.

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
the phone keeps in `src/main/java` and this repo in `src/main/kotlin`. That edit changed no
assertion.

The PC wording of stop reasons, the battery guard and "on this PC" in `insight/` and elsewhere
changed what five copied tests assert: `insight/EventTextTest.kt`, `insight/TimelineTest.kt`,
`insight/ProblemsTest.kt`, `insight/CalibrationHealthTest.kt` and `ui/SetupChecklistTest.kt` take
the new sentences, and `EventTextTest` adds two tests: the PC's own stop reasons, and a crash's
reason that names no exception text. These edits
are also in `docs/DIVERGENCES.md`.

`accounts/AccountStoreTest.kt`, the seeding test: the two accounts' Supercell names and tags are
`AccountStore`'s placeholders, "Supercell ID 1", "#UNREAD1", "Supercell ID 2" and "#UNREAD2",
since the app no longer ships the owner's (`docs/DIVERGENCES.md`).

## PC tests

364 `@Test` methods in 40 files under `desktop/`, and 21 in `accounts/PcAccountTest.kt`. Their
fixtures are in `app/src/test/resources/pc-frames/`: the sidecar's OCR of frames captured from
Blood's game window at 1740x978 and 1860x1162, its OCR of upscaled crops of the builders' counter,
and a few PNG crops. `identity/pc/blood.png` is
Blood's Settings strip at 1740x978, the template the app shipped before each account cut its own;
`identity/pc/blood-1860x1162.png` and `pc-frames/profile/strip-village-1860x1162.png` are the
same strip on the Settings tab and the village at 1860x1162
(`audit-evidence/live/20261025-034323-profile3`). They are test data only.

| Test | Covers | Tests |
| --- | --- | --- |
| `PcGeometryTest` | `PcGeometry`, and the phone's readers on captured PC frames: the village, the quit prompt, the builder counter and list, a Wall row, the army screen, the idle dialog, the scout, the result screen, the selection buttons, and the 16:10 frames, among them the army screen captured at 1860x1162 with its My Army tab read as two boxes | 21 |
| `PcProfilesTest` | `PcProfiles.derive` against Blood's 1740x978 calibration: the same profile at its own size, 1860x1162 and 1565x978 values (Battle inside its button and the storage boxes over their balances on a 16:10 capture), 1920x1080, a round trip, the attack check at four sizes, verification per aspect, refusals, `fitInside`, `plainStopReason` and `SurfaceVerification`; `runSize`, and `startRefusal` sending a calibration not yet verified, moved onto another area, to Calibrate; the army tap on Attack! and clear of the gem and clan buttons, on the 1860x1162 capture and on geo-army's buttons fitted inside each size, and a 16:10 calibration's army tap moved back onto 16:9; the drop points fitted to the battle map at 16:10; on boxes from geo-scout moved by the height, the loot, fee, countdown and first card boxes holding their text as well as at 1740x978 | 23 |
| `FarmerServicePcTest` | The ported `FarmerService`: the Clash window as the foreground app, the viewport, the battery, launching Clash, a window closed by hand or opened again, sizing the game surface, capping it to the monitor, moving the calibration onto the size reached (the same profile at 1740x978, unverified at 16:10 until a supervised attack writes surfaces.json and never profile.json), refusing every account but the PC's, refusing unattended starts and the remote page's account check before the account is read, the PC and its screen held awake for a start set for later and let go when it is cancelled, fires or the app shuts down, a start refused on a locked PC or a dark screen, failure snapshots with their full-size capture, `LatestFrame.heightFor`, the daily pruning, and a PC with no battery told apart without changing the guard's reading | 34 |
| `AtomicStoresTest` | `AtomicFile`, and each of the five stores that use it saved twice | 6 |
| `PcAccountSwitcherTest` | The identity check on the profile menu's Settings strip: the Settings tab by its words, the village and both menu tabs reading as Clash in English, and a template cut at 1740x978 or 1860x1162 matching the strip at both sizes and not the village or the profile tab, and the real 1740x978 and 1860x1162 strips matching each other both ways within the cross-size limit while the real 16:10 village does not | 12 |
| `accounts/PcAccountTest` (outside `desktop/`) | The fresh install's neutral account and its no-op on an existing index, the template and frame files, a damaged template counting as not read, `forget` and `undoRead`, the name and tag read off the strip and which part failed when they don't read, a tag another account holds, Clash's English words told from German and French ones, the refusal texts and their hedged causes, and a fresh install's presets (no walls, no "Both accounts") against an existing presets.json left byte for byte | 21 |
| `PcBuildersCornerTest` | The builders' counter read again from an upscaled crop of a village frame, through a fake OCR backend | 7 |
| `PcUpgradeListTest` | The builder panel's mangled timers, the prices beside them, and a card's name read line by line | 5 |
| `PcReplayTest` | Return Home on a replay's end screen | 5 |
| `PcBattleDamageTest` | The battle's damage with the PC's End Battle label, or a star's stray glyph, between the word and the value, and the result screen sweeping in | 5 |
| `PcBattleResultTest` | The result screen's loot rows, stars and damage box at the PC's lengths, at 1740x978 and on a result captured at 1860x1162, and the damage box holding all of the digits' green ink with the label moved 4 px each way | 6 |
| `PcRatePromptTest` | LATER on Play's rating dialog | 3 |
| `PcStarBonusNoticeTest` | Okay on Clash's Star Bonus notice | 4 |
| `PcTrayBadgeTest` | The tray badges' cut levels on the PC and the phone, through a fake OCR backend | 2 |
| `DesktopLogTest` | The desktop log file, and one log per file for every writer | 2 |
| `RunTrayTest` | `RunNotifier` through the tray, and the status panel | 10 |
| `PcStopLineTest` | The PC's own stop and refusal reasons in `EventText.stopLine`, and the first read's village-not-found advice kept whole | 2 |
| `SingleInstanceTest` | One app per files directory: the lock in one JVM for the same and separate directories, its release, and a later start raising the first; the same against a second JVM holding the lock; a port file that can't be written; and the start messages | 10 |
| `RemotePageTest` | Where the remote page listens, with a fake Tailscale address: off without it, on its IPv4, tailnet IPv6 and loopback addresses, following it as it comes, goes and changes, the 10-minute retry after a taken port against the next-check retry after any other failed bind, reopening a listener that stopped, and detection by adapter name; plus the real server on loopback, free and taken, and Java's own bind failures | 19 |
| `RemoteLinkTest` | The remote link and a new key: 32 hex digits, written to `remote_key.txt`, and on a real server on loopback the old key refused (401) and the new one answered at once, also by a listener opened after the change | 4 |
| `WindowPlacementTest` | Where the window opens: a first start at 1100x760 or shrunk to a scaled laptop's work area, no screens read, a saved place and maximized kept, a second monitor kept and an unplugged one centred, a window pulled back inside the work area or shrunk to it, the smallest size, `window.json` read and written, and the title with a live run's phase | 11 |
| `CrashHandlerTest` | Uncaught failures: which end the app, the dialog's text, its 10-minute limit, and window failures that skip it | 5 |
| `BuildInfoTest` | The version and About text, and the stamped version matching `gradle.properties` | 3 |
| `platform/ClashWindowWatcherTest` | The Clash window lookup, a window opened again by the same game process (and a new game's window that starts hidden, which is not), `pcInfo` and a failed start | 14 |
| `platform/SidecarGameSurfaceTest` | Taps, swipes, the pinch, Back and frames through the sidecar, and old frames on a locked PC, a dark display and a still village | 14 |
| `sidecar/SidecarTest` | The sidecar protocol, against `FakeSidecar`, including frame age, the window's process and full screen, `sessionState`, and `pcInfo` with Clash unknown, hello's text reader error, the monitor's room in `find`, and `power` with no battery and from an older sidecar | 17 |
| `ui/` (fifteen files) | The Compose UI; see `docs/UI.md`. Among them `FirstRunTest`: the first-run flag and the dialog's text | 120 |

## When they run

- Gradle runs the tests from the module directory, `app/`. `EventTextTest` and `PhoneProfileTest`
  open files relative to it. `DailyLogFileTest` names `File("logs")` but only computes file names;
  nothing is written there.
- `EventTextTest.everyLoggedEventHasAPlainSentence` needs every event name logged under
  `src/main/kotlin` (outside `insight/`) to be one `EventText` knows, and more than 150 of them.
  The tree logs 258 names: 246 of the phone's 251; `calibration_failed` and
  `standard_positions_loaded`, which calibration logs; `surface_resized`, `surface_aspect_differs`,
  `surface_resize_failed`, `surface_resize_capped`, `profile_moved`, `surface_verified` and
  `surface_verification_skipped_unverified_calibration`, which the PC's `FarmerService` logs when
  it sizes the game and moves the calibration onto it; `clash_launch_failed`, which it logs when
  starting Clash fails; and `account_identity_enrolled` and `account_identity_forgotten`, which
  `AccountSwitcher` logs when it reads an account and when it drops a read the check did not
  confirm (Setup's Read account again logs the second too). The
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

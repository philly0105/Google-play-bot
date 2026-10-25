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
| `insight/EventText.kt` | Added sentences for `surface_resized`, `surface_aspect_differs` and `surface_resize_failed`, after `gesture_held`. | `FarmerService.fitSurface` logs them when it sizes the game window to its calibration or to the preferred size, and `EventTextTest` requires a sentence for every logged event. |
| `app/src/test/kotlin/local/clashfarmer/insight/EventTextTest.kt` (a test) | Line 22: `File("src/main/java")` is now `File("src/main/kotlin")`. No assertion changed. | The test reads every logged event name from the main source root, which this repo keeps in `src/main/kotlin`. See `docs/TESTS.md`. |
| `core/PcGeometry.kt` (new, PC only) | `isPc(viewport)` is true for a landscape aspect from 1.3:1 up to, not including, 2:1; `pick(viewport, phone, pc)` returns the PC value then and the phone's otherwise. `length`, `x`, `y`, `point`, `region` and `xFraction` place a PC value, given in pixels of 1740x978 with the edge it keeps to (`Anchor.LEFT`, `CENTRE` or `RIGHT`), on a frame of any size: its distance from that edge and its height from the top scale with the frame's height over 978. | Google Play Games draws the game at the aspect of the monitor it launched on and keeps it through a resize: 1740x978 (16:9), and after a relaunch 1860x1162, resized to 1565x978 (16:10). The rows below keep every phone value and add a PC one beside it, chosen through this object. The phone's tests use 1600x720, 2400x1080, 800x360, 1550x720, 1500x620 and 1700x720, all 2.15:1 or wider, and 720x1600, so they keep the phone values; RunGuardTest's 1920x1080 counts as the PC's, and that test only compares sizes. `docs/PC-GEOMETRY.md` lists each value with its anchor and its evidence. |
| `core/Policy.kt` | `QUIT_BODY` takes "che" for "the"; `QUIT_TITLE` lets the t of "Exit" go missing; two last `STATE_RULES` entries read " baftle " and " batfle " with " build " as HOME. | PaddleOCR read the PC's quit prompt as "Do you want Go quit Che game?" and "CONfiRM Exi+", and the village's Battle as "Baftle" (`audit-evidence/spike/geo-after-deselect.json`) and, on six village frames at 1860x1162, "Batfle" (`audit-evidence/live/20261024-030816-profile/00-home.json` among them). These are spellings, not positions, so they apply at every aspect; each only widens a match the phone already makes. |
| `core/Builders.kt` | `PC_CORNER` and `PC_CORNER_BUILDERS_LEFT`, the counters' line above Build and the split between the builders' counter and the goblin's, in pixels of 1740x978 kept from the right edge. `inCorner` and `readCorner` use them on the PC. | The phone's fractions miss the PC's "0/6" at x 1576-1642, y 774-804 of 1740x978 (`audit-evidence/spike/geo-home.json`): the HUD keeps to the bottom-right corner and scales with the height. At 1860x1162 it reads at x 1667-1746, y 920-954 (`audit-evidence/live/20261024-034034-dragmeasure/4a-right150.json`), within 2 px of where the anchoring puts it. |
| `core/WallPanel.kt` | `addOneByPitch` steps `PC_BUTTON_PITCH` on the PC, 140 px at 1740x978 scaled with the height; `priceRegions` takes the PC's half width and offsets above the label, scaled the same way. | At 1740x978 the PC draws a selected building's buttons at the phone's pixel size (`audit-evidence/spike/geo-selected.json`). That they scale with the height like the rest of the HUD is an assumption: they have not been read at 16:10. The price regions are an estimate: no purchase button has been read on the PC. |
| `core/UpgradeListNavigator.kt` | `upgradeListCardShowing` takes the PC's column, x 1272-1740 and y 63-873 of 1740x978, and its flush edge, x 1694, all kept from the right edge. | With the phone's fractions every village frame on the PC passed for an open list: the balances "9 693 255", "167 280" and "2 508" end by x 1678, right of the phone's edge scaled (1631). At 1860x1162 they end by x 1783, and the flush edge is at 1805. |
| `core/CollectionPolicy.kt` | `inVillage` ends the bottom-left corner at `PC_CORNER_LEFT` on the PC, x 679 of 1740x978 kept from the left edge, not at the phone's 0.39 of the width. | The event bar along the bottom left keeps to the left edge: it ends at x 636 at 1740x978 and at 1565x978 alike (`audit-evidence/spike/geo-home.png`, `audit-evidence/live/20261024-025818-resize/1740x978-got-1565x978.png`). At 1565x978 the phone's 0.39 is x 610, inside the "<" that folds the bar, which would leave the bar's last 26 px open to a collection tap. 679 is 0.39 of 1740, so on a 16:9 frame the line moves by a pixel at most. |
| `recognition/ScreenTargets.kt` | `Band` takes an optional PC band, which `RELOAD_BUTTON` has. `BATTLE_READS` adds "baftle" and "batfle". `builderCounterPoint`, `upgradeListClosePoint` and the panel branch of `upgradePopupRegion` place PC values kept from the right edge. `isOwnOverlay` is false on the PC. | Positions from `audit-evidence/spike/geo-home.json` and `geo-list-*.json`. The idle dialog is Android's, centred: its RELOAD GAME reads at (577, 545) of 1740x978 (`audit-evidence/live/20261024-025411-look/frame.png`) and (651, 660) of 1860x1162 (`audit-evidence/live/20261024-031222-focuscheck/0-before.json`), y 0.56-0.57, above the phone band's top at 0.78. The PC's overlay is a window of its own that the capture never holds, and the corner it covered holds the game's name, clan and loot. |
| `recognition/ArmyRecipes.kt` | `Band` takes an optional PC band, which `MY_ARMY_LABEL`, `SAVED_RECIPES_LABEL`, `BUTTON_COLUMN` and `BOOST_ROW` have. `titleBoxes`, `find` and `describe` take `PC_TITLE_LEFT_MIN` and `PC_TITLE_COLUMN`, x at 1740x978 kept from the centre, and `PC_USE_CENTRE_MIN`; `listSwipe` takes a PC swipe kept from the centre. | The PC draws the army screen as the phone's scaled about 1.185 around the centre (`audit-evidence/spike/geo-army.json` and `geo-recipes.json`), and the phone's bands miss both tab labels there. The button column and the Boost row hold at 16:9. At 16:10 the Use buttons leave the column if the screen keeps its scale by the height, and two of the Boost row's four labels leave the row either way it scales; no 16:10 capture of the screen exists, so the PC bands take both ways. |
| `recognition/UpgradeListReader.kt` | On the PC `rowsOf` scales `CARD_LINE_GAP`, 32 px at 1740x978, with the frame's height. | A card's second line sits 22-29 px under its name at 1740x978; moved to 1860x1162 as the model moves the panel, that is 27-34 px, and the phone's 32 split three of the captured cards. No list has been captured at 16:10. |
| `recognition/UpgradeListReader.kt` | On the PC, `rowsOf` drops boxes that end within `PC_ICON_COLUMN` of the panel's left edge, 80 px at 1740x978 scaled with the height, not 50; `rowOf` leaves boxes that start at x 1696 of 1740x978 or later, kept from the right edge, out of the price, and samples a Wall row's currency icon from there to the frame's edge. | With 50, the PC's level badges "se" and "20" (ending at x 1336 and 1344) were glued onto the card names after them, which would lose a Wall row. The coin read as a "0" of its own made the price "4 400 000 0", and the icon region after the price missed the coin on 5 of 9 priced cards, where now all 9 read gold (`audit-evidence/spike/geo-list-end.json` and `.png`). |
| `recognition/PhoneProfile.kt` | On the PC, `withTroopCard` sizes the card area around the tap with `PC_CARD_LEFT`, `PC_CARD_RIGHT`, `PC_CARD_ABOVE` and `PC_CARD_BELOW`, 37, 38, 57 and 59 px of 1740x978 scaled with the frame's height, in place of the phone's 33, 34, 50 and 52. A top and bottom taken from the card's existing area, or the valkyries', are kept as before. | Calibration's Apply builds a card's area from its tap. The PC draws the battle tray's cards 1.13 times the phone's size: 102x134 px, 110 px apart, at 1740x978 (`audit-evidence/spike/geo-scout.png`), where Sweat's phone had 89x119, 97 apart. With the phone's offsets the area's top sat 7 px low there, and `AttackPlanner.badgeBand`, which hangs off that top, started at y 848 and cut the first card's x44 badge, whose ink starts at 843. With the PC's it runs y 841-866. The badges themselves stay the phone's size, so `AttackPlanner` is unchanged. |
| `accounts/AccountSwitcher.kt` | The display check takes a PC game surface of any size as well as 1600x720 (`supportedDisplay`). On a PC frame the profile card, the menu's Settings tab, its X and the Layout Editor's X are pressed at `PC_PROFILE_BUTTON`, `PC_SETTINGS_TAB`, `PC_SETTINGS_CLOSE_BTN` and `PC_LAYOUT_EDITOR_CLOSE` (`onDisplay`), and the identity is read from `PC_SETTINGS_IDENTITY_REGION` against `identity/pc/blood.png` only (`identityRegion`, `identityTemplates`); a strip of another size than the template is scaled to it before the comparison (`identityDifference`). On the PC a mismatched identity stops the run with `PC_SWITCH_REFUSED` before the switch arrows are pressed. | The phone's check refused every PC surface, and its points and strip miss the PC's menu. The PC values are in pixels of 1740x978, read on Blood's menu (`audit-evidence/live/20261024-134805-profile2`): the card at (77, 92), kept to the left edge, opened the menu; the Settings tab, the X and the strip are kept to the middle. The template is the strip of that Settings tab, 275x93, which differs by 52 to 86 on the village and the profile tab, against the limit of 8. The Layout Editor's X is an estimate: the editor has not been seen on the PC. The PC runs only Blood and never signs accounts in or out, so nothing on the switch's path, the arrows, the picker or its rows, was measured. |
| `recognition/ScreenReader.kt` | `readTrayBadge` binarises a PC frame's badge band at `PC_BADGE_CUTS`, 175, 185, 205 and 210, in place of the phone's 235 to 250. | The PC draws the badges' white at 220 to 229 luminance (`audit-evidence/spike/geo-scout.png`), so every phone cut blanked them and the tray read as unknown. On that frame each PC cut reads the x44 Valkyries, the x5 rages and the x1 jumps, and the King's card beside the Valkyries reads no badge on any cut from 145 to 225 (`audit-evidence/calibration/badge-cuts-5.txt`). The phone kept its cuts above the event card's pale background, 220 on its scout; the PC's event card has not been measured, so whether these cuts read its badge is unverified. |
| `core/RatePrompt.kt` (new, PC only) | `later(boxes)` finds Google Play's "Rate Clash of Clans" dialog by its title or its body's "rate or review", and returns the centre of LATER read below them, or null. | The dialog came up over the village as Blood's supervised attack of 24 October returned home, and the run ended with it still open (`audit-evidence/live/20261024-163618-look`). It reads as the village, so nothing in the phone tree answered it. LATER is the only button it names: REVIEW leaves for the Play Store, and NEVER is the operator's choice. |
| `core/StarBonusNotice.kt` (new, PC only) | `okay(boxes)` finds Clash's "Star Bonus Received!" notice by its title or its body's "delivered to the Treasury", and returns the centre of an Okay or OK read below them, or null. It returns null when anything on the frame sells (`Policy.spendsPremium`, `Policy.showsShop`). | The notice came up over the village as Blood's third supervised attack of 24 October returned home with the day's fifth star, and the run ended with it still open (`audit-evidence/live/20261024-171712-look`). It reads as the village, and its dim hid all three balances from the checkpoint after that attack (`balance_unreadable` six times). The bonus is already paid when it shows; Okay only closes it. On the phone, Clash 18.647 paid the bonus through Claim Reward and a chest, so the phone tree has nothing for it. |
| `core/LocalRunner.kt` | `observeSettled` asks `RatePrompt.later`, then `StarBonusNotice.okay`, when the frame is not Clash's quit prompt, and presses the button found at most `MAX_POPUP_TAPS` times, logged as `popup_dismissed` with popup `rate_prompt` or `star_bonus_notice`, before it returns the frame as the village. | Both read as the village like the quit prompt, so the next attack's Battle press would have gone to them. Every pass of the run's loop starts in `observeSettled`, which already answers the quit prompt the same way. |
| `recognition/BattleDamage.kt` | `OVERALL` skips one battle-button label between "Overall Damage" and the value: a word of seven to ten letters starting with s, or a word starting with e followed by one starting with b. | The PC's recogniser returns lines in reading order, so the button's "SURReNdeR" or "End Battle" stood between the word and the value on all nine damage readings of the 24 October attack, and none read (`audit-evidence/live/20261024-163029-supervised/events.jsonl`). Without a damage the early end at a damage and the stall watcher's damage go unused. |
| `recognition/BattleDamage.kt` | `OVERALL` also skips up to two one-character tokens before the label and up to two after it; a token that could be a digit (0-9, s, o, i, l, z, g, $, b) or the sign is never skipped. New `resultWipe(rawText)` is true when the text holds "total damage". | The stars beside the value read as a lone "e", "d" or "*" (`Overall Damage d End Battle * 87%`). On Blood's unattended run of 25 October 18 of 336 damage samples went unread that way, twelve in a row in one battle, and the stall watcher took a battle climbing from 79% to 93% for a stall. With the skip all 18 read, and the 24 October readings are unchanged. `resultWipe` is for the row below. |
| `core/LocalRunner.kt` | `feedStallShadow` logs `stall_damage_unreadable` only when the text is not the result screen sweeping in (`BattleDamage.resultWipe`). The sample is still fed to the stall watcher as before. | The result screen's "Total damage: 0%" counts up from zero over the last battle frame, which is neither a damage nor a reader miss; logged, it made the miss count no longer the reader's (one frame on 25 October). |
| `recognition/BattleResultReader.kt` | On a PC frame `lootRegions` hangs the rows at `PC_ROW_LEFT`, `PC_ROW_RIGHT`, `PC_ROW_OFFSETS` and `PC_ROW_HEIGHT`, `starCentres` samples `PC_STAR_OFFSETS`, and a new `glyphCount(roi, viewport, pixel)` counts with `PC_GLYPH_GAP`, `PC_WIDE_GLYPH` and `PC_EDGE`, all in pixels of 1740x978 scaled by `PcGeometry.length`. The phone's `glyphCount(roi, pixel)` stays and keeps its lengths. | The phone's lengths are pixels of 1600x720. On both result screens of 24 October they put the elixir and dark rows between the digits, so no row read, and their star points fell off the stars, so both battles recorded 0 stars. The PC's digits are also wider (up to 24 px) and further apart (17 px). With the PC values all seven result frames of the second battle read 1 346 371, 474 889 and 4 314 on every cut with the anchor moved up to 11 px, and 2 stars (`audit-evidence/live/20261024-165200-result`). The damage region is unchanged: it read 70% on every mask of every frame. A defeat's result screen has not been seen on the PC. |
| `recognition/ScreenReader.kt` | `readResultLoot` passes the frame's size to `BattleResultReader.glyphCount`. | So that a PC frame's rows are counted at the PC's lengths (row above). |
| `core/Builders.kt` | New `pcCornerRegion(viewport)`, `PC_CORNER` grown by `PC_CORNER_MARGIN` (12 px at 1740x978) and kept from the right edge, null off the PC; `PC_CORNER_SCALE = 3`; and `withCorner(full, crop, viewport)`, which swaps the corner's boxes for a crop's. | A whole PC frame reaches the recogniser shrunk to about 0.56, and its 28 px counter read on 3 of the 65 captures that show it. The same recogniser, run offline on the x3 crop of these regions, read the builders' count on 62 of them at 1740x978 and 1860x1162; the other three are two army screens and one "076" (`app/src/test/resources/pc-frames/corner-*.json`). Without the count the wall phase cannot tell whether a builder is free. |
| `recognition/ScreenReader.kt` | `observe` on a PC frame classed HOME whose boxes read no builder count reads `Builders.pcCornerRegion` again through `observeRegion` at `Builders.PC_CORNER_SCALE`, and keeps the crop's corner boxes in place of the frame's when they read a count. `rawText` and the state are the whole frame's. | So that `Builders.read` finds the count on the PC (row above). One more read of a 534x252 image per village frame that missed it; the phone, a frame that read the count and every other screen take none. |
| `recognition/UpgradeListReader.kt` | New `isTimer(text, viewport)`: `isCountdown`, and on the PC also text with a digit, no run of three digits, and a word of up to two digits or lookalikes (I, l, O, o, B, S, G, E), a unit (d, h, H, m, M, s) and at most one digit after it. `read`'s flush-price count and page items, `rowsOf`'s priced card and `rowOf`'s prices use it in place of `isCountdown`. | The PC's recogniser reads a card's timer as "Id B8H", "Gd 9H", "4d0", "10 9H" or "40M 0s" (`audit-evidence/spike/geo-list-*.json`). Taken for a price, the timer on the name line stood as the card's price and the real one on the line below was dropped: Royal Champion, Barbarian King, Archer Queen, Monolith, Air Bomb and Builder's Hut lost theirs. Every price has a run of three digits. |
| `recognition/UpgradeListReader.kt` | On the PC, `rowsOf` builds a panel card from its lines in order, each line sorted left to right, and leaves out a joined line's boxes left of the split that hold no letter; `rowOf` takes such a card's boxes in that order (`inOrder`) rather than sorting them by x. | Elixir Collector's "983", on the line under its name and 1 px further left, started the name ("983 ElixiR CollectoR x 7"), and a subtitle 2-4 px left of "Crafting Station" came before it ("Hero Hunter Poison Effect Crafting Station"); a few pixels decided which word a page comparison keyed the row on (`audit-evidence/spike/geo-list-end.json`, `geo-list-scroll1.json`, `geo-list-top.json`). |
| `recognition/ScreenTargets.kt` | `returnHomeTarget`, when no button line reads in `RESULT_BUTTON` and the screen is a result screen, returns on the PC the centre of a Return box and a Home box both read in `PC_REPLAY_RETURN`, the bottom-left x 0-0.2, y 0.8-1.0, before falling back to the calibrated point. | A defence replay's end screen reads as a result screen (Total damage, Troops expended), with its Return Home at x 40-190, y 1053-1131 of 1860x1162 (`audit-evidence/live/20261024-033349-look`). The calibrated point there is bare village, so the run would tap it and never get home. None of the eleven saved battle-result frames reads anything in that corner, and matchmaking's Return Home there has no result words, so neither changes. |

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
    spike, where a relaunch did not (`docs/gpg-spike.md`, Restarting). Where no calibration sets
    the size, the bot works at `preferredSurface`: 1740x978, the size every PC measurement was
    taken at, when the surface has that aspect, else the surface's own size. `captureClashGame`
    (Calibrate's capture, Inspect and the tray check) sizes the surface to it first, and
    Calibrate's starting profile uses it, so a calibration is taken at the size runs are fitted
    to. The sidecar's resize then moves a window that runs off its monitor's work area back
    onto it, on the same monitor, without activating it.
  - Accounts: the PC runs only Blood, the user's call on 24 October. `startCombinedRun`,
    `startSwitchOnly` and `startGuardedAccountSession` refuse any other account before they start,
    so the account check never switches accounts in Clash; the switcher refuses as well, before
    the switch arrows (`accounts/AccountSwitcher.kt` above). `startSwitchOnly` checks an account
    with no calibration at `preferredSurface`, where the phone defaulted to 1600x720, the one
    display its switcher supported.
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
  the app instead. It also stays hidden until the tray's Status panel is ticked, which starts
  unticked at every launch, since the user found the pill in the way. The card's folded state is kept per launch, not in preferences, and
  `hideForCapture` only runs its action, since the capture is of the Clash window alone.
  `OverlayGeometry` is verbatim (phone lines 492-533). `StatusBubble` and `PauseGlyph` are drawn in
  Compose in `StatusPanel.kt`.
- `remote/RemoteServer.kt` is verbatim through shims (above), not ported or stubbed. It holds
  `ServiceRemoteBackend`, which calls `FarmerService`.

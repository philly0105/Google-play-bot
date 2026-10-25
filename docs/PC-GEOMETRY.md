# PC screen geometry

The phone bot finds and taps Clash's screens at fractions or pixels of 1600x720. This file lists
where those positions fall on the PC's frames, the PC value the code adds where the phone's
misses, and what still needs a live capture. Every PC value sits behind `PcGeometry.isPc`, so
nothing changes at 1600x720 and the copied phone tests are untouched.

Code paths are relative to `app/src/main/kotlin/local/clashfarmer/`. Evidence is in
`audit-evidence/`, which is gitignored and exists only in the main checkout. In the tables, `geo-x`
stands for `audit-evidence/spike/geo-x.json` and its `.png`, and `live-x` for the fixture
`app/src/test/resources/pc-frames/live-x.json`, whose source is listed under Tests.

All positions are frame pixels. After a relaunch, posted taps have landed above their aim
(`docs/gpg-spike.md`). That is the input transport's problem and doesn't change these values. The calibration profile's
positions, such as deploy points, troop cards and the profile's ROIs, are tapped by the user at
the calibrated size; Moving a calibration, below, says how a run moves them onto another one.

## Which frames are the PC

`PcGeometry.isPc(viewport)` is true when the frame is landscape and its width over its height is at
least 1.3 and under 2.0. Every phone size in the tests is 2.15:1 or wider, or portrait. The PC
frames seen so far:

| Frame | Aspect | Seen |
| --- | --- | --- |
| 1740x978 | 1.779 | the spike and all four supervised runs |
| 1920x1080 | 1.778 | a fresh start, per main's `docs/gpg-spike.md` |
| 1280x720 | 1.778 | a 1680x765 window, before the runs resized it |
| 1860x1162 | 1.601 | after a relaunch |
| 1565x978 | 1.600 | 1740x978 requested on the 16:10 surface |

The game keeps its launch aspect through a resize, so a PC frame can be 16:9 or 16:10 and the code
assumes neither. RunGuardTest's 1920x1080 mismatch case only compares sizes and still holds. A 20:9
frame caught mid-resize gets the phone's values, and RunGuard stops input on it.

## Placing a PC value

A PC value is a position in pixels of 1740x978. On a frame `W` wide and `H` high, with
`s = H / 978`:

| Kind | Placed at |
| --- | --- |
| length | `round(v * s)` |
| x kept from the left, LEFT | `left + round(x * s)` |
| x kept from the right, RIGHT | `right - round((1740 - x) * s)` |
| x kept from the centre, CENTRE | `left + W / 2 + round((x - 870) * s)` |
| y | `top + round(y * s)` |

HUD items keep their distance from their edge, scaled by the height. That is the spike's H/858 rule
inside the phone's 55 px inset, restated at 1740x978. Centred panels and popups keep their offset
from the centre. A y needs no anchor, since the height sets the scale. At 16:9 the three anchors
agree within 1.5 px. At 16:10 LEFT and RIGHT put the same value 207 px apart at 1860x1162 and 175 px
apart at 1565x978.

The HUD at 16:10 follows the rule within 5 px:

| Word | Anchor | 1740x978 | 1860x1162 placed | 1860x1162 read | 1565x978 placed | 1565x978 found |
| --- | --- | --- | --- | --- | --- | --- |
| Battle | LEFT | (97,939) | (115,1116) | (114,1111) | (97,939) | (96,939) |
| Blood | LEFT | (181,77) | (215,91) | (217,90) | | |
| Build | RIGHT | (1646,939) | (1748,1116) | (1744,1112) | (1471,939) | (1470,939) |
| SHOP | RIGHT | (1661,520) | (1766,618) | (1766,616) | | |
| 0/6 | RIGHT | (1609,789) | (1704,937) | (1706,937) | (1434,789) | (1433,789) |

The 1860x1162 reads are OCR on `live-1860-village`, and on `live-1860-counter` for 0/6. The
1565x978 positions were found by image correlation on
`audit-evidence/live/20261024-025818-resize/1740x978-got-1565x978.png`, which has no OCR. On
`audit-evidence/live/20261024-162829-resize/1280x720-got-1280x720.png`, Battle, Build and 0/6 sit
within 1.5 px of the rule.

Measured from phone pixels to pixels at 1740x978, the HUD scales by 1.14 from its edge, the upgrade
list's rows by 1.10, the army screen by 1.185 about the centre, the quit prompt by about 1.13 and
the result screen by 978/720 = 1.358 about the centre. A selected building's buttons keep the
phone's pixel size. Where a screen is not captured, "the model" below is the phone's position
scaled by one of these about the phone's centre (800,360) and placed about (870,489).

The army screen has been captured at 16:10 (`live-1860-army`), and Clash fits it inside the game
area: it scales by the smaller of W/1740 and H/978 about the centre, so at 1860x1162 it grows by
the width's 1.069, not the height's 1.188. Its Attack!, gem and clan buttons sit within 6 px of that
rule. The zoomed-out battle map is fitted the same way: on a live 1860x1162 battle
(`audit-evidence/live/20261025-055900-battle1610`) Blood's perimeter segments, fitted, lie on the
grass edge at the left and right corners, and scaled by the height they fall on the beach and in
the forest. The troop tray and the battle HUD follow the height. The profile menu and its Settings
tab, also captured at 16:10, follow the height
(`audit-evidence/live/20261025-034323-profile3`), and so does the result screen
(`live-1860-result`): its label, VICTORY and the gap from "You got" to the first row grow by 1.175
to 1.19 about the centre. No list or recipes tab has been captured at 16:10; Clash may scale them by
the height, which is what CENTRE does, or fit them, by 1.069 at 1860x1162 and 0.899 at 1565x978.
PcGeometryTest checks the recipes tab both ways.

## Moving a calibration

A run, Calibrate's capture, the camera reference and the tray check move a profile calibrated at
one size onto the game area they find (`desktop/PcProfiles.derive`), with the rule above and the
profile's own size in place of 1740x978. At the calibrated size the profile is used as it is.

| Profile field | Anchor | Why |
| --- | --- | --- |
| `stateAnchors` attack, end_battle, find_match | LEFT | Battle and End Battle sit in the HUD's left corners; Find a Match's cards run from the left |
| `stateAnchors` next | RIGHT | Next sits in the HUD's right corner |
| `stateAnchors` army | fitted | the army screen is fitted inside the game area: scaled about the centre by min(W/1740, H/978) over the same for the calibration's size |
| `deploymentPoints`, `perimeterSegments`, `heroDrop`, `stateAnchors` hero_drop | fitted | the zoomed-out battle map is fitted like the army screen |
| other `stateAnchors` (return_home, ...) | CENTRE | drawn on centred panels |
| `troopAnchors`, `troopRois` | CENTRE | the tray is centred along the bottom |
| `wallPoints`, `wallRows` | CENTRE | the home village's map, about the centre; not seen at 16:10, and it may be fitted like the battle map |
| `wallConfig` dialog fields | CENTRE | centred dialog, not seen at 16:10 |
| `homeResourceRois`, `wallConfig.resourceRois`, `scoutFeeRoi` | RIGHT | the storage balances and the fee sit at the right edge |
| `scoutLootRois` | LEFT | the scout's loot panel sits at the left edge |
| `scoutCountdownRoi` | CENTRE | centred at the top |
| `collectionTargets` | dropped | measured on the camera reference, taken at the calibrated size |

Proven on 16:10 captures (PcProfilesTest): Battle moved from Blood's 1740x978 calibration lands
inside its button on `live-1860-village`, the storage boxes over the balances the OCR read, and the
army tap on Attack! on `live-1860-army`, 20 px or more from the gem and clan buttons. End Battle,
Next, the countdown, the loot and the tray's cards matched the rule within 6 px on a 16:10 scout
(`audit-evidence/live/20261025-034044-scout`). The first four attacks at 16:10, with the drops
scaled by the height, each tapped the Valkyries' card a second time (`card_retry`) because the
first drops fell off the map; the fitted segments are drawn on the same battle in
`audit-evidence/live/20261025-055900-battle1610`. A moved profile counts as verified only after a
supervised attack at that aspect (`desktop/SurfaceVerification`, `accounts/<id>/surfaces.json`).

## PC values

The phone's value still applies at 1600x720 in every row.

| File | Constant | Phone | PC value | Evidence |
| --- | --- | --- | --- | --- |
| `recognition/ScreenTargets.kt` | `builderCounterPoint` | (1430,553) | (1609,789), RIGHT | The 0/6 box on `geo-home` centres there. The phone's fractions give (1555,751), outside the box. At 16:10 the placed point lands inside the box on `live-1860-counter` and on the 1565x978 image. |
| `recognition/ScreenTargets.kt` | `upgradeListClosePoint` | (1520,28) | (1711,28), RIGHT | The list's X is [1690,10,1732,47] on `geo-list-top` and [1690,10,1730,45] on `geo-list-scroll1`. The phone's (1653,38) misses it. At 1860x1162 the rule puts the X at x 1801-1850 and the point at (1826,33), by the model only. |
| `recognition/ScreenTargets.kt` | `upgradePopupRegion`, list panel | x 1118-1545, y 60-625 | (1272,63,1740,873), RIGHT | The list is a full-height panel. Its rows run x 1283-1738, y 61-856 on the three `geo-list-*` frames. |
| `recognition/ScreenTargets.kt` | `RELOAD_BUTTON` | x 0.3-0.7, y 0.78-1.0 | x 0.25-0.75, y 0.5-1.0, fractions | Android's idle dialog draws RELOAD GAME at (578,545), fraction (0.332,0.557), on `live-1740-idle`, and at (651,660), fraction (0.350,0.568), on `live-1860-idle`. Both sit above the phone's band. The supervised runs tapped (578,545) three times, each followed by `takeover_reloaded`. |
| `recognition/ScreenTargets.kt` | `isOwnOverlay` | the top-left 600x105 | off | The PC's panel is a window of its own that the capture never holds. Clash's name, clan and scout loot sit in that corner. |
| `core/Builders.kt` | `PC_CORNER` | x 0.872-0.956, y 0.729-0.803 | (1569,756,1723,816), RIGHT | On `geo-home` the builders' 0/6 centres at (0.925,0.807), below the phone's band, and the goblin's 0/1 at x 1680, past the band's right edge at 1664. |
| `core/Builders.kt` | `PC_CORNER_BUILDERS_LEFT` | 1450 of 1600 | 1632, RIGHT | The builders' 0/6 starts at x 1576 and the goblin's 0/1 at 1651. |
| `core/UpgradeListNavigator.kt` | `upgradeListCardShowing` | left 1118 and flush 1500 of 1600, top 60 and bottom 625 of 720 | left 1272 and flush 1694, RIGHT; top 63, bottom 873 | Prices end at x 1708-1738. The village's balances end by 1678, and by 1783 at 1860x1162. With the phone's flush line at 1631, every village capture passed for an open list. The phone's top and bottom fall at y 81 and 849, inside the rows at 61-856. |
| `core/WallPanel.kt` | `PC_BUTTON_PITCH` | 143 of 1600 wide | 140, a length | A selected building's labels centre at x 592, 732, 872 and 1152 on `geo-selected`, 140 apart. The phone's formula gives 155. |
| `core/WallPanel.kt` | `priceRegions` | half-width 75 of 1600, from 95 to 35 of 720 above the label | half-width 75, from 86 to 26 above the label, lengths, estimated | The PC's buttons keep the phone's pixel size, and the PC label's top sits about 76 px under its button's top where the phone's sat 81-85. No purchase button has been read on the PC. |
| `core/CollectionPolicy.kt` | `PC_CORNER_LEFT` | 0.39 | 679, LEFT | The event bar ends at x 636 on `geo-home` and on the 1565x978 image. There the phone's fraction falls at 610, inside the bar's "<". |
| `recognition/ArmyRecipes.kt` | `MY_ARMY_LABEL` | x 0.15-0.35, y 0.03-0.14 | x 0.12-0.34, y 0.08-0.22 | The tab label [333,121,474,159] centres at (0.232,0.143) on `geo-army`, below the phone's band. |
| `recognition/ArmyRecipes.kt` | `SAVED_RECIPES_LABEL` | x 0.40-0.60, y 0.03-0.14 | x 0.39-0.61, y 0.08-0.22 | The tab label [773,123,966,161] centres at (0.499,0.145). |
| `recognition/ArmyRecipes.kt` | `BUTTON_COLUMN` | x 0.80-0.89, y 0.14-1.0 | x 0.80-0.95, y 0.14-1.0, estimated | Use centres at x 0.876 on `geo-recipes`, inside both. Kept from the centre at 1860x1162 it moves to 0.918. |
| `recognition/ArmyRecipes.kt` | `BOOST_ROW` | x 0.55-0.85, y 0.12-0.26 | x 0.55-0.90, y 0.12-0.30, estimated | The boosts read at (1212,210) and (1428,210) on `geo-army`. At 16:10 "HeRoes" moves to x 0.857 by the height, or to y 0.264 by the width; on `live-1860-army` it reads at (0.823,0.262). |
| `recognition/ArmyRecipes.kt` | `PC_TITLE_COLUMN` | 240 of 1600 | 197, CENTRE | Card titles start at x 209-210 on `geo-recipes`, and the phone's line at 261 counted every one as stray. 197 keeps the phone's 11 px gap, scaled. |
| `recognition/ArmyRecipes.kt` | `PC_TITLE_LEFT_MIN` | 160 of 1600 | 112, CENTRE, estimated | The phone's line catches a title with the drag handle merged in, which starts near x 175. 112 is the phone's 160 moved by the model at 1.185. No merged title has been read on the PC. |
| `recognition/ArmyRecipes.kt` | `PC_USE_CENTRE_MIN` | 170 of 720 | 264 of 978 | Boost Heroes ends at y 247 on `geo-myarmy`, and the first Use centres at 277 on `geo-recipes`. The phone's line falls at 231, above Boost Heroes' end. |
| `recognition/ArmyRecipes.kt` | `listSwipe` | (1080,600) to (1080,300) of 1600x720 | (1202,773) to (1202,418), CENTRE, estimated | The cards are 296 px apart. The phone's fractions drag 407 px, 1.375 cards, and 355 px drags 1.2. The PC's distance is not measured. |
| `recognition/UpgradeListReader.kt` | `PC_ICON_COLUMN` | 50 | 80, a length | Level badges such as "se" [1292,175,1336,210] and "20" [1285,595,1344,623] end up to 72 px into the panel. With 50 they joined the names after them, "Giant Bomb x 8" and "Hero Hunter Poison Effect". |
| `recognition/UpgradeListReader.kt` | `PC_COIN_COLUMN` | a sample 30 px past the price | 1696, RIGHT | The coin's yellow is at x 1708-1724 on `geo-list-end`. OCR returns it as a "0" from x 1699 or 1702, or runs a price on to 1734. The phone's sample missed the coin on 5 of 9 priced cards, and the column finds gold on all 9. |
| `recognition/UpgradeListReader.kt` | `CARD_LINE_GAP` on the PC | 32 | 32, a length, estimated | Second lines sit 22-29 px under their names at 1740x978. Moved to 1860x1162 by the model that is 27-34 px, and a fixed 32 would split three captured cards. |
| `core/AttackPlanner.kt` | `PC_CARD_PITCH` | none: the King's tap to the Queen's, 97 px | 110, a length | The standard positions put the heroes at x 429, 539, 649 and 759 and the rage and jump cards at 881 and 991, and the cards on `geo-scout` are 110 px apart from hero to hero. Used only when no two calibrated hero cards, nor the rage and jump cards, measure the width. At 1860x1162 it is 131 px; the standard heroes moved there sit 130 and 131 px apart. |

## Phone values that hold

| File | Constant | Evidence | Verdict |
| --- | --- | --- | --- |
| `recognition/ScreenTargets.kt` | `RESULT_BUTTON`, `RESULT_COLUMN`, `BUTTON_SPREAD` | On `live-1740-result`, RetURN centres at (0.501,0.843) and Home at y 0.870, with the result words above. All four runs pressed Return Home at (872,851). Moved about the centre to 16:10, both ways, Return Home stays in the band. | holds |
| `recognition/ScreenTargets.kt` | `HOME_ATTACK` | Battle at (0.056,0.960), and (0.061,0.956) on `live-1860-village`. | holds |
| `recognition/ScreenTargets.kt` | `NEXT_BUTTON`, `SURRENDER_BUTTON` | Next at (1641,723) and End Battle at (105,767) on `geo-scout`, fractions (0.943,0.739) and (0.060,0.784). Surrender itself is not captured. | holds |
| `recognition/ScreenTargets.kt` | `SEARCH_BUTTON`, `SEARCH_TITLE`, `CONFIRM_BUTTON` | By the model only. The quit prompt's OkaY sits at (0.590,0.618), inside `CONFIRM_BUTTON`. | holds by the model |
| `recognition/ScreenTargets.kt` | `isBuilderPanel` | The list's title reads at (1504,28). | holds |
| `recognition/ScreenTargets.kt` | `laboratoryColumnRegion` | Calls `upgradePopupRegion` with no boxes, so it gets the phone's popup region. | not captured |
| `core/Builders.kt` | `COUNTER_*` | The top-bar fallback. No PC capture reads a count there. | holds |
| `core/Builders.kt` | `DIALOG`, `GEM_BUTTON`, `CLOSE_CORNER` | By the model only. At 16:10 by the height with 1.358, the busy dialog's X would sit at x 0.726, past `CLOSE_CORNER`'s 0.719. A miss presses Back. | holds by the model, thin at 16:10 |
| `core/WallPanel.kt` | `BAND`, the 76/100 filter | "TowN Hall (Level18)" and INfO read inside the band on `geo-selected`. The button labels start at y 804, below the 76% line at 743. | holds |
| `recognition/ArmyRecipes.kt` | `TITLE_LEFT_MAX`, `LIST_TOP` | The tab label starts at x 333 against the line at 330.6, and its top at 121 against 129. | holds, thin |
| `recognition/ArmyRecipes.kt` | `USE_GAP`, `USE_SLACK` | Uses sit 53-55 px apart, 0.054-0.056 of the height, inside 0.035-0.097. The slack is 5.4 px on boxes 32-37 px tall. | holds |
| `recognition/UpgradeListReader.kt` | `PRICE_EDGE` | The earliest price end, x 1708, clears the line at 1695 by 13 px. At 1860x1162 by the model the margin is 7 px. | holds, thin at 16:10 |
| `core/CollectionPolicy.kt` | `BALANCES` | The gold bar starts 43 px right of the line at 1740x978, about 6 px at 1860x1162 and 7 px at 1565x978, from images. A RIGHT value of 1375 would restore the margin and was not made. | holds, thin at 16:10 |
| `core/CollectionPolicy.kt` | `CORNERS_TOP` | The globe button at x 315-400 starts at y 782, 30 px above the line at 812, so a collection tap in that strip could hit it. | holds, thin |
| `core/CollectionPolicy.kt` | `CORNER_RIGHT`, `OVERLAY`, `PANEL` | The book starts at x 0.836, right of the line at 0.74. The name and clan end at x 271, y 118. The panel reads open on `geo-selected` and `geo-home-4` only. | holds |
| `core/CameraRegistration.kt` | the window, `MAX_DIFFERENCE` | The shield countdown overlaps the window's top-right corner by 28x29 px at 1740x978 and 50x29 px at 1565x978, of about 1251x503. | holds |
| `core/PopupGuard.kt` | `DIALOG`, `BUTTON`, `SHOP_CORNER`, `ATTACK_CORNER` | The idle dialog at (0.341,0.442). The Star Bonus notice's OkaY at (0.505,0.839). Build at (0.946,0.960) and Battle as above. | holds |
| `core/PopupGuard.kt` | `CLOSE_CORNER`, `CHEST_TAP_LABEL`, `CHEST_X`, `CHEST_Y`, `CHEST_RARITY` | By the model only. `CHEST_RARITY` is thin. | holds by the model |
| `core/PopupGuard.kt` | `CHEST_CONTINUE`, `BUBBLE`, `MAX_LINE_GAP` | The model misses `CHEST_CONTINUE` at 1.10, and at 1.185 by the width at 16:10. A miss taps nothing. No speech bubble has been captured. | not captured |
| `recognition/HeroCards.kt` | `SEARCH_ABOVE`, `SEARCH_GAP` | A hero's bar sits 19-32 px above its card at 1740x978 by the model, inside 40. At 1860x1162 that is 23-38 px. | holds at 16:9, not captured at 16:10 |
| `core/AndroidWallDevice.kt`, `core/WallRunner.kt`, `core/WallPanel.kt` | the wall flow's regions and drifts | Blood's walls are maxed, so the bulk panel, its confirm and a Wall card's price have never shown on the PC. | not captured |

`accounts/AccountIdentityParser.kt` reads text only, apart from `parsePickerRows`, which the PC
never reaches because the PC refuses account switches.

## Spelling fixes

The PC's OCR reads a few words differently. `core/Policy.kt` and `recognition/ScreenTargets.kt` take
these as well:

- `QUIT_BODY` takes "Che" for "the", from "Do you want Go quit Che game?" on `geo-after-deselect`.
- `QUIT_TITLE` takes "Exi+" for "Exit", from "CONfiRM Exi+" on the same frame.
- `BATTLE_READS` and the HOME rules in `STATE_RULES` take "baftle", from `geo-after-deselect`, and
  "batfle", from six frames at 1860x1162.
- A RESULT rule in `STATE_RULES` takes " home " with "troops expended", for Return Home read as
  "RefurN HoMe" at 1860x1162. In the first unattended run at 16:10, on 25 October, that made four of
  the first eight results unknown: two waited out the transition until the run restarted, and two
  lost their loot when the second result frame read as nothing.

## Handled elsewhere

These have PC handling of their own, outside the values above:

- `accounts/AccountSwitcher.kt`, `PhoneProfile.withTroopCard` and
  `desktop/ui/calibration/CalibrationTargets.kt`.
- The tray badge read, `ScreenReader.readTrayBadge` and `AttackPlanner`'s badge band.
- `BattleResultReader`, `BattleDamage`, `core/RatePrompt.kt`, `core/StarBonusNotice.kt` and
  `LocalRunner.observeSettled`. The result reader's PC row lengths agree with 978/720 within 3 px,
  and its first star is exact. At 1860x1162 they scale by the height, as the screen does, and hold
  the three rows and both stars of `live-1860-result`. Its damage box has PC lengths too, the
  phone's times 978/720, scaled the same way: the phone's cut off the digits' lower part at both
  sizes, and at 1860x1162 72% read as "730" and 59% as "c9%".

`AccountSwitcher` calls `returnHomeTarget` with `Point(800, 624)` as its calibrated anchor. On a PC
frame that sits at (0.46,0.638), outside `RESULT_BUTTON`, so `returnHomeTarget` drops it and falls
back to the RetURN box. That fails safe. The PC's equivalent is the phone point moved by 1.358 about
the centre, `PcGeometry.point(viewport, 870, 848, Anchor.CENTRE)`, inside the green button at
x 759-984, y 799-888 on `live-1740-result`.

## Readers that still fail

The six below were fixed on 25 October from captures and logs. None of the fixes has run live yet.

1. The builders' counter read from the whole frame on 3 of the 65 captures that show it. When a
   village frame reads no count, `ScreenReader.observe` now reads `Builders.pcCornerRegion`,
   `PC_CORNER` grown by 12 px, again at x3. Run offline through the sidecar's recogniser, that crop
   read the count on 62 of the 65 at both sizes. The other three are the two army screens and one
   village that read "076", which reads as no count. The `corner-*` fixtures are those crops.
   Since the whole frame almost never reads the count, nearly every village frame on the PC now
   costs this second read.
2. Countdown misreads such as "8H", "10 9H", "Gd 9H", "4d0", "40M 0s" and "Id B8H" passed for prices
   and dropped the real price on the line below. `UpgradeListReader.isTimer` takes them on the PC:
   text with a digit, no run of three digits, and a word shaped like a timer.
3. A card's second line joined its name sorted by x, so "983", 27 px under "ElixiR CollectoR x 7"
   on `geo-list-end`, started the name, and subtitles 2-4 px left of "CRafting StatioN" came before
   it. On the PC a card's lines now keep their order, and a second line's letterless boxes at the
   left are left out.
4. A replay's end screen at 1860x1162 has RetuRN and HoMe at the bottom left and reads as RESULT.
   `returnHomeTarget` now taps those two on the PC when the result button did not read, rather than
   the calibrated point, which is bare village there. The bot never opens replays.
5. A star beside the battle's damage reads as a lone "e", "d" or "*", which hid the value 18 times
   in the 25 October run. `BattleDamage` skips up to two such tokens on each side of the label,
   but never one that could be a digit, a 1 read as |, !, [, ], t or j, or the sign.
   The result screen's "Total damage: 0%" sweeping in is no longer logged as
   `stall_damage_unreadable`. `waiting_unrecognized` stays as the phone logs it, a neutral event.
   Unlike fixes 1-4, neither change takes the frame's size, so both apply on the phone too.
6. At 1860x1162 a result row's leading 1, alone before the first group, was dropped by every cut:
   1 247 184 read as 247 184, and 1 512 953 and 1 501 482 lost their 1 too. The ink count refused
   the short reads, so the loot went unrecorded. `BattleResultReader.withLeadingOne` puts the 1
   back when the ink shows one glyph more, the leftmost a 1's width and a group's gap from the
   rest, and the read's own 1s on the other narrow glyphs.

## Tests

`app/src/test/kotlin/local/clashfarmer/desktop/PcGeometryTest.kt` replays the captures above, and
`PcUpgradeListTest`, `PcReplayTest` and `PcBuildersCornerTest` beside it replay some of them. The
`geo-*` fixtures are byte copies of `audit-evidence/spike/`. The `live-*` fixtures come from:

| Fixture | Source |
| --- | --- |
| `live-1740-idle` | `audit-evidence/live/20261024-134731-profile2/00-home.json` |
| `live-1740-result` | `audit-evidence/live/20261024-165200-result/1792886045068.json` |
| `live-1860-idle` | `audit-evidence/live/20261024-031222-focuscheck/0-before.json` |
| `live-1860-home` | `audit-evidence/live/20261024-030816-profile/00-home.json` |
| `live-1860-village` | `audit-evidence/live/20261024-034034-dragmeasure/4b-right150-back.json` |
| `live-1860-counter` | `audit-evidence/live/20261024-034034-dragmeasure/4a-right150.json` |
| `live-1860-replay` | `audit-evidence/live/20261024-033349-look/frame.json`, the attacker's name replaced |
| `live-1860-army` | `audit-evidence/live/20261025-033812-menus/2-army.json`, the lines above y 150 (the HUD's names and balances) left out |
| `live-1860-result` | `audit-evidence/live/20261025-053311-result1610/1792931695135.json`, the defender's name and clan replaced |

The `corner-*` fixtures hold the lines the sidecar's recogniser read, offline, from the x3 crop of
`Builders.pcCornerRegion` on a saved PNG. Each also has the crop's region and scale.
`PcBuildersCornerTest` hands them back through a fake OCR backend.

| Fixture | Crop of |
| --- | --- |
| `corner-1740-village` | `audit-evidence/spike/geo-home-3.png` |
| `corner-1740-one-free` | `audit-evidence/live/20261024-171712-look/frame.png` |
| `corner-1740-misread` | `audit-evidence/live/20261024-133057-taps/2-after-557-551.png` |
| `corner-1860-village` | `audit-evidence/live/20261024-034034-dragmeasure/4b-right150-back.png` |
| `corner-1860-dragged` | `audit-evidence/live/20261024-034034-dragmeasure/1a-down300.png` |

`PcBattleResultTest` also reads raw RGB crops of the two result frames: `result-1740-rows.rgb` and
`result-1740-stars.rgb` from `live-1740-result`'s PNG, and `result-1860-stars.rgb`, 400x140 from
(740, 180), from `live-1860-result`'s. `result-1860-rows.rgb`, 320x230 from (680, 495), is the rows
of `audit-evidence/live/20261025-065005-results1610/1792936962785.png`, whose elixir lost its 1.

Some inputs are built in the test rather than captured. The 1565x978 HUD boxes come from the image
by correlation. The 16:10 list and recipes screens are the 1740x978 captures moved by the
model. The Wall rows are `geo-list-end` with two countdowns removed and two names changed to
"Wall x 9" and "Wall x 80".

## Still needs live captures

| Capture | Settles |
| --- | --- |
| the recipes tab at 16:10 | whether it is fitted like My Army, and the army values marked estimated |
| the home village zoomed out at 16:10, with walls calibrated | whether `wallPoints` and `wallRows` are fitted like the battle map |
| the upgrade list at 16:10, and how far a swipe scrolls it | the panel, `CARD_LINE_GAP`, the coin column, both list swipes |
| a defeat | the result reader on a battle with no stars and little loot |
| a selected building at 16:10 | `PC_BUTTON_PITCH` |
| a Clash popup with an X, and "All builders are busy!" | `CLOSE_CORNER` in `PopupGuard` and `Builders`, and `Builders`' other regions |
| the takeover and connection-lost dialogs | `RELOAD_BUTTON` for Clash's own Reload |
| Surrender and its confirm dialog | `SURRENDER_BUTTON`, `CONFIRM_BUTTON` |
| matchmaking with its OCR kept | `SEARCH_BUTTON`, `SEARCH_TITLE` |
| the reward chest, if the PC shows one | the `CHEST_*` values |
| a speech bubble | `BUBBLE`, `MAX_LINE_GAP` |
| the heroes at 16:10 | `HeroCards` |
| the laboratory | `laboratoryColumnRegion` |
| the bulk wall panel, its confirm and a Wall card | the wall flow; not possible while Blood's walls are maxed |

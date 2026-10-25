# Status

What has worked on this PC and what has only been tested offline. "Live" means it ran against
Blood in Google Play Games on this PC; "offline" means unit tests on frames captured from that
window. Evidence is in `audit-evidence/` (gitignored). Its folder names are in local time (UTC-7);
the times below are UTC.

## 2026-10-25

### Live

Farm 40, the first unattended run on the PC, started from the app at 01:18 and made 28 attacks
before the user stopped it from the floating panel at 02:33. Every battle ran as the supervised
ones did, and every result screen read its damage, stars and loot:

| Stars | 0 | 1 | 2 | 3 |
| --- | --- | --- | --- | --- |
| Battles | 1 | 14 | 9 | 4 |

The results read 17,324,777 gold, 16,063,540 elixir and 163,858 dark elixir. Gold was already full
at 31,000,000 when the run started, so none of that gold was kept. Results came 2 to 5 minutes
apart. In the same run:

- the stall watcher marked two battles as stalled (`stall_would_surrender`), and ended neither,
  since Blood's settings leave a stalled battle running;
- a star beside the battle's damage hid the value 18 times (`stall_damage_unreadable`); fixed
  offline since, `docs/PC-GEOMETRY.md` "Readers that still fail", item 5;
- the Valkyries' card was tapped a second time 3 times (`card_retry`).

At 09:48 two starts of Farm 40 stopped at once with "Viewport mismatch". Clash's game area was
1860x1162 by then. Google Play Games keeps the shape Clash started with, so the app could not size
it back to 1740x978, and Blood's calibration is 1740x978. The app now moves the
calibration onto the area Clash has (`docs/PC-GEOMETRY.md`, "Moving a calibration"), and asks for
one supervised attack at a new shape before it runs unattended there.

At 12:29 and 12:33, two supervised attacks ran at 1860x1162 on `fd09aba`. Each confirmed Blood,
opened Battle, found a match, tapped Attack! on the army screen, deployed, watched the battle and
pressed Return Home. Clash fits the army screen inside the game area rather than scaling it by the
height, and the tap that worked is the fitted one; the height's would have missed the button.
The first attack verified the 16:10 shape: `surfaces.json` in Blood's folder records it, and
`profile.json` was not changed. The second started as verified.

| Start | Damage | Stars read | Earned read |
| --- | --- | --- | --- |
| 12:29 | not read | 1 | 178,064 gold, 318,462 elixir, 1,119 dark |
| 12:33 | 72% on the screen, not read | 2 | 577,557 gold, 232,449 elixir, 2,039 dark |

Evidence: `audit-evidence/live/20261025-033812-menus` (the army screen),
`20261025-034044-scout`, `20261025-034323-profile3` (the profile menu and Settings) and
`20261025-053311-result1610` (the second result screen).

### Offline only

- The result's damage at 16:10. Its box now scales with the frame's height, and read 72% on all
  four settled frames of the second result. Neither 16:10 attack read its damage before the fix.

### Not done

- An unattended run at 16:10.
- A defeat, walls, ending a battle early, the in-app Calibrate flow, the remote page from another
  device and the SendInput fallback, as on 24 October.

## 2026-10-24

### Setup

- Clash ran in GPG with a 1740x978 game surface, the size the PC positions were measured at
  (`core/PcGeometry.kt`).
- Blood's calibration is a profile built from captured 1740x978 frames (the deploy edges, the
  button anchors, the loot and balance boxes) and written into the app's data folder before the
  first run. The in-app Calibrate flow has not been run on the PC.

### Live

Before the first run, an account check (remote mode `switch`) sized the game surface from
1280x720 to 1740x978, pressed RELOAD GAME on Clash's "Anyone there?" idle dialog and confirmed
Blood was the account in Clash. Then four supervised runs of one attack each, started through the
remote API from this PC (`audit-evidence/live/20261024-*-supervised`). Every run:

- pressed RELOAD GAME on the idle dialog when it was up (the third and fourth runs),
- confirmed Blood was the account in Clash,
- read the storage, opened Battle, found a match and checked the army,
- read the troop tray and the hero cards, zoomed out, cast the spells, deployed the four heroes
  and used their abilities, and watched the battle's damage and loot until it ended,
- read the result, pressed Return Home, read the storage again, recorded the battle in the
  history and posted a notification.

All input was posted to the Clash window while another window had the focus. The sidecar has no
SendInput path, so the cursor never moves.

| Start | Build | Damage | Stars read | Earned read | Notes |
| --- | --- | --- | --- | --- | --- |
| 23:30 | `eedcc93` | 72% | 0, wrong | No | The battle's damage and the result's rows and stars did not read. Play's rating dialog came up afterwards. This run verified Blood's profile. |
| 23:52 | `bc9b144` | 70% | 0, wrong | No | The battle's damage read; the result's rows and stars still did not. |
| 00:10 | `17925f1` | 100% | 3 | 644,495 gold, 475,627 elixir, 4,625 dark, as on the screen | Clash's Star Bonus notice was left over the village and hid the balances from the last storage read. |
| 00:23 | `0c9ea16` | 90% | 2 | 321,299 gold, 385,059 elixir, 3,297 dark | Every read worked. |

### Offline only

- LATER on Play's rating dialog (`core/RatePrompt.kt`, fixture `pc-frames/live-1740-rate.json`).
  The dialog came up once, after the first run, before the fix was built.
- Okay on Clash's Star Bonus notice (`core/StarBonusNotice.kt`, fixture
  `pc-frames/live-1740-star-bonus.json`). The bonus comes once a day, and the idle reload had
  cleared the notice before the build with the fix ran.

### Not done

- Walls. Blood's walls are maxed, so no wall has been bought on the PC and nothing that reads a
  wall's price has run live.
- Unattended runs (Farm, Farm 40, Farm 2h, Event). None has run on the PC. The first supervised run
  verified Blood's profile, so they are unlocked.
- Ending a battle early. The stall watcher ran in every battle but never ended one.
- A lost battle. No defeat screen has been seen on the PC.
- The in-app Calibrate flow.
- The remote page from another device. Windows asked once to let the app through the firewall for
  port 8765, and the user allowed it on 24 October; only this PC has called the API.
- Any account but Blood. The PC refuses them by design.
- The SendInput fallback in `docs/PLAN.md`. Posted input has worked for everything so far.

### Known issues

- Blood's gold is full at 31,000,000 and its walls are maxed. An unattended run may stop at its
  first storage check, because storage is full and nothing is left to spend the gold on.
- The app's own window comes to the front when it starts, even when launched minimized. Runs do
  not take the focus.
- The remote status lists Sweat beside Blood, because the account store keeps the phone's two
  accounts. Every way of starting a run refuses Sweat.
- The last frames of a battle sometimes give one unreadable loot or damage read
  (`battle_loot_unreadable`, `stall_damage_unreadable`). The reads before and after are fine.
- Building the distributable fails while the app is running. Quit it from the tray first.
- Some readers still fail on PC captures (`docs/PC-GEOMETRY.md`, "Readers that still fail"). The
  builder counter reads on 2 of 39 village captures, so the builder count a storage read records
  is often missing, and the wall phase cannot always tell whether a builder is free. The upgrade
  list can take a misread countdown for a price on a row in progress, never on a Wall row.

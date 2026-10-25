# Clash Farmer PC

A Windows desktop bot that farms Clash of Clans running in Google Play Games for PC (GPG). It is a
port of the Android bot in `C:\Users\aideo\Projects\clash-farmer` (branch `ui-overhaul`), with the
phone's attack, wall, collection, history and remote logic. It drives the Clash window through
Windows instead of an Android accessibility service, and it replaces the phone bot.

Botting breaks Supercell's terms of service. Supercell can ban any account the bot drives, and the
ban can be permanent.

## What it does

- Attacks with one army plan: a mass troop such as Valkyries dropped all around the base, a spell
  such as Rage cast five times inside, an optional Jump spell, and up to four heroes. It runs
  Battle, Find a Match, the army check, then heroes, abilities, spells and the event card, with
  the phone's stall watcher and optional early end. It
  reads the result screen (loot, damage, stars) and records every battle.
- Between attacks it reads the village's storage. It stops when storage is full and nothing is left
  to spend it on, and it can buy walls when that is turned on for the account.
- It works in the background. Input is posted to the Clash window, so the cursor and the keyboard
  stay yours and Clash can sit behind other windows. Before each run, and before Calibrate,
  Inspect and the tray check, it sizes a 16:9 game back to 1740x978, the size it was calibrated
  at, or to the largest that fits when the monitor is smaller.
- Google Play Games keeps the game's shape from when it opened, so after a relaunch the game can
  be 16:10 (1860x1162) instead of 16:9. A run then leaves the game at that size and moves the
  calibration onto it, without saving the moved points. It refuses a shape narrower than 1.3:1 or
  2:1 and wider, or a game under 720 rows. At a shape the account wasn't calibrated at, unattended
  runs wait for one supervised attack there, and a calibration that was never verified has to be
  taken again on that game area; Setup's Game window line says which applies.
- It never spends gems and never presses a button on a dialog that shows a gem price. It runs one
  account, the one signed in to Clash on this PC, and never signs accounts in or out.
- The dashboard has the phone's tabs (Home, Run, Stats, Activity, Setup); Ctrl+1 to Ctrl+5 and
  Ctrl+Tab move between them, and Tab moves between controls. The app also has a tray icon,
  Windows notifications, and the phone's remote page and API on port 8765, reachable only over
  Tailscale. The tray menu's Status panel shows a small always-on-top panel during runs; it is
  off at every launch.

`docs/STATUS.md` says what has been proven live on this PC and what has only been tested offline.

## Requirements

- Windows 11 with Google Play Games for PC, Clash of Clans installed and signed in, set to English.
  On Windows 10, Windows draws a yellow frame around the game while the bot captures it, and the
  bot has not been tested there; Setup shows an amber Windows line.
- The PC must stay unlocked with the screen on during a run. Locking it or letting the screen turn
  off stops the game's picture, and the bot stops the run with a message saying so. A start set
  for later keeps the PC awake with the screen on until it fires, and does not run if the PC is
  locked then.
- Don't run Clash of Clans full screen in Google Play Games, and don't close its window by hand
  while Google Play Games keeps running: opening it again needs a PC restart before the bot's
  taps reach the game. Setup's Game window line flags both.
- To build: the .NET 8 SDK, and the JDK 21 and Gradle 8.11.1 that `Build.ps1` expects in `tools/`
  (gitignored; copy them from `clash-farmer/tools/android-build`). The built app bundles its own
  Java runtime and a self-contained sidecar, so running it needs neither. The build copies the
  Visual C++ runtime DLLs from this PC's System32 next to the sidecar, and stops if they are
  missing.

## Build and run

```powershell
powershell -ExecutionPolicy Bypass -File .\Build.ps1                      # tests + distributable
powershell -ExecutionPolicy Bypass -File .\Build.ps1 -Tasks ':app:test'   # tests only
```

The app is `app\build\compose\binaries\main\app\ClashFarmerPC\ClashFarmerPC.exe`. Rebuilding the
distributable fails with "Unable to delete directory" while that app is running; quit it from the
tray first.

The version is `appVersion` in `gradle.properties`, and only there. The exe, Setup's About line,
`desktop.log` and the package name all read it.

Setup has a line for each thing a run needs: the Clash window, the text reader (the sidecar's
OCR), the tray icon, battery, the remote page (which needs Tailscale), and per account whether the
bot has read it, the calibration, the troop tray, walls, the goal and rules, and builders. Open
Clash from the Game window line, calibrate the account, then run one supervised attack. A new
account can start calibration from the standard positions and only check its troop cards,
pressing Not in my army on any hero or jump spell it doesn't have.
Unattended runs stay locked until a supervised attack has verified the account's profile. The
bottom of Setup names the version and has Open logs folder.

A fresh install starts with one account called "Your village". The first supervised attack opens
Clash's Settings, reads the village name and tag, and keeps a crop of that strip in
`accounts\<id>\identity.png` with the frame size it was cut at in `identity.json`. Every later run
checks Settings against that crop before it starts; a crop cut at 1740x978 matches Clash at
1860x1162 and the other way round (tested on real captures of both; other sizes are untested). A
read that the check straight after it does not confirm is dropped, so a misread can't lock the
account. Until the account is read, Setup's Account line is amber and unattended starts, and the
remote page's account check, are refused with "Run one supervised attack first so the bot can read
your account." Setup's Read account again drops the crop, so the next supervised attack reads the
account afresh.

Closing the window leaves the bot running behind the tray icon; Quit is in the icon's menu.
Starting the app again while it runs opens its window instead of a second copy. App data, logs and
captures live in `%LOCALAPPDATA%\ClashFarmerPC\files\`, laid out like the phone's files directory.
A failure nobody caught goes to `logs\desktop.log` there, and a dialog says so. Old logs and
captures are pruned at start and once a day after that.

The remote page listens on port 8765 only on this PC's Tailscale addresses (IPv4 and the tailnet
IPv6 one) and on 127.0.0.1, never on the LAN. It checks for Tailscale every minute and turns on
or off with it. Setup says why it is off: Tailscale not connected, port 8765 taken by another
program (tried again every 10 minutes), or an address Windows won't use yet, as just after sleep
(tried again every minute). Its key is
`remote_key.txt` in the files directory. Windows asks once to let the app through the firewall.

## Sending it to someone

```powershell
powershell -ExecutionPolicy Bypass -File .\Package.ps1               # tests, build, zip
powershell -ExecutionPolicy Bypass -File .\Package.ps1 -SkipTests    # build and zip only
```

It writes `dist\ClashFarmerPC-<version>-<date>.zip` (gitignored) and prints its SHA256. The zip
holds the `ClashFarmerPC` folder and `packaging\READ-ME-FIRST.txt`: how to unzip and start it, the
SmartScreen "More info > Run anyway" step (the exe isn't signed), the requirements (GPG, Clash in
English), calibrate then one supervised attack, and the ban risk. Raise `appVersion` before
sending a new build.

## Layout

- `app/` is the Kotlin/JVM app (one Gradle module, Compose for Desktop). `local.clashfarmer.*` is
  the phone code; `local.clashfarmer.desktop.*` is Windows-only code; `android.*` and
  `com.google.*` are shims that let the phone code compile unchanged.
- `native/ClashFarmer.Native/` is the C# .NET 8 sidecar: Windows Graphics Capture, OCR
  (RapidOcrNet) and window input. Its protocol is in `docs/SIDECAR.md`.
- `docs/PLAN.md` has the architecture and the phone-to-PC mapping. `docs/DIVERGENCES.md` lists
  every edit to a file that was copied from the phone verbatim, and why. `docs/gpg-spike.md` has
  what was measured about GPG on this PC, and `docs/PC-GEOMETRY.md` where the phone's screen
  positions fall on its frames. `docs/TESTS.md` and `docs/UI.md` cover the tests and the Compose
  UI.

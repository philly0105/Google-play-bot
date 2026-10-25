# Clash Farmer PC

A Windows desktop bot that farms Clash of Clans running in Google Play Games for PC (GPG). It is a
port of the Android bot in `C:\Users\aideo\Projects\clash-farmer` (branch `ui-overhaul`), with the
phone's attack, wall, collection, history and remote logic. It drives the Clash window through
Windows instead of an Android accessibility service, and it replaces the phone bot.

Botting breaks Supercell's terms of service. Supercell can ban any account the bot drives, and the
ban can be permanent.

## What it does

- Attacks with the army Clash has trained: Battle, Find a Match, the army check, then heroes,
  abilities, spells and the event card, with the phone's stall watcher and optional early end. It
  reads the result screen (loot, damage, stars) and records every battle.
- Between attacks it reads the village's storage. It stops when storage is full and nothing is left
  to spend it on, and it can buy walls when that is turned on for the account.
- It works in the background. Input is posted to the Clash window, so the cursor and the keyboard
  stay yours and Clash can sit behind other windows. Before each run, and before Calibrate,
  Inspect and the tray check, it sizes the game back to 1740x978, the size it was calibrated at.
- It never spends gems and never presses a button on a dialog that shows a gem price. It runs only
  the Blood account and never signs accounts in or out.
- The dashboard has the phone's tabs (Home, Run, Stats, Activity, Setup). The app also has a tray
  icon, Windows notifications, and the phone's remote page and API on port 8765. The tray menu's
  Status panel shows a small always-on-top panel during runs; it is off at every launch.

`docs/STATUS.md` says what has been proven live on this PC and what has only been tested offline.

## Requirements

- Windows 11 with Google Play Games for PC, Clash of Clans installed, and Blood signed in. On
  Windows 10, Windows draws a yellow frame around the game while the bot captures it, and the bot
  has not been tested there; Setup shows an amber Windows line.
- The PC must stay unlocked with the screen on during a run. Locking it or letting the screen turn
  off stops the game's picture, and the bot stops the run with a message saying so. A start set
  for later keeps the PC from sleeping until it fires.
- Don't run Clash of Clans full screen in Google Play Games, and don't close its window by hand
  while Google Play Games keeps running: opening it again needs a PC restart before the bot's
  taps reach the game. Setup's Game window line flags both.
- To build: the .NET 8 SDK, and the JDK 21 and Gradle 8.11.1 that `Build.ps1` expects in `tools/`
  (gitignored; copy them from `clash-farmer/tools/android-build`). The built app bundles its own
  Java runtime and a self-contained sidecar, so running it needs neither.

## Build and run

```powershell
powershell -ExecutionPolicy Bypass -File .\Build.ps1                      # tests + distributable
powershell -ExecutionPolicy Bypass -File .\Build.ps1 -Tasks ':app:test'   # tests only
```

The app is `app\build\compose\binaries\main\app\ClashFarmerPC\ClashFarmerPC.exe`. Rebuilding the
distributable fails with "Unable to delete directory" while that app is running; quit it from the
tray first.

Setup has a line for each thing a run needs: the Clash window, the tray icon, battery, the remote
page (which needs Tailscale), and per account the calibration, the troop tray, walls, the goal and
rules, and builders. Open Clash from the Game window line, calibrate Blood, then run one
supervised attack. Unattended runs stay locked until a supervised attack has verified the
account's profile.

Closing the window leaves the bot running behind the tray icon; Quit is in the icon's menu. App
data, logs and captures live in `%LOCALAPPDATA%\ClashFarmerPC\files\`, laid out like the phone's
files directory. The remote page's key is `remote_key.txt` there. Windows asks once to let the app
through the firewall for port 8765.

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

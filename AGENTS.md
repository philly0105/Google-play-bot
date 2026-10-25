# Clash Farmer PC

Windows desktop port of the phone bot in `C:\Users\aideo\Projects\clash-farmer` (branch
`ui-overhaul`). It farms Clash of Clans running in Google Play Games for PC (GPG). Read
`docs/PLAN.md` first: it has the architecture, the file classes (verbatim, shimmed, ported,
rewritten) and the phone-to-PC mapping. `docs/DIVERGENCES.md` lists every edit to a file that was
supposed to stay verbatim.

## Layout

- `app/` - the Kotlin/JVM desktop app (one Gradle module). `local.clashfarmer.*` is the phone code;
  `local.clashfarmer.desktop.*` is Windows-only code; `android.*` and `com.google.*` are shims.
- `native/ClashFarmer.Native/` - C# .NET 8 sidecar for capture, OCR and window input.
- `tools/` - gitignored JDK 21 and Gradle 8.11.1, copied from `clash-farmer/tools/android-build`.

## Build and test

```powershell
powershell -ExecutionPolicy Bypass -File .\Build.ps1                      # tests + distributable
powershell -ExecutionPolicy Bypass -File .\Build.ps1 -Tasks ':app:test'   # tests only
dotnet build .\native\ClashFarmer.Native -c Release                       # sidecar
```

`Build.ps1` sets `JAVA_HOME` and `GRADLE_USER_HOME` itself; don't set them. In a git worktree it
uses the main checkout's `tools/`.

## Working rules

- The phone tree is the source of truth for behaviour. Before changing a decision, read the phone
  file it came from and the matching section of `clash-farmer/android/STATUS.md`.
- Don't edit verbatim files casually. If a PC-only reason forces an edit, make it surgical and add
  it to `docs/DIVERGENCES.md`.
- Evidence (captures, logs) goes in `audit-evidence/`, which is gitignored. What a live check
  proved goes in `docs/STATUS.md`.
- Commit locally as you go. The remote is the private repo `philly0105/Google-play-bot`: push
  `main` there when the user asks, and keep other branches local.

## Driving the game

- Only the Blood account. Never spend gems, never press a button on a dialog that shows a gem
  price, never sign accounts in or out.
- The bot runs in the background: don't move the user's real cursor or steal focus, except in
  the documented SendInput fallback.
- Bots violate Supercell's terms and risk a permanent ban. Say so plainly rather than calling
  this safe.

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
  one property that verbatim code names: `Context.POWER_SERVICE` and `PowerManager.isInteractive`
  in `remote/RemoteServer.kt`, and `AccessibilityService.GLOBAL_ACTION_BACK` (fully qualified) in
  `core/LocalRunner.kt`. With them, `RemoteServer.kt`, which PLAN.md lists as ported (class 3),
  stays verbatim (class 2).
- The ML Kit shim returns each OCR line as its own `TextBlock` with one `Line`; ML Kit groups
  lines into blocks. `Text.text` joins them with newlines, as ML Kit does.
- `org.json` is json.org's `org.json:json:20240303`, the same artifact the phone's JVM tests used,
  so tests are unaffected. The phone app ran on Android's org.json, which differs at runtime:
  Android keeps keys in insertion order (json.org's `JSONObject` is a `HashMap`, so files and log
  lines the PC writes have keys in arbitrary order), and Android's `optString` returns `"null"` for
  an explicit JSON null where json.org returns the fallback.

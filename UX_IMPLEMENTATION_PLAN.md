# NewsRadar UX/UI Implementation Plan — "No Noise" Offline Refresh

> Design only. No code changes made. Scope: offline-first, sideloaded, single-user, free.
> Goal: Peacock Teal #006D77 accent, no-border surface cards, mono source tags,
> skeleton loading (no spinners), system-font typography (serif headlines / sans UI / mono metadata).

## 1. Scope Critique (offline sideloaded reality)

### High-value / low-risk (do first)
- **Teal accent #006D77** — `ColorScheme.TEAL` already exists (`ui/theme/Theme.kt:21`) but is
  `0xFF00796B`. One-line color swap, zero runtime risk.
- **Surface-contrast cards (no borders)** — `ui/ArticleCard.kt:89-96` uses `Card` with
  `BorderStroke(2.dp, ageColor)` + `elevation`. Remove border/elevation; rely on
  `#141414` vs `#0A0A0A` (dark) / `#FAFAFA` vs `#FFFFFF` (light) surface contrast. Pure theme work.
- **Mono source tag `[THE GUARDIAN]`** — `ArticleCard.kt:116-120` uppercases `outletName` in
  `primary` but not mono / not bracketed. Add `FontFamily.Monospace` + brackets. Reader overlay
  (`ReaderOverlay.kt:103`) already uppercases outlet — give it same mono tag.
- **System-font typography mapping** — already offline-safe: `ReaderFont.toFamily()`
  (`ReaderOverlay.kt:158`) maps to `FontFamily.Serif/SansSerif/Monospace`. Gap: **feed headlines
  are not serif**. Fix in `ui/theme/Type.kt`.
- **Skeleton loading** — replace 4 `CircularProgressIndicator` call sites with pulsing grey blocks.
  Pure UI, no logic.

### Stretch / defer (out of scope)
- **Source Swipe** — brief marks optional; needs multi-publisher event clustering + gestures. Skip/stub.
- **Custom font files (Playfair/Inter/JetBrains Mono)** — impossible offline-first without bundling;
  use system fallbacks. Not a regression; documented expectation.

## 2. Offline Font Fallback Mapping (no font files)

| Role            | Brief wants            | Offline system fallback        | Where applied                                  |
|-----------------|------------------------|--------------------------------|------------------------------------------------|
| Headlines       | Playfair/Newsreader    | `FontFamily.Serif`             | `Type.kt` `titleLarge`/`headlineSmall`; card title |
| UI              | Inter/Geist            | `FontFamily.Default` (Roboto/Noto Sans) | leave `Type.kt` default                 |
| Metadata/source | JetBrains Mono         | `FontFamily.Monospace`         | `[SOURCE]` tag in card + reader top bar        |

No `fontFamily` is currently set on any `AppTypography` style, so the app silently uses `Default`
(sans). Adding serif to headlines is the only typography change needed.

## 3. Exact File Changes (minimal high-impact diff)

### `ui/theme/Theme.kt`
- Line 21: `ColorScheme.TEAL to Palette(Color(0xFF00796B), Color(0xFF26A69A), Color(0xFF004D40))`
  → primary `Color(0xFF006D77)`, secondary `Color(0xFF4DB6AC)`, tertiary `Color(0xFF004D40)`.
- Add explicit surface values (currently Material3 auto-defaults, not the spec):
  - Dark: `background = Color(0xFF0A0A0A)`, `surface = Color(0xFF141414)`,
    `surfaceVariant = Color(0xFF1E1E1E)`, `onSurface = Color(0xFFEDEDED)`,
    `onSurfaceVariant = Color(0xFFB0B0B0)`.
  - Light: `background = Color(0xFFFFFFFF)`, `surface = Color(0xFFFAFAFA)`,
    `surfaceVariant = Color(0xFFF0F0F0)`, `onSurface = Color(0xFF111111)`,
    `onSurfaceVariant = Color(0xFF555555)`.
  - This is what makes "no-border cards" read as cards (surface on contrasting background).
- `RatingGreen/Amber/Red` (lines 13-15) stay constant — do not touch.

### `ui/theme/Type.kt`
- Set `fontFamily = FontFamily.Serif` on `headlineSmall` and `titleLarge` (headline roles).
  Leave everything else default sans. One import: `import androidx.compose.ui.text.font.FontFamily`.

### `ui/ArticleCard.kt`
- Lines 89-96: replace `Card(...)` with `Surface(color = surface, shape = RoundedCornerShape(12.dp))`,
  no `border`, no `elevation`. Keep same `Modifier.padding`.
- Lines 98-111 hero image: keep; match `clip` radius to new `12.dp`; placeholder bg already `surfaceVariant`.
- Lines 116-120: build `"[${article.outletName.uppercase()}]"`, add `fontFamily = FontFamily.Monospace`,
  small `letterSpacing`, `style = MaterialTheme.typography.labelMedium`. Keep paywall lock beside it.
  Recommend mono `onSurfaceVariant` text with the brackets themselves in `primary` via `AnnotatedString`.
- Age label (lines 137-142) keeps age color as text only — color communicates recency without a border.

### `ui/ReaderOverlay.kt`
- Lines 132-135: remove the `1.dp` `outlineVariant` divider — brief bans dividers. Use whitespace/spacing.
- Lines 245 / 356: replace `CircularProgressIndicator` (reader body + WebView) with `SkeletonReader`.
- Lines 103-108: top-bar outlet → mono `[OUTLET]` tag (same as card).

### `ui/FeedScreen.kt`
- Lines 157-158 (initial load), 189 (`canLoadMore` spinner), 217-219 (refresh spinner),
  295 (`EmptyState` spinner): replace `CircularProgressIndicator` with skeleton.
  - Initial empty → `SkeletonFeedList(itemCount = 6)`.
  - `canLoadMore` → a shimmering `SkeletonCard` row (not a spinner).
  - `state.refreshing` → a 2.dp `surfaceVariant` `ShimmerStrip`, not a spinner.

### `ui/SettingsScreen.kt`
- Lines 112, 216, 288, 343, 384: five `HorizontalDivider` → `Spacer(Modifier.height(20.dp))`
  (No Noise = whitespace). Section titles already use `primary` — keep.

### `ui/WeatherBar.kt`
- Line 48: `primaryContainer` → `surfaceVariant` for consistent surface-contrast language. Low risk.

### New file: `ui/Skeleton.kt` (only new file)
Two composables using `InfiniteTransition` alpha pulse (0.25f → 0.6f, ~1.1s) on
`MaterialTheme.colorScheme.surfaceVariant`:
- `SkeletonCard()` — Surface + 12.dp radius, surface bg, then: `height(180.dp)` image block,
  mono `labelMedium` line, 2-line title block, 2-line summary block. ~3 stacked, 12.dp spacing.
- `SkeletonFeedList(count)` — `LazyColumn` (or `Column` for init) of `SkeletonCard`.
- `SkeletonReader()` — Surface bg, 200.dp hero block, title block, ~6 varied-width body lines.
- `ShimmerStrip()` — 2.dp tall `surfaceVariant` pulsing line for `refreshing` indicator.
- Pulse: `val alpha by rememberInfiniteTransition().animateFloat(...)` then
  `Modifier.background(surfaceVariant.copy(alpha = alpha))`. No spinners anywhere.

## 4. Minimal high-impact order
1. `Theme.kt` (teal + surface spec) — biggest payoff, 0 logic risk.
2. `Type.kt` (serif headlines) — 1 import + 2 lines.
3. `Skeleton.kt` (new) — skeleton system.
4. `ArticleCard.kt` (Surface, mono tag, no border) — signature screen.
5. `FeedScreen.kt` + `ReaderOverlay.kt` (skeletons, kill dividers/spinners).
6. `SettingsScreen.kt` (kill dividers) + `WeatherBar.kt` (surfaceVariant) — consistency pass.

## 5. Issues / notes
- Source Swipe intentionally deferred (stretch).
- Bundled font files out of scope (offline constraint); system fallbacks are the agreed substitute.
  Serif headlines render as device Noto/Droid Serif — acceptable Playfair approximation.
- Default `ColorScheme` is still `BLUE` (`prefs/SettingsStore.kt:49`); optionally flip default to
  `TEAL` (one-line) so new installs get Peacock Teal immediately.
- Pure presentation layer; recommendation engine V2 (separate task) untouched. No learned weights affected.

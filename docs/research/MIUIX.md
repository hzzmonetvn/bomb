# Research — Compose MIUIX

```text
Reference:
https://github.com/compose-miuix-ui/miuix.git @ ac7802c7bb2142cae8427b442119574b6e6901f0
(commit date 2026-08-07)

License: Apache-2.0 (LICENSE) — compatible; reuse permitted with attribution.
Bomb consumes it as a Maven dependency and does not vendor it.

Currently used by Bomb: top.yukonga.miuix.kmp:miuix:0.8.8
  (gradle/libs.versions.toml, consumed by :preview commonMain)
```

---

## 1. Finding that affects Bomb today: upstream is now multi-module

The researched HEAD is split into eight published modules:

```text
miuix-core        utils, MiuixIcons entry
miuix-ui          basic components, theme, layout, overlay, window, anim, color
miuix-preference  preference rows, dropdown menus, popups
miuix-nav         navigation: transitions, state, lifecycle, saveable state
miuix-icons       icon set
miuix-blur        blur effects
miuix-shader      shader support
miuix-squircle    squircle shape
```

and README.md:44-56 documents the new coordinates:

```kotlin
implementation("top.yukonga.miuix.kmp:miuix-ui:<version>")
implementation("top.yukonga.miuix.kmp:miuix-preference:<version>")
implementation("top.yukonga.miuix.kmp:miuix-icons:<version>")
implementation("top.yukonga.miuix.kmp:miuix-blur:<version>")
implementation("top.yukonga.miuix.kmp:miuix-squircle:<version>")
implementation("top.yukonga.miuix.kmp:miuix-nav:<version>")
```

Bomb's `gradle/libs.versions.toml` pins the **single-artifact** coordinate
`top.yukonga.miuix.kmp:miuix:0.8.8`.

**Bomb implications:**

1. This is not urgent — 0.8.8 resolves and builds today — but it is a **migration
   with a deadline**: the single `miuix` artifact will stop receiving updates.
   Track it as a known item, plan the split when Bomb moves off 0.8.8, and take
   only the modules actually used (`miuix-ui`, `miuix-preference`, `miuix-nav`,
   `miuix-icons`) rather than all eight.
2. The existing decision to route **every** MIUIX usage through Bomb's `Bomb*`
   wrapper layer (docs/BOMB_PLAN.md D4) is what makes this migration a
   contained change instead of a repo-wide edit. Confirmed as correct — keep it
   strict, including for `MiuixTheme` and icons.
3. `miuix-nav` did not exist as a separate concern in 0.8.8. Bomb's navigation
   currently uses its own `BombNavigation.kt`; when migrating, evaluate
   `miuix-nav`'s transitions (`NavTransition`, `NavMotion`, `NavDirectionalTransition`,
   `NavSettleEasing`) against Bomb's hand-written predictive-back animation rather
   than adopting it reflexively — Bomb already has working predictive back.

---

## 2. Component inventory vs. the master plan's required wrappers

`miuix-ui/.../basic/` provides:

```text
Badge  BreadcrumbBar  Button  Card  Checkbox  ColorPalette  ColorPicker
Component  Divider  Dropdown  FloatingActionButton  FloatingToolbar
Icon  IconButton  ListPopup  NavigationBar  NavigationRail  NumberPicker
ProgressIndicator  PullToRefresh  RadioButton  Scaffold  ScrollBar  SearchBar
Slider  SmallTitle  Snackbar  Surface  Switch  TabRow  Text  TextField
Tooltip  TopAppBar
```

`miuix-ui/.../overlay/` and `.../window/` provide dialogs and sheets in two
presentation modes: `OverlayDialog` / `WindowDialog`,
`OverlayBottomSheet` / `WindowBottomSheet`, `OverlayListPopup` / `WindowListPopup`
(plus cascading variants), with shared content layouts in `.../layout/`
(`DialogContentLayout.kt`, `BottomSheetContentLayout.kt`).

`miuix-preference/` provides:
`ArrowPreference`, `SwitchPreference`, `SliderPreference`, `CheckboxPreference`,
`RadioButtonPreference`, `OverlayDropdownPreference` / `WindowDropdownPreference`,
`OverlaySpinnerPreference` / `WindowSpinnerPreference`.

Mapping to master plan §4's required `Bomb*` layer:

| Required wrapper | MIUIX backing | Gap |
| --- | --- | --- |
| `BombScaffold` | `basic/Scaffold.kt` | — |
| `BombTopAppBar` | `basic/TopAppBar.kt` | — |
| `BombLargeTitle` | `TopAppBar` large variant + `basic/SmallTitle.kt` | — |
| `BombCard` | `basic/Card.kt` | — |
| `BombPreference` | `preference/ArrowPreference.kt` | — |
| `BombSwitchPreference` | `preference/SwitchPreference.kt` | — |
| `BombSliderPreference` | `preference/SliderPreference.kt` | — |
| `BombSegmentedButton` | `basic/TabRow.kt` is the closest | **partial** — TabRow is navigation-shaped, not a value selector. Bomb builds a segmented control on `Surface` + `Text`, or uses M3E `SingleChoiceSegmentedButtonRow` |
| `BombDialog` | `overlay/OverlayDialog.kt` / `window/WindowDialog.kt` | choose one mode and hide the choice inside the wrapper |
| `BombBottomSheet` | `overlay/OverlayBottomSheet.kt` / `window/WindowBottomSheet.kt` | same |
| `BombAppRow` | `basic/Component.kt` + `Surface` | Bomb-built |
| `BombProcessRow` | as above | Bomb-built |
| `BombStatCard` | `basic/Card.kt` | **Bomb-built content** |
| `BombChart` | none | **Bomb-built** — Compose `Canvas`, no charting dependency |
| `BombMonitorOverlay` | none | **Bomb-built** — it is a `SYSTEM_ALERT_WINDOW` surface, not a MIUIX concern |
| `BombEmptyState` / `BombUnsupportedState` | none | **Bomb-built** — and mandatory from day one |

The Overlay/Window duality is worth a note: the same component exists twice
depending on whether it renders inside the composition or in a separate platform
window. Bomb's wrappers must pick one per component and keep it consistent — a
screen mixing both will show inconsistent dismissal and back behaviour.

---

## 3. Theme system

`miuix-ui/.../theme/`: `MiuixTheme.kt`, `Colors.kt`, `ContentColor.kt`,
`TextStyles.kt`, `DynamicColors.kt`, `MonetMapping.kt`, `ThemeController.kt`,
`DismissState.kt`.

`DynamicColors.kt` + `MonetMapping.kt` mean MIUIX can consume Android's Monet
palette, which is how a HyperOS-native look survives the user changing wallpaper
accent. `ThemeController.kt` centralises light/dark switching.

**Bomb implications:**

1. `BombTheme` wraps `MiuixTheme` and exposes **Bomb tokens** (`BombColors`,
   `BombTypography`, `BombShapes`, `BombMotion`), so feature code never reads
   `MiuixTheme.colorScheme` directly. This is the same containment rule as §1.3.
2. Dark mode is first-class in MIUIX and in master plan §4; the token layer must
   define both from the start, not retrofit dark later.
3. Monet/dynamic color is optional. If Bomb offers it, it must be a user setting
   with a defined fallback palette, not an unconditional dependency on a wallpaper
   the ROM may not expose.

---

## 4. Where Material 3 Expressive belongs

Master plan §4 says M3E is for "charts, telemetry, chips, segmented controls,
transitions, and responsive stat cards" — precisely the gaps in the table above.
That is consistent: MIUIX covers the settings/navigation chrome; M3E covers the
data-display widgets MIUIX has no opinion about.

The rule that keeps this from becoming a mess (master plan §4: "Do not randomly mix
raw MIUIX and stock Material components per screen"):

> Feature code imports only `com.hzzmonet.zkbomb.ui.design.*`. Whether a given
> `Bomb*` component is implemented over MIUIX, over Material 3, or from scratch is
> an implementation detail of `ui/design` and is never visible at the call site.

A lint rule or a simple CI grep (`import top.yukonga.miuix` outside `ui/design`)
enforces it cheaply, and should be added when `:app` exists.

---

## 5. Practical notes

- MIUIX is Compose **Multiplatform**, and its Kotlin/Compose versions are coupled.
  Bomb's `libs.versions.toml` already documents this ("MIUIX 0.8.8 is built against
  Kotlin 2.3.20 / Compose Multiplatform 1.10.3 … do not bump one alone"). That
  comment is correct and should stay; it is the highest-value line in the file.
- The repo ships `AGENTS.md` and `CLAUDE.md` at its root and a `docs/demo` module —
  the demo is the fastest way to check a component's real API before wrapping it.
- `baselineprofile/` exists upstream; Bomb should consider a baseline profile for
  `:app` in Phase 17 (polish), not before.

---

## 6. What Bomb takes

| MIUIX | Bomb decision |
| --- | --- |
| Scaffold / TopAppBar / Card / preference rows / dialogs / sheets | Adopt behind `Bomb*` wrappers |
| Theme + Monet mapping | Wrap in `BombTheme`, expose Bomb tokens only |
| `miuix-nav` transitions | Evaluate at migration time; Bomb already has predictive back |
| TabRow as a segmented control | Insufficient — Bomb builds `BombSegmentedButton` |
| Charts, stat cards, overlay, empty/unsupported states | Not provided — Bomb builds them |
| Single `miuix` artifact | Works at 0.8.8; migrate to the module split on the next version bump |

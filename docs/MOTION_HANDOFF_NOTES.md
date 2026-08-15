# MajorGym Motion Implementation — Status Report

## Architecture
Preserved exactly as it was: `var screen by remember { mutableStateOf<Screen>(...) }`
+ `when(screen)` in `MainActivity.kt`. No Navigation Compose introduced.

## Dependencies
None added. `androidx.compose.animation:animation` was already present in
`app/build.gradle.kts` before this work started — confirmed by inspection,
not assumed.

## Files changed

- `app/src/main/java/com/majorgym/app/ui/Motion.kt` — **new**. Shared
  `GymMotion` tokens (Fast=120/Standard=220/Slow=320/Ambient=1400ms), a
  standard easing curve, `Modifier.gymPressScale()` for button/card press
  feedback, and `LocalReducedMotion` (CompositionLocal, wired at the app
  root in `MainActivity.kt`).
- `app/src/main/java/com/majorgym/app/MainActivity.kt` — screen-to-screen
  `AnimatedContent` transition (fade + slight horizontal slide); reads the
  OS "Remove animations" setting once at launch and provides it via
  `CompositionLocalProvider(LocalReducedMotion provides ...)`; renders the
  new `KioskIdleIndicator`.
- `app/src/main/java/com/majorgym/app/ui/Screens.kt` — bottom nav
  pill/tint/label animation; `StatCard` count-up + press-lift; press-scale
  on Add/Save and Confirm Renewal buttons; `StatusRing`/`StatusBadge`
  animated colors; `PlanGrid` selection animation; animated validation
  error reveal; staggered `RenewalSuccessScreen` entrance; animated Backup
  "Share" button state swap; fade+scale entrance on the full-screen Gym QR
  dialog; stable list keys + `animateItemPlacement()` added where missing.
- `app/src/main/java/com/majorgym/app/ui/MemberListScreens.kt` —
  `animateItemPlacement()` on the filtered stat-card member lists.
- `app/src/main/java/com/majorgym/app/ui/KioskOverlay.kt` — kiosk result
  overlay: fast fade+scale entrance/exit instead of a bare fade; new
  `KioskIdleIndicator` composable (faint pulsing dot while idle, respects
  reduced motion).
- `app/src/main/java/com/majorgym/app/ui/RegistrationSuccessScreen.kt` —
  staggered checkmark (spring) → message → QR (fade+scale) → actions
  entrance.
- `app/src/main/java/com/majorgym/app/ui/FingerprintScreens.kt` — breathing
  pulse ring while waiting for a finger (skipped under reduced motion);
  spring scale+fade icon swap on success; small horizontal shake on
  failure/no-match.
- `app/src/main/java/com/majorgym/app/ui/SyncScreen.kt` — "Sync Now" button
  content cross-fades between idle/working; press-scale added.
- `app/src/main/java/com/majorgym/app/ui/PhotoViewer.kt` — optional
  spring-back: panning stays on plain synchronous state (zero added
  latency, byte-for-byte the original real-time feel); a separate
  `Animatable` handles only the brief "spring back to center" moment
  (zoom released to ≤1x, or double-tap reset). Pinch, pan, double-tap,
  and photo loading are otherwise unchanged.

No other files were modified. `Theme.kt` and `app/build.gradle.kts` are
untouched from the original upload.

## Animations added, by spec section
1. Foundation motion tokens — `ui/Motion.kt`
6. Screen transitions — `MainActivity.kt`
6. Bottom nav indicator/tint/label — `Screens.kt`
7. Dashboard stat count-up — `Screens.kt`
8. Primary button press feedback — Add/Save, Confirm Renewal, Sync Now,
   Share Backup (`Screens.kt`, `SyncScreen.kt`)
9. Kiosk result entrance — `KioskOverlay.kt`
10. Form validation error reveal — `Screens.kt`
11. Membership status color transitions — `Screens.kt`
12. Plan selection animation — `Screens.kt`
13. Member list item placement — `Screens.kt`, `MemberListScreens.kt`
14/15. Registration & renewal success stagger — `RegistrationSuccessScreen.kt`,
   `Screens.kt`
16. Sync/Backup button state swap — `SyncScreen.kt`, `Screens.kt`
17. Full-screen QR dialog entrance — `Screens.kt` (see "Not done" below for
    the other four dialogs)
18/19/20. Fingerprint waiting pulse, success spring, failure shake —
   `FingerprintScreens.kt`
21. QR reveal (registration/renewal success + full-screen QR dialog) —
   covered by 14/15/17 above
22. Photo viewer spring-back (optional) — `PhotoViewer.kt`
23. Kiosk idle indicator, card press lift (Priority 3) — `KioskOverlay.kt`,
   `MainActivity.kt`, `Screens.kt`
24. Reduced motion — `Motion.kt`, `MainActivity.kt`, `FingerprintScreens.kt`,
   `KioskOverlay.kt`

## Hardware safety
Confirmed by inspection: nothing added here delays or gates
`ScannerHub`, `FingerprintKioskService`, `ScannerOwnership`,
`requestStop()`, capture/retry logic, database writes, sync, or backup.
Every animation is layered on top of state that was already computed —
timers/springs/pulses run alongside functional code, never in front of it.
The fingerprint waiting pulse, success spring, and failure shake in
`FingerprintScreens.kt` were specifically checked against `runScan()` and
confirmed not to reference or wrap any of its hardware calls.

## Build result
**Not verified against a real Gradle build.** This sandbox has no Android
SDK and no network access, so `./gradlew build` could not be run. In place
of that, every edited file was checked for: balanced braces/parens,
no duplicate imports, and correct resolution of new symbols (e.g.
`MainActivity.kt`'s `import com.majorgym.app.ui.*` covers the new
`KioskIdleIndicator` / `LocalReducedMotion` / `GymMotion` symbols). Treat
all of this as unverified until it's actually compiled — the most likely
failure mode is a missed or misplaced import given how many passes some
files went through, so a first build-and-fix pass should be expected.

## Deliberately left out / not done

- **Dialog consistency for the remaining four `AlertDialog`s** (Add Photo
  source picker, ID Proof source picker, delete-ID-photo confirm,
  delete-member confirm). Investigated and declined on purpose: Material3's
  `AlertDialog` doesn't expose a way to swap its entrance/exit animation
  without dropping to a raw `Dialog` and rebuilding button/dismiss/back-press
  handling from scratch — real risk for two of these being destructive
  confirmations, for a cosmetic gain the spec itself marks optional
  ("where technically appropriate"). They currently use Compose's default
  dialog transition, which is already a restrained scale+fade, not a jarring
  instant swap — so the inconsistency is minor.
- No formal reduced-motion **toggle** in the app's own UI — only the OS-level
  "Remove animations" setting is respected, per spec section 24's "do not
  over-engineer this."
- No build was run (see above).

## Priority 3 items completed
- Fingerprint waiting pulse ✅
- Kiosk idle indicator ✅ (very faint, top-right, respects reduced motion)
- QR reveal ✅ (via success screens + full-screen QR dialog)
- Card press lift ✅ (StatCard)
- Photo viewer spring-back ✅ (optional, done safely — see above)
- Shared-element avatar expansion — not attempted (spec explicitly flags
  this as optional/advanced and warns against experimental APIs for
  novelty alone)

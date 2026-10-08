# ADR-012: Design tokens and motion system pipeline

**Status:** Accepted, 2026-10-01

## Decision
- **One source of truth:** `design/tokens/*.json`, in the W3C Design Tokens format. It covers colour (OLED dark and light, semantic roles only), type scale, spacing, radius, elevation and motion (durations, easing curves, spring parameters).
- **Generator:** `design/generate.mjs` is dependency-free Node. It emits:
  - `android/.../designsystem/MekaTokens.kt` (Compose `Color`, `TextUnit`, `Dp`, `AnimationSpec` factories)
  - `macos/MekaOS/DesignSystem/MekaTokens.swift` (SwiftUI `Color`, `Font`, `CGFloat`, `Animation`)
- **CI check:** CI runs the generator and fails if the generated files differ from those committed.
- **Motion tokens are semantic:** examples are `motion.complete`, `motion.replan`, `motion.expand`, `motion.syncPulse` and `motion.approve`.
  - Each has a full-motion spec and a reduced-motion spec. Reduced motion means a cross-fade under 150 ms and no translation.
  - Components never use raw durations.
- **Palette:**
  - A neutral scale with one restrained accent.
  - State colours only where state must be read at a glance: critical, approval and offline.
  - *Addendum 2026-10-08 (news ticker, slice 2):* one content colour, `barca` (a garnet, ≥ 3:1 like the state colours), marks Barça's lane in News, the command centre and the ticker. It is never used for UI state or actions; the accent stays the only brand colour.
  - No gradients in tokens. Elevation is expressed with surface tone in dark mode rather than shadows.
- **Choreography tokens** (added 2026-10-05): `choreography` holds sequencing values (stagger step 40 ms with a cap of 8 steps, rise distance, count-up and shimmer periods). They generate `MekaChoreography` on both platforms; `MekaMotionKit` (Kotlin and Swift) is the only code that reads them, and its pure arithmetic (`MotionMath`) is unit-tested on both sides.
- *Addendum 2026-10-08 (motion pass 2, slice 1):* motion follows MEKA's own **Appearance → Motion** setting per device (Expressive · Subtle · Off; `MotionRules` in core), not the system's: with nothing chosen, the phone's "Remove animations" (animator scale 0) or the Mac's Reduce Motion means Off, and Today offers a one-time card. "Reduced" in tokens and kits now means Motion → Off. Expressive values are tokens too: `choreography.expressive*` (stagger 60 ms, rise 28, entry scale 0.96, count-up 900 ms) and `motion.<name>.expressiveDamping` on `complete` (0.75) and `approve` (0.62); the generated `MekaMotion` reads `MotionStyle.expressive` (set by the app's theme root) as the default. On Android the activities compose on a recomposer whose `MotionDurationScale` is 1 (`MotionClock.kt`, its frame clock paused while stopped), since Compose otherwise snaps every animation when the animator scale is 0. On the Mac views read `\.mekaReduceMotion` / `\.mekaExpressiveMotion` from the environment (`.mekaMotion()` at each root) instead of `\.accessibilityReduceMotion`.
- *Addendum 2026-10-08 (motion pass 2, slice 2):* `choreography.pressScale` (0.97, what anything tappable presses in to while held; Off dims instead) and `choreography.hoverLift` (2 dp/pt, how far a card rises under the Mac pointer, with no shadow). The Fold applies the press through MekaTheme's `LocalIndication` (`MekaPressIndication`), the Mac through `MekaPressStyle` on every plain button.
- *Addendum 2026-10-08 (motion pass 2, slice 3):* `choreography.pullThresholdDistance` (64 dp/pt: how far Today follows a pull before letting go syncs), `pullMaxDistance` (112, the rubber band's limit), `syncSpinPeriod` (900 ms: one turn of the brass sync ring, and the least time it shows) and `sheetScrimOpacity` (0.5: the black dim behind a pane on the Fold). The generator treats names ending `Distance` or `Lift` as distances and `Scale` or `Opacity` as unitless. The ring is the accent colour, the only brand colour, so it needs no new token.
- *Addendum 2026-10-08 (motion pass 2, slice 4):* `choreography.checkDraw` (420 ms): completing a task draws the check — the accent ring sweeps round in the first half, the fill floods in as it closes (35–50 %), the check strokes in over the second half — before the row leaves. The phase arithmetic is `MotionMath.checkRingDegrees/checkFill/checkStroke` on both platforms; Motion → Off shows it done at once.

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
  - No gradients in tokens. Elevation is expressed with surface tone in dark mode rather than shadows.
- **Choreography tokens** (added 2026-10-05): `choreography` holds sequencing values (stagger step 40 ms with a cap of 8 steps, rise distance, count-up and shimmer periods). They generate `MekaChoreography` on both platforms; `MekaMotionKit` (Kotlin and Swift) is the only code that reads them, and its pure arithmetic (`MotionMath`) is unit-tested on both sides.

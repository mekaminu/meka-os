import AppKit
import SwiftUI

// Motion foundation (build plan M1). Every screen builds its motion from these helpers so the catalogue in
// docs/build-plan.md is applied the same way everywhere and Reduce Motion is honoured in one place.
// The arithmetic matches android/.../designsystem/MotionMath.kt number for number.

enum MotionMath {
    /// Delay before item `index` of a staggered group appears, in seconds. Capped; zero when reduced. Expressive
    /// (Appearance → Motion) spaces items wider apart.
    static func staggerDelay(index: Int, reduced: Bool, expressive: Bool = false) -> Double {
        if reduced || index <= 0 { return 0 }
        let step = expressive ? MekaChoreography.expressiveStaggerStep : MekaChoreography.staggerStep
        return Double(min(index, MekaChoreography.staggerMaxSteps)) * step
    }

    /// Time a staggered group of `count` items takes to start appearing, in seconds.
    static func staggerSpan(count: Int, reduced: Bool, expressive: Bool = false) -> Double {
        count <= 0 ? 0 : staggerDelay(index: count - 1, reduced: reduced, expressive: expressive)
    }

    /// How far (pt) an appearing item rises from: further in Expressive.
    static func riseDistance(expressive: Bool) -> CGFloat {
        expressive ? MekaChoreography.expressiveRiseDistance : MekaChoreography.riseDistance
    }

    /// How long a count-up runs, in seconds: longer in Expressive.
    static func countUpDuration(expressive: Bool) -> Double {
        expressive ? MekaChoreography.expressiveCountUp : MekaChoreography.countUp
    }

    /// Scale an appearing item starts from: Expressive grows it from 0.96; Subtle and reduced motion never scale.
    static func entryScale(expressive: Bool, reduced: Bool) -> CGFloat {
        reduced || !expressive ? 1 : MekaChoreography.expressiveEntryScale
    }

    /// Scale of anything clickable while held (feedback motion, motion pass 2): 0.97 in Subtle and Expressive; Off never
    /// scales (a brief dim instead, `pressOpacity`).
    static func pressScale(pressed: Bool, reduced: Bool) -> CGFloat {
        pressed && !reduced ? MekaChoreography.pressScale : 1
    }

    /// Opacity of a held item: only Motion → Off dims it, since it doesn't press in.
    static func pressOpacity(pressed: Bool, reduced: Bool) -> Double {
        pressed && reduced ? 0.85 : 1
    }

    /// How far (pt) a card lifts while the pointer is over it. None with Motion → Off.
    static func hoverLift(hovering: Bool, reduced: Bool) -> CGFloat {
        hovering && !reduced ? MekaChoreography.hoverLift : 0
    }

    /// The brass sync ring's longest arc (degrees), so the turning gap reads as motion (motion pass 2, slice 3).
    static let ringArcDegrees: Double = 300

    /// The ring's arc as it fills (`progress` 0…1); the Fold fills it with the pull, the Mac shows it full.
    static func ringSweepDegrees(progress: Double) -> Double { ringArcDegrees * min(max(progress, 0), 1) }

    /// The ring's turn (degrees) `elapsed` seconds into a sync: one turn per `syncSpinPeriod`; still with Motion → Off.
    static func ringSpinDegrees(elapsed: Double, reduced: Bool) -> Double {
        if reduced || elapsed <= 0 { return 0 }
        let period = MekaChoreography.syncSpinPeriod
        return elapsed.truncatingRemainder(dividingBy: period) / period * 360
    }

    /// How much longer (seconds) the ring stays after a sync that took `elapsed`, so a quick sync is still seen.
    static func ringHold(elapsed: Double) -> Double { max(MekaChoreography.syncSpinPeriod - elapsed, 0) }

    /// Completing a task (motion pass 2, slice 4): how long (seconds) the ring-and-check draw runs before the row
    /// leaves. Motion → Off: no draw, shown done at once.
    static func checkDraw(reduced: Bool) -> Double { reduced ? 0 : MekaChoreography.checkDraw }

    /// Where the ring has closed, the fill starts flooding in and the check starts to stroke (fractions of the draw).
    static let checkRingEnd: Double = 0.5
    static let checkFillStart: Double = 0.35
    static let checkStrokeStart: Double = 0.5

    /// The accent ring's sweep (degrees) `fraction` of the way through the draw: once round in the first half.
    static func checkRingDegrees(_ fraction: Double) -> Double { 360 * easeOutCubic(fraction / checkRingEnd) }

    /// How solid the fill inside the ring is: it floods in as the ring closes.
    static func checkFill(_ fraction: Double) -> Double {
        min(max((fraction - checkFillStart) / (checkStrokeStart - checkFillStart), 0), 1)
    }

    /// How much of the check's stroke is drawn (0…1): it strokes in over the second half, short leg first.
    static func checkStroke(_ fraction: Double) -> Double {
        easeOutCubic((fraction - checkStrokeStart) / (1 - checkStrokeStart))
    }

    /// Empty states (motion pass 2, slice 5): how far into a breath the brass ring is `elapsed` seconds after it
    /// appeared, 0 (out) → 1 (in) → 0 over `emptyBreathPeriod`, a smooth cosine. Motion → Off: held still, fully in.
    static func breath(elapsed: Double, reduced: Bool) -> Double {
        if reduced { return 1 }
        let period = MekaChoreography.emptyBreathPeriod
        let phase = max(elapsed, 0).truncatingRemainder(dividingBy: period) / period
        return min(max(0.5 - 0.5 * cos(2 * Double.pi * phase), 0), 1)
    }

    /// The breathing ring's smallest size and faintest glow (the same as the Fold's).
    static let breathMinScale: Double = 0.92
    static let breathMinGlow: Double = 0.35

    /// The ring's size at `breath` (0…1): from `breathMinScale` out to full size in.
    static func breathScale(_ breath: Double) -> Double { breathMinScale + (1 - breathMinScale) * min(max(breath, 0), 1) }

    /// How strongly the ring's glow shows at `breath`: never gone, so it reads as resting, not blinking.
    static func breathGlow(_ breath: Double) -> Double { breathMinGlow + (1 - breathMinGlow) * min(max(breath, 0), 1) }

    /// Ease-out cubic: fast start, gentle landing.
    static func easeOutCubic(_ fraction: Double) -> Double {
        let f = min(max(fraction, 0), 1)
        let inv = 1 - f
        return 1 - inv * inv * inv
    }

    /// The number a count-up shows at `fraction` of the way from `from` to `to`. Lands exactly on `to`.
    static func countUpValue(from: Int, to: Int, fraction: Double) -> Int {
        if fraction >= 1 { return to }
        return Int((Double(from) + Double(to - from) * easeOutCubic(fraction)).rounded())
    }
}

/// Fades an item up after `index × 40 ms` (Expressive: 60 ms apart, rising further and growing from 0.96). When `play`
/// is false it is simply there. Motion → Off: cross-fade only.
private struct StaggeredAppear: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.mekaExpressiveMotion) private var expressive
    let index: Int
    let play: Bool
    @State private var shown = false

    func body(content: Content) -> some View {
        let visible = shown || !play
        content
            .opacity(visible ? 1 : 0)
            .scaleEffect(visible ? 1 : MotionMath.entryScale(expressive: expressive, reduced: reduceMotion))
            .offset(y: visible || reduceMotion ? 0 : MotionMath.riseDistance(expressive: expressive))
            .onAppear {
                guard play, !shown else { return }
                let delay = MotionMath.staggerDelay(index: index, reduced: reduceMotion, expressive: expressive)
                withAnimation(MekaMotion.appear(reduced: reduceMotion).delay(delay)) {
                    shown = true
                }
            }
    }
}

extension View {
    /// Item `index` of a staggered group (catalogue: sections 40 ms apart, timeline blocks cascade).
    func staggeredAppear(_ index: Int, play: Bool = true) -> some View {
        modifier(StaggeredAppear(index: index, play: play))
    }
}

/// A number that counts up to `value` when it first appears and rolls to new values after. Reduce Motion: no count.
struct CountUpText: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.mekaExpressiveMotion) private var expressive
    let value: Int
    let format: (Int) -> String
    @State private var from = 0
    @State private var target = 0
    @State private var start: Date?

    init(_ value: Int, format: @escaping (Int) -> String = { "\($0)" }) {
        self.value = value
        self.format = format
    }

    var body: some View {
        TimelineView(.animation(paused: start == nil)) { context in
            Text(format(shown(at: context.date)))
                .monospacedDigit()
        }
        .accessibilityLabel(format(value))
        .onAppear { retarget(to: value, from: 0) }
        .onChange(of: value) { old, new in retarget(to: new, from: old) }
    }

    private func shown(at date: Date) -> Int {
        guard let start else { return target }
        let fraction = date.timeIntervalSince(start) / MotionMath.countUpDuration(expressive: expressive)
        return MotionMath.countUpValue(from: from, to: target, fraction: fraction)
    }

    private func retarget(to new: Int, from old: Int) {
        target = new
        guard !reduceMotion, old != new else { start = nil; return }
        from = old
        start = .now
        let duration = MotionMath.countUpDuration(expressive: expressive)
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(duration))
            if target == new { start = nil }
        }
    }
}

/// Skeleton rows with a slow shimmer: the catalogue's loading state (never a spinner on its own).
struct SkeletonRows: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let count: Int
    let rowHeight: CGFloat
    let palette: MekaPalette

    init(count: Int = 3, rowHeight: CGFloat = 36, palette: MekaPalette) {
        self.count = count
        self.rowHeight = rowHeight
        self.palette = palette
    }

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let t = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate
                .truncatingRemainder(dividingBy: MekaChoreography.shimmerPeriod) / MekaChoreography.shimmerPeriod
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(0..<count, id: \.self) { i in
                    GeometryReader { geo in
                        let x = (t * 3 - 1) * geo.size.width
                        RoundedRectangle(cornerRadius: MekaRadius.m)
                            .fill(reduceMotion ? AnyShapeStyle(palette.surfaceRaised) : AnyShapeStyle(LinearGradient(
                                colors: [palette.surfaceRaised, palette.hairline, palette.surfaceRaised],
                                startPoint: UnitPoint(x: (x - geo.size.width * 0.4) / max(geo.size.width, 1), y: 0.5),
                                endPoint: UnitPoint(x: (x + geo.size.width * 0.4) / max(geo.size.width, 1), y: 0.5))))
                    }
                    .frame(height: rowHeight)
                    .frame(maxWidth: i == count - 1 ? CGFloat(240) : CGFloat.infinity, alignment: .leading)
                }
            }
        }
        .accessibilityLabel("Loading")
    }
}

/// Every plain button in the app presses in as it's clicked (scale 0.97 on the complete spring; feedback motion,
/// motion pass 2): cards, More rows, text actions and chips, before what they open scale-fades in. It draws only the
/// label, like `.plain`. Motion → Off: a brief dim instead.
struct MekaPressStyle: ButtonStyle {
    @Environment(\.mekaReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(MotionMath.pressScale(pressed: configuration.isPressed, reduced: reduceMotion))
            .opacity(MotionMath.pressOpacity(pressed: configuration.isPressed, reduced: reduceMotion))
            .animation(MekaMotion.complete(reduced: reduceMotion), value: configuration.isPressed)
    }
}

/// Cards lift 2 pt while the pointer is over them (motion pass 2: "hover lifts cards 2 pt"); no shadow, since elevation
/// is surface tone (ADR-012). Motion → Off: still.
private struct MekaHoverLift: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var hovering = false

    func body(content: Content) -> some View {
        content
            .offset(y: -MotionMath.hoverLift(hovering: hovering, reduced: reduceMotion))
            .onHover { h in withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h } }
    }
}

extension View {
    /// Lifts this card 2 pt under the pointer (see `MekaHoverLift`).
    func mekaHoverLift() -> some View { modifier(MekaHoverLift()) }
}

/// Haptics named for what happened (catalogue: light on complete, medium on approve). Trackpad only; silent otherwise.
enum MekaHaptics {
    static func light() { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
    static func medium() { NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now) }
    static func tick() { NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now) }
}

/// The brass sync ring (motion pass 2, slice 3; catalogue: Sync): beside Today's links while a sync you asked for
/// runs (⌘R, the View menu or the command bar), turning once per `syncSpinPeriod`. Motion → Off: a still ring.
struct SyncRingView: View {
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var began = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { context in
            Circle()
                .trim(from: 0, to: MotionMath.ringSweepDegrees(progress: 1) / 360)
                .stroke(palette.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90 + MotionMath.ringSpinDegrees(
                    elapsed: context.date.timeIntervalSince(began), reduced: reduceMotion)))
        }
        .frame(width: 13, height: 13)
        .onAppear { began = Date() }
        .accessibilityElement()
        .accessibilityLabel("Syncing")
    }
}

/// The completion check (motion pass 2, slice 4; catalogue "Complete a task"), drawn like the Fold's `CheckRing`: a
/// quiet outline at rest; once `began` is set the accent ring sweeps round from the top, the inside floods with the
/// accent as it closes and the check strokes in, over `checkDraw`. Motion → Off: shown done at once.
struct CheckRingView: View {
    let palette: MekaPalette
    /// When the draw started; nil = at rest.
    let began: Date?
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: began == nil || reduceMotion)) { context in
            let f = fraction(at: context.date)
            ZStack {
                Circle().strokeBorder(palette.textTertiary, lineWidth: 1.5)
                Circle()
                    .trim(from: 0, to: MotionMath.checkRingDegrees(f) / 360)
                    .stroke(palette.accent, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(0.75)
                Circle().fill(palette.accent.opacity(MotionMath.checkFill(f)))
                CheckMark()
                    .trim(from: 0, to: MotionMath.checkStroke(f))
                    .stroke(palette.onAccent, style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
            }
        }
    }

    private func fraction(at now: Date) -> Double {
        guard let began else { return 0 }
        let total = MotionMath.checkDraw(reduced: reduceMotion)
        if total <= 0 { return 1 }
        return min(max(now.timeIntervalSince(began) / total, 0), 1)
    }
}

/// The check's two strokes, short leg first (the same points as the Fold's).
private struct CheckMark: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + rect.width * 0.30, y: rect.minY + rect.height * 0.52))
        p.addLine(to: CGPoint(x: rect.minX + rect.width * 0.44, y: rect.minY + rect.height * 0.66))
        p.addLine(to: CGPoint(x: rect.minX + rect.width * 0.71, y: rect.minY + rect.height * 0.38))
        return p
    }
}

/// The breathing ring beside an empty state (motion pass 2, slice 5; catalogue "Empty states"), like the Fold's
/// `BreathingRing`: MEKA's brass ring with a soft glow, slowly breathing in and out (`MotionMath.breath`), so
/// "You're clear." feels at rest rather than blank. Motion → Off: held still. Decorative: hidden from VoiceOver.
struct BreathingRingView: View {
    let palette: MekaPalette
    var size: CGFloat = 26
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var began = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { context in
            let b = MotionMath.breath(elapsed: context.date.timeIntervalSince(began), reduced: reduceMotion)
            let glow = MotionMath.breathGlow(b)
            ZStack {
                Circle().fill(palette.accent.opacity(0.18 * glow))
                Circle().strokeBorder(palette.accent.opacity(0.55 + 0.45 * glow), lineWidth: 1.5)
            }
            .scaleEffect(MotionMath.breathScale(b))
        }
        .frame(width: size, height: size)
        .onAppear { began = Date() }
        .accessibilityHidden(true)
    }
}

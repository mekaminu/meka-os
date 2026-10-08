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

/// Cards and More rows press in as they're clicked (scale 0.97 on the complete spring) before what they open
/// scale-fades in (Four tabs, slice 3; the Mac's simpler stand-in for the Fold's travelling titles, rule 7).
/// Reduce Motion: a brief dim instead.
struct MekaPressStyle: ButtonStyle {
    @Environment(\.mekaReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .opacity(configuration.isPressed && reduceMotion ? 0.85 : 1)
            .animation(MekaMotion.complete(reduced: reduceMotion), value: configuration.isPressed)
    }
}

/// Haptics named for what happened (catalogue: light on complete, medium on approve). Trackpad only; silent otherwise.
enum MekaHaptics {
    static func light() { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
    static func medium() { NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now) }
    static func tick() { NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now) }
}

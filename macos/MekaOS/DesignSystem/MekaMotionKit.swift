import AppKit
import SwiftUI

// Motion foundation (build plan M1). Every screen builds its motion from these helpers so the catalogue in
// docs/build-plan.md is applied the same way everywhere and Reduce Motion is honoured in one place.
// The arithmetic matches android/.../designsystem/MotionMath.kt number for number.

enum MotionMath {
    /// Delay before item `index` of a staggered group appears, in seconds. Capped; zero when reduced.
    static func staggerDelay(index: Int, reduced: Bool) -> Double {
        if reduced || index <= 0 { return 0 }
        return Double(min(index, MekaChoreography.staggerMaxSteps)) * MekaChoreography.staggerStep
    }

    /// Time a staggered group of `count` items takes to start appearing, in seconds.
    static func staggerSpan(count: Int, reduced: Bool) -> Double {
        count <= 0 ? 0 : staggerDelay(index: count - 1, reduced: reduced)
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

/// Fades an item up after `index × 40 ms`. When `play` is false it is simply there. Reduce Motion: cross-fade only.
private struct StaggeredAppear: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let index: Int
    let play: Bool
    @State private var shown = false

    func body(content: Content) -> some View {
        let visible = shown || !play
        content
            .opacity(visible ? 1 : 0)
            .offset(y: visible || reduceMotion ? 0 : MekaChoreography.riseDistance)
            .onAppear {
                guard play, !shown else { return }
                withAnimation(MekaMotion.appear(reduced: reduceMotion).delay(MotionMath.staggerDelay(index: index, reduced: reduceMotion))) {
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
        let fraction = date.timeIntervalSince(start) / MekaChoreography.countUp
        return MotionMath.countUpValue(from: from, to: target, fraction: fraction)
    }

    private func retarget(to new: Int, from old: Int) {
        target = new
        guard !reduceMotion, old != new else { start = nil; return }
        from = old
        start = .now
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(MekaChoreography.countUp))
            if target == new { start = nil }
        }
    }
}

/// Skeleton rows with a slow shimmer: the catalogue's loading state (never a spinner on its own).
struct SkeletonRows: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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

/// Haptics named for what happened (catalogue: light on complete, medium on approve). Trackpad only; silent otherwise.
enum MekaHaptics {
    static func light() { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
    static func medium() { NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now) }
    static func tick() { NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now) }
}

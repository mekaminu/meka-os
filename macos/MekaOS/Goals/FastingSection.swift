@preconcurrency import MekaKit
import SwiftUI

/// FASTING on the Mac (build plan M1), at the top of Goals and synced with the Fold: the ring sweeps continuously
/// while a fast runs and glows softly once the goal is reached; the last seven days fill on appear. Start (now or
/// earlier), End, adjust the goal or the start (menus, as rule 7 allows), or discard a mistaken fast.
/// Reduce Motion: the ring steps each second and the glow is steady.
struct FastingSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        if let v = model.fasting {
            VStack(alignment: .leading, spacing: MekaSpace.s) {
                HStack(alignment: .center, spacing: MekaSpace.m) {
                    FastRing(current: v.current, palette: palette)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(v.current != nil ? "Fasting" : "Not fasting").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        if let cur = v.current {
                            Text(cur.goalLine).font(MekaType.itemMeta).foregroundStyle(cur.reachedGoal ? palette.accent : palette.textSecondary)
                            Text(cur.startedLine).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        } else {
                            Text(v.windowLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            if let last = v.last {
                                Text(last.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                        }
                        Text(v.plan.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    Spacer()
                }
                actions(v)
                WeekBars(view: v, palette: palette)
            }
            .padding(MekaSpace.m)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
            .animation(MekaMotion.replan(reduced: reduceMotion), value: v.current?.id)
        }
    }

    @ViewBuilder
    private func actions(_ v: FastingView) -> some View {
        HStack(spacing: MekaSpace.l) {
            if let cur = v.current {
                Button("End fast") { model.endFast() }
                Menu("Goal \(cur.targetHours) h") {
                    ForEach(FastingRules.shared.TARGET_CHOICES, id: \.intValue) { h in
                        Button("\(h.intValue) h") { model.setFastTarget(h.int32Value) }
                    }
                }
                .menuStyle(.button).fixedSize()
                Menu("Started…") {
                    ForEach(FastingRules.shared.MOVE_START_CHOICES, id: \.intValue) { m in
                        Button(FastingRules.shared.moveLabel(min: m.int32Value)) { model.moveFastStart(m.int32Value) }
                    }
                }
                .menuStyle(.button).fixedSize()
                Button("Discard", role: .destructive) { model.discardFast() }.foregroundStyle(palette.critical)
            } else {
                Menu("Start a fast") {
                    ForEach(FastingRules.shared.STARTED_AGO_CHOICES, id: \.intValue) { m in
                        Button(FastingRules.shared.startedAgoLabel(min: m.int32Value)) { model.startFast(minutesAgo: m.int32Value) }
                    }
                } primaryAction: {
                    model.startFast(minutesAgo: 0)
                }
                .menuStyle(.button).fixedSize()
                if let last = v.last, last.canResume {
                    Button("Undo end") { model.resumeFast(last.id) }
                }
                Menu("Plan \(FastingRules.shared.planLabel(p: v.plan))") {
                    ForEach(Array(FastingRules.shared.PLAN_CHOICES.enumerated()), id: \.offset) { i, c in
                        Button(c.label) { model.chooseFastingPlan(i) }
                    }
                }
                .menuStyle(.button).fixedSize()
            }
        }
        .buttonStyle(.plain).font(MekaType.itemTitle).foregroundStyle(palette.accent)
    }
}

/// The ring: sweeps with the clock, glows softly at the goal; the timer in the middle.
private struct FastRing: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let current: FastNow?
    let palette: MekaPalette
    @State private var glow = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let now = Int64(ctx.date.timeIntervalSince1970 * 1000)
            let p = current.map { Double(FastingRules.shared.progress(startedAtMs: $0.startedAtMs, targetHours: $0.targetHours, nowMs: now)) } ?? 0
            ZStack {
                if current?.reachedGoal == true {
                    Circle()
                        .fill(RadialGradient(colors: [palette.accent.opacity(reduceMotion ? 0.3 : (glow ? 0.42 : 0.18)), .clear], center: .center, startRadius: 0, endRadius: 56))
                }
                Circle().stroke(palette.surfaceRaised, lineWidth: 8).padding(10)
                if current != nil {
                    Circle().trim(from: 0, to: p)
                        .stroke(palette.accent, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .padding(10)
                        // A linear one-second glide keeps the sweep continuous between ticks.
                        .animation(reduceMotion ? nil : .linear(duration: 1), value: p)
                }
                VStack(spacing: 0) {
                    if let cur = current {
                        Text(FastingRules.shared.clock(elapsedMs: now - cur.startedAtMs)).font(MekaType.itemTitle).monospacedDigit()
                            .foregroundStyle(palette.textPrimary)
                        Text("of \(cur.targetHours) h").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    } else {
                        Text("—").font(MekaType.itemTitle).foregroundStyle(palette.textTertiary)
                    }
                }
            }
            .frame(width: 112, height: 112)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(current.map { "Fasting \(FastingRules.shared.clock(elapsedMs: now - $0.startedAtMs)) of \($0.targetHours) hours" } ?? "Not fasting")
        }
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { glow = true }
        }
    }
}

/// The last seven days: one bar per day (the longest fast that ended then), filling on appear.
private struct WeekBars: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let view: FastingView
    let palette: MekaPalette
    @State private var shown = false

    var body: some View {
        let days = view.week
        let top = max(24, days.map(\.hours).max() ?? 0)
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(alignment: .bottom, spacing: MekaSpace.xs) {
                ForEach(days, id: \.epochDay) { d in
                    VStack(spacing: MekaSpace.xxs) {
                        GeometryReader { geo in
                            ZStack(alignment: .bottom) {
                                RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.surfaceRaised)
                                if d.hours > 0 {
                                    RoundedRectangle(cornerRadius: MekaRadius.s)
                                        .fill(d.reachedGoal ? palette.accent : palette.textTertiary)
                                        .frame(height: geo.size.height * (shown ? max(0.04, min(1, d.hours / top)) : 0))
                                }
                            }
                        }
                        .frame(height: 56)
                        Text(d.label).font(MekaType.caption).foregroundStyle(d.isToday ? palette.textPrimary : palette.textTertiary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(view.weekLine ?? "No fasts in the last 7 days")
            Text(view.weekLine ?? "Your last 7 days of fasts show here.").font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .onAppear {
            if reduceMotion { shown = true } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = true } }
        }
    }
}

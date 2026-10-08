import Combine
@preconcurrency import MekaKit
import SwiftUI

/// FASTING on the Mac (build plan M1), at the top of Goals and synced with the Fold: the ring sweeps continuously
/// while a fast runs and glows softly once the goal is reached; the last seven days fill on appear. Start (now or
/// earlier), End, adjust the goal or the start (menus, as rule 7 allows), or discard a mistaken fast.
/// Reduce Motion: the ring steps each second and the glow is steady.
/// Fasting v2: an extended fast (24 h … 7 days, or until a day at 18:00) shows "Day 3 of 5 · 62 h"; the history below
/// keeps every fast (planned against actual), the streak and a twelve-week heat strip. Tracking only, no advice.
/// When an extended fast reaches its goal, "You did it · 5 days" pops in and the ring bursts once (a ring of light
/// spreading out with twelve rays), once per fast on this Mac. Reduce Motion: the line fades in, no burst.
/// Longer fast → Pick a Day and Time… opens a popover (the system scale-fades it): a date-and-time field limited to
/// 12 hours … ten days on, the core's line saying the goal or why not (it cross-fades), and Start.
struct FastingSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The fast whose "you did it" burst has already played here.
    @AppStorage("meka.fastBurst") private var burstFastId = ""
    @State private var burst: Double = 0
    @State private var pop: Double = 1
    /// "Pick a Day and Time…" from the Longer fast menu.
    @State private var pickingUntil = false

    var body: some View {
        if let v = model.fasting {
            VStack(alignment: .leading, spacing: MekaSpace.s) {
                HStack(alignment: .center, spacing: MekaSpace.m) {
                    FastRing(current: v.current, palette: palette, burst: burst)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(v.current.map { $0.extended ? $0.title : "Fasting" } ?? "Not fasting")
                            .font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        if let cur = v.current {
                            if let done = cur.doneLine {
                                Text(done).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                                    .scaleEffect(pop, anchor: .leading)
                                    .transition(.opacity)
                            }
                            if let day = cur.dayLine {
                                Text(day).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.accent)
                            }
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
                FastingHistorySection(history: v.history, palette: palette)
            }
            .padding(MekaSpace.m)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
            .animation(MekaMotion.replan(reduced: reduceMotion), value: v.current?.id)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: v.current?.doneLine)
            .onChange(of: v.current?.doneLine == nil ? nil : v.current?.id, initial: true) { _, id in
                celebrate(id)
            }
        }
    }

    /// The "you did it" moment, once per fast on this Mac.
    private func celebrate(_ id: String?) {
        guard let id, id != burstFastId else { return }
        burstFastId = id
        guard !reduceMotion else { return }
        MekaHaptics.light()
        pop = 0.6
        withAnimation(MekaMotion.complete(reduced: false)) { pop = 1 }
        burst = 0
        withAnimation(.easeOut(duration: 0.9)) { burst = 1 } completion: { burst = 0 }
    }

    @ViewBuilder
    private func actions(_ v: FastingView) -> some View {
        HStack(spacing: MekaSpace.l) {
            if let cur = v.current {
                Button("End fast") { model.endFast() }
                if cur.extended {
                    Menu("Goal \(FastingRules.shared.daysLabel(hours: cur.targetHours))") {
                        ForEach(FastingRules.shared.EXTENDED_CHOICES, id: \.hours) { c in
                            Button(c.label) { model.setFastTarget(c.hours) }
                        }
                    }
                    .menuStyle(.button).fixedSize()
                } else {
                    Menu("Goal \(cur.targetHours) h") {
                        ForEach(FastingRules.shared.TARGET_CHOICES, id: \.intValue) { h in
                            Button("\(h.intValue) h") { model.setFastTarget(h.int32Value) }
                        }
                    }
                    .menuStyle(.button).fixedSize()
                }
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
                Menu("Longer fast") {
                    ForEach(FastingRules.shared.EXTENDED_CHOICES, id: \.hours) { c in
                        Button(c.label) { model.startExtendedFast(hours: c.hours) }
                    }
                    Divider()
                    ForEach(v.untilChoices, id: \.untilMs) { c in
                        Button(c.label) { model.startFastUntil(c.untilMs) }
                    }
                    Divider()
                    Button("Pick a Day and Time…") { pickingUntil = true }
                }
                .menuStyle(.button).fixedSize()
                .popover(isPresented: $pickingUntil, arrowEdge: .bottom) {
                    FastUntilPicker(defaultMs: v.untilChoices.first?.untilMs, palette: palette) { ms in
                        pickingUntil = false
                        model.startFastUntil(ms)
                    }
                }
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
    /// 0 → 1 while the "you did it" burst plays; 0 otherwise.
    var burst: Double = 0
    @State private var glow = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let now = Int64(ctx.date.timeIntervalSince1970 * 1000)
            let p = current.map { Double($0.progress(nowMs: now)) } ?? 0
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
                if burst > 0 {
                    BurstShape(progress: burst)
                        .stroke(palette.accent.opacity(1 - burst), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    Circle()
                        .stroke(palette.accent.opacity(0.6 * (1 - burst)), lineWidth: 8 * (1 - burst * 0.7))
                        .padding(10 - burst * 10)
                }
                VStack(spacing: 0) {
                    if let cur = current {
                        Text(FastingRules.shared.clock(elapsedMs: now - cur.startedAtMs)).font(MekaType.itemTitle).monospacedDigit()
                            .foregroundStyle(palette.textPrimary)
                        Text(cur.extended ? "of \(FastingRules.shared.daysLabel(hours: cur.targetHours))" : "of \(cur.targetHours) h")
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
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

/// Twelve short rays spreading out from just outside the ring as [progress] goes 0 → 1.
private struct BurstShape: Shape {
    var progress: Double
    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let r0 = min(rect.width, rect.height) / 2 - 10 + 4 + 4 * progress
        let r1 = r0 + 6 * (0.4 + progress)
        for i in 0..<12 {
            let a = Double(i) * .pi / 6 - .pi / 2
            p.move(to: CGPoint(x: c.x + cos(a) * r0, y: c.y + sin(a) * r0))
            p.addLine(to: CGPoint(x: c.x + cos(a) * r1, y: c.y + sin(a) * r1))
        }
        return p
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

/// Fasting v2's history: the streak, a twelve-week heat strip (hours fasted each day; weeks fade in left to right
/// 40 ms apart) and every fast, planned against actual, behind a disclosure. Reduce Motion: shown at once.
private struct FastingHistorySection: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let history: FastingHistory
    let palette: MekaPalette
    @State private var shown = false
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let streak = history.streakLine {
                Text(streak).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            HStack(alignment: .top, spacing: 3) {
                ForEach(Array(history.heat.enumerated()), id: \.offset) { w, week in
                    VStack(spacing: 3) {
                        ForEach(week, id: \.epochDay) { d in
                            RoundedRectangle(cornerRadius: 2)
                                .fill(color(d))
                                .overlay {
                                    if d.isToday { RoundedRectangle(cornerRadius: 2).stroke(palette.textSecondary, lineWidth: 1) }
                                }
                                .frame(width: 12, height: 12)
                        }
                    }
                    .opacity(shown ? 1 : 0)
                    .animation(reduceMotion ? nil : MekaMotion.replan(reduced: false).delay(Double(w) * 0.04), value: shown)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(history.totalsLine ?? "No fasts yet")
            Text(history.totalsLine ?? "Every fast you finish is kept here.").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            if !history.fasts.isEmpty {
                DisclosureGroup(isExpanded: $expanded) {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(history.fasts, id: \.id) { f in
                            VStack(alignment: .leading, spacing: 0) {
                                HStack {
                                    Text(f.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Spacer()
                                    Text(f.resultLine).font(MekaType.itemMeta).monospacedDigit()
                                        .foregroundStyle(f.reachedGoal ? palette.accent : palette.textSecondary)
                                }
                                Text(f.whenLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                        }
                    }
                    .padding(.top, MekaSpace.xs)
                } label: {
                    Text("History").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
        }
        .onAppear { shown = true }
    }

    private func color(_ d: FastingHeatDay) -> Color {
        if d.isFuture { return palette.surfaceRaised.opacity(0.4) }
        switch d.level {
        case 0: return palette.surfaceRaised
        case 1: return palette.accent.opacity(0.3)
        case 2: return palette.accent.opacity(0.5)
        case 3: return palette.accent.opacity(0.75)
        default: return palette.accent
        }
    }
}

/// A custom "until" end on the Mac: any day and time from 12 hours to ten days on, checked by the core as it changes
/// ("Goal 4 d 18 h · starts now"). Start is enabled only when it's valid.
struct FastUntilPicker: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let start: (Int64) -> Void
    @State private var date: Date
    @State private var now = Date()

    init(defaultMs: Int64?, palette: MekaPalette, start: @escaping (Int64) -> Void) {
        self.palette = palette
        self.start = start
        let fallback = Date().addingTimeInterval(TimeInterval(FastingRules.shared.MIN_UNTIL_HOURS) * 3600 * 2)
        _date = State(initialValue: defaultMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } ?? fallback)
    }

    private static func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }

    var body: some View {
        let nowMs = Self.ms(now)
        let pick = FastingRules.shared.untilPick(nowMs: nowMs, untilMs: Self.ms(date))
        let range = Self.date(FastingRules.shared.untilEarliest(nowMs: nowMs))...Self.date(FastingRules.shared.untilLatest(nowMs: nowMs))
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("Fast until").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            DatePicker("Ends", selection: $date, in: range, displayedComponents: [.date, .hourAndMinute])
                .datePickerStyle(.field).labelsHidden()
            Text(pick.line)
                .font(MekaType.caption)
                .foregroundStyle(pick.ok ? palette.textSecondary : palette.critical)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: pick.line)
            HStack {
                Spacer()
                Button("Start") { MekaHaptics.light(); start(Self.ms(date)) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!pick.ok)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 260)
        .onReceive(Timer.publish(every: 30, on: .main, in: .common).autoconnect()) { now = $0 }
    }
}


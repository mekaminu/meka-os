@preconcurrency import MekaKit
import SwiftUI

/// FASTING, its own sheet on the Mac (Fasting rebuild slice 3, the approved design docs/design/fasting/fasting.dc.html,
/// like the Fold's FastingPane): the plan chip, a big gold ring with a glowing tip and 4/8/12h markers, the time in
/// the middle and "Ends in … · 12:00"; the Started and Goal tiles (each click unfolds its choices); one main button,
/// Start fast or End fast, with Undo end after an end; "More options" for a fast started earlier, longer fasts (the GP
/// note above them) and a day and time; what's happening now; the seven stages, each unfolding its words and
/// evidence; this week as seven small rings; History as a list. Every word comes from core FastingScreenRules; only
/// numbers and ids go back to the core. Tracking only: no food, calorie or weight advice.
///
/// Motion: the sheet scale-fades (system); the parts appear in the stagger; the ring sweeps with the clock (a one-second
/// linear glide) and its tip breathes (2.4 s); tiles, the plan, More options and stages unfold on the expand spring;
/// Start and End give a light haptic, the rest a tick; this week's rings fill on the replan spring; an extended fast
/// reaching its goal bursts the ring once (once per fast on this Mac). Reduce Motion: no sweep, breath or burst; the
/// unfolds cross-fade.
struct FastingSheet: View {
    /// The mockup's title and ring time at the Mac's scale (the phone's 28 and 46 sp, FastingBesideRules, as the Mac's
    /// greeting is to the phone's: 26 to 34).
    static var titleFont: Font { Font.system(size: MekaTextScale.size(22), weight: .semibold) } // type: ok, beside the mockup (FastingBesideRules.TITLE_SP at the Mac's scale)
    static var timeFont: Font { Font.system(size: MekaTextScale.size(36), weight: .bold) } // type: ok, beside the mockup (FastingBesideRules.TIME_SP at the Mac's scale)

    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The fast whose "you did it" burst has already played here (shared with the old Goals card's key).
    @AppStorage("meka.fastBurst") private var burstFastId = ""
    @State private var burst: Double = 0
    @State private var planOpen = false
    /// "start" or "goal": the tile whose choices are open.
    @State private var editing: String?
    @State private var moreOpen = false
    @State private var picking = false
    @State private var stageOpen: String?

    var body: some View {
        let v = model.fasting
        let running = v?.current != nil
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            TimelineView(.periodic(from: .now, by: running ? 1 : 30)) { ctx in
                if let v, let s = model.fastingScreen(atMs: Int64(ctx.date.timeIntervalSince1970 * 1000)) {
                    ScrollView {
                        content(v, s)
                            .padding(MekaSpace.l)
                    }
                    .frame(minHeight: 420, maxHeight: 640)
                } else {
                    Text("Navilife is still starting.").font(MekaType.body).foregroundStyle(palette.textSecondary)
                        .padding(MekaSpace.l)
                }
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding([.horizontal, .bottom], MekaSpace.l)
        }
        .frame(width: 520)
        .background(palette.background)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: editing)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: moreOpen)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: planOpen)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: picking)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: stageOpen)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: v?.current?.id)
        // A fast that ends or starts folds whatever was open for the other state.
        .onChange(of: v?.current?.id) { _, _ in editing = nil; picking = false; moreOpen = false }
        .onChange(of: v?.current?.doneLine == nil ? nil : v?.current?.id, initial: true) { _, id in celebrate(id) }
    }

    @ViewBuilder
    private func content(_ v: FastingView, _ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            header(s).staggeredAppear(0)
            FastingBigRing(screen: s, palette: palette, burst: burst)
                .frame(maxWidth: .infinity)
                .staggeredAppear(1)
            if let done = v.current?.doneLine {
                Text(done).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                    .frame(maxWidth: .infinity)
                    .transition(.opacity)
            }
            if s.isFasting { tiles(v, s).staggeredAppear(2).transition(.opacity) }
            mainBlock(v, s).staggeredAppear(2)
            if let n = s.now { nowCard(n).staggeredAppear(3).transition(.opacity) }
            stages(s).staggeredAppear(4)
            week(s).staggeredAppear(5)
            history(s).staggeredAppear(5)
        }
    }

    // MARK: Header

    private func header(_ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                Text("Fasting")
                    .font(Self.titleFont).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button { MekaHaptics.tick(); planOpen.toggle() } label: {
                    Text(s.planChip).font(MekaType.captionStrong)
                        .foregroundStyle(planOpen ? palette.onAccent : palette.accent)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(planOpen ? palette.accent : palette.surfaceRaised))
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Plan \(s.planChip)")
            }
            if planOpen {
                FastChips(title: "Plan", labels: FastingRules.shared.PLAN_CHOICES.map(\.label),
                          lit: { $0 == s.planChip }, palette: palette) { i in
                    MekaHaptics.tick(); planOpen = false; model.chooseFastingPlan(i)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    // MARK: Tiles and the main button

    private func tiles(_ v: FastingView, _ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                editTile("Started", s.startedTile ?? "", key: "start")
                editTile("Goal", s.goalTile ?? "", key: "goal")
            }
            .fixedSize(horizontal: false, vertical: true)
            if editing == "start" {
                let moves = FastingRules.shared.MOVE_START_CHOICES.map(\.int32Value)
                FastChips(title: "Move the start", labels: moves.map { FastingRules.shared.moveLabel(min: $0) },
                          lit: { _ in false }, palette: palette) { i in
                    MekaHaptics.tick(); model.moveFastStart(moves[i])
                }
                .transition(.opacity)
            }
            if editing == "goal", let cur = v.current {
                if cur.extended {
                    let choices = FastingRules.shared.EXTENDED_CHOICES
                    FastChips(title: "Goal", labels: choices.map(\.label), lit: { l in choices.first { $0.label == l }?.hours == cur.targetHours },
                              palette: palette) { i in
                        MekaHaptics.tick(); model.setFastTarget(choices[i].hours)
                    }
                    .transition(.opacity)
                } else {
                    let hours = FastingRules.shared.TARGET_CHOICES.map(\.int32Value)
                    FastChips(title: "Goal", labels: hours.map { "\($0) h" }, lit: { $0 == "\(cur.targetHours) h" },
                              palette: palette) { i in
                        MekaHaptics.tick(); model.setFastTarget(hours[i])
                    }
                    .transition(.opacity)
                }
            }
        }
    }

    private func editTile(_ label: String, _ value: String, key: String) -> some View {
        Button {
            MekaHaptics.tick()
            editing = editing == key ? nil : key
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack {
                    Text(label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    Image(systemName: "pencil").font(MekaType.caption).foregroundStyle(palette.accent)
                }
                Text(value).font(MekaType.metaStrong).monospacedDigit().foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(editing == key ? palette.surfaceRaised : palette.surface))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel("\(label) \(value). Change")
    }

    private func mainBlock(_ v: FastingView, _ s: FastingScreen) -> some View {
        VStack(spacing: MekaSpace.xs) {
            Button {
                MekaHaptics.light()
                if s.isFasting { model.endFast() } else { model.startFast(minutesAgo: 0) }
            } label: {
                Text(s.mainLabel).font(MekaType.metaStrong)
                    .foregroundStyle(s.isFasting ? palette.accent : palette.onAccent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, MekaSpace.m)
                    .background(Capsule().fill(s.isFasting ? palette.surface : palette.accent))
                    .overlay(Capsule().stroke(palette.accent, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityIdentifier("fasting-main")
            if let id = s.undoEndId {
                Button(FastingScreenRules.shared.UNDO_END) { MekaHaptics.tick(); model.resumeFast(id) }
                    .buttonStyle(MekaPressStyle()).font(MekaType.captionStrong).foregroundStyle(palette.accent)
                    .transition(.opacity)
            }
            HStack(spacing: MekaSpace.xs) {
                Text(s.isFasting ? "Started by mistake?" : FastingScreenRules.shared.MORE_OPTIONS_PROMPT)
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Button(moreOpen ? "Close" : FastingScreenRules.shared.MORE_OPTIONS) { MekaHaptics.tick(); moreOpen.toggle() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.captionStrong).foregroundStyle(palette.accent)
            }
            if moreOpen { moreOptions(v, s).transition(.opacity) }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func moreOptions(_ v: FastingView, _ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if s.isFasting {
                Button("Discard this fast", role: .destructive) { MekaHaptics.light(); moreOpen = false; model.discardFast() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.captionStrong).foregroundStyle(palette.critical)
            } else {
                let ago = FastingRules.shared.STARTED_AGO_CHOICES.map(\.int32Value).filter { $0 > 0 }
                FastChips(title: "Started earlier", labels: ago.map { FastingRules.shared.startedAgoLabel(min: $0) },
                          lit: { _ in false }, palette: palette) { i in
                    model.startFast(minutesAgo: ago[i])
                }
                // The GP note comes before any longer fast can be started.
                Text(FastingScreenRules.shared.GP_NOTE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                let ext = FastingRules.shared.EXTENDED_CHOICES
                let until = v.untilChoices
                FastChips(title: "Longer fast", labels: ext.map(\.label) + until.map(\.label), lit: { _ in false }, palette: palette) { i in
                    if i < ext.count { model.startExtendedFast(hours: ext[i].hours) } else { model.startFastUntil(until[i - ext.count].untilMs) }
                }
                Button(picking ? "Close" : "Pick a day and time") { MekaHaptics.tick(); picking.toggle() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.captionStrong).foregroundStyle(palette.accent)
                    .popover(isPresented: $picking, arrowEdge: .bottom) {
                        FastUntilPicker(defaultMs: until.first?.untilMs, palette: palette) { ms in
                            picking = false
                            model.startFastUntil(ms)
                        }
                    }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
    }

    // MARK: What's happening now, the stages

    private func nowCard(_ n: FastingNowCard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(FastingScreenRules.shared.NOW_TITLE.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.accent)
            Text(n.stage.name).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(n.stage.body).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: MekaSpace.xs) {
                EvidenceBadge(evidence: n.stage.evidence, tone: n.tone, palette: palette)
                if let next = n.next {
                    Text(next).font(MekaType.caption).monospacedDigit().foregroundStyle(palette.textTertiary)
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.accent.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.l).stroke(palette.accent.opacity(0.3), lineWidth: 1))
    }

    private func stages(_ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(FastingScreenRules.shared.STAGES_TITLE, palette)
            ForEach(s.stages, id: \.stage.id) { row in
                stageRow(row, fasting: s.isFasting)
            }
            Text(FastingScreenRules.shared.STAGES_NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, MekaSpace.xs)
    }

    private func stageRow(_ row: FastingStageRow, fasting: Bool) -> some View {
        let open = stageOpen == row.stage.id
        let dot: Color = row.here ? palette.accent.mix(with: .white, by: 0.3) : (row.reached ? palette.accent : palette.hairline)
        return Button {
            MekaHaptics.tick()
            stageOpen = open ? nil : row.stage.id
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(spacing: MekaSpace.m) {
                    Circle().fill(dot).frame(width: MekaSpace.xs, height: MekaSpace.xs)
                    Text(row.stage.name).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                    Spacer(minLength: MekaSpace.xs)
                    Text(row.stage.whenLabel).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
                if open {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(row.stage.body).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        EvidenceBadge(evidence: row.stage.evidence, tone: row.tone, palette: palette)
                    }
                    .padding(.leading, MekaSpace.l)
                    .transition(.opacity)
                }
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(row.here ? palette.accent.opacity(0.1) : palette.surface))
            .opacity(fasting && !row.reached ? 0.7 : 1)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel("\(row.stage.name), \(row.stage.whenLabel)\(row.here ? ", now" : "")")
        .accessibilityHint(open ? row.stage.body : "Shows what happens")
    }

    // MARK: This week, History

    private func week(_ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                SectionLabel("This week", palette)
                Spacer()
                if let line = s.weekLine {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            if s.weekShown {
                HStack(spacing: MekaSpace.xxs) {
                    ForEach(s.week, id: \.day) { d in
                        FastWeekRing(ring: d, palette: palette).frame(maxWidth: .infinity)
                    }
                }
            } else {
                Text(FastingScreenRules.shared.WEEK_EMPTY).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(.top, MekaSpace.xs)
    }

    private func history(_ s: FastingScreen) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("History", palette)
            if s.history.isEmpty {
                Text(FastingScreenRules.shared.HISTORY_EMPTY).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
            ForEach(s.history, id: \.id) { h in
                // One line as in the mockup: span · length · goal (beside the mockup, 2026-10-30, FastingBesideRules).
                HStack(spacing: MekaSpace.s) {
                    Circle().fill(h.onGoal ? palette.accent : palette.accent.opacity(0.4)).frame(width: 9, height: 9)
                    Text(h.span).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(1)
                    Spacer(minLength: MekaSpace.xs)
                    Text(h.length).font(MekaType.metaStrong).monospacedDigit().foregroundStyle(palette.textPrimary).lineLimit(1)
                    Text(FastingBesideRules.shared.goalOnRow(goal: h.goal)).font(MekaType.caption)
                        .foregroundStyle(h.onGoal ? palette.accent : palette.textTertiary).lineLimit(1)
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(FastingBesideRules.shared.spoken(row: h))
            }
        }
        .padding(.top, MekaSpace.xs)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: s.history.map(\.id))
    }

    /// The "you did it" moment, once per extended fast on this Mac.
    private func celebrate(_ id: String?) {
        guard let id, id != burstFastId else { return }
        burstFastId = id
        guard !reduceMotion else { return }
        MekaHaptics.light()
        burst = 0
        withAnimation(.easeOut(duration: 0.9)) { burst = 1 } completion: { burst = 0 }
    }
}

/// The big ring: a gold sweep over a dark track, markers at 4, 8 and 12 hours (whole days on a longer fast), a glowing
/// tip at the sweep's end that breathes while a fast runs, and the time in the middle. 280 pt across.
private struct FastingBigRing: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let screen: FastingScreen
    let palette: MekaPalette
    var burst: Double = 0
    @State private var breath = false

    private let side: CGFloat = 280
    private let stroke: CGFloat = 16

    var body: some View {
        let s = screen
        let r = side / 2 - stroke / 2 - 8
        let light = palette.accent.mix(with: .white, by: 0.4)
        ZStack {
            Circle().stroke(palette.surfaceRaised, lineWidth: stroke).frame(width: r * 2, height: r * 2)
            if s.isFasting && s.fraction > 0 {
                Circle().trim(from: 0, to: CGFloat(s.fraction))
                    .stroke(LinearGradient(colors: [light, palette.accent], startPoint: .topLeading, endPoint: .bottomTrailing),
                            style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: r * 2, height: r * 2)
                    .animation(reduceMotion ? nil : .linear(duration: 1), value: s.fraction)
            }
            ForEach(s.marks, id: \.label) { m in
                ZStack {
                    Circle().fill(m.passed ? palette.onAccent : palette.background).frame(width: 12, height: 12)
                    Circle().fill(m.passed ? palette.onAccent : palette.textTertiary.opacity(0.6)).frame(width: 8, height: 8)
                }
                .offset(at(Double(m.fraction), r))
            }
            if s.showTip {
                ZStack {
                    Circle().fill(RadialGradient(colors: [light.opacity(0.55 * (reduceMotion ? 0.8 : (breath ? 1 : 0.55))), .clear],
                                                 center: .center, startRadius: 0, endRadius: 22))
                        .frame(width: 44, height: 44)
                    Circle().fill(light.mix(with: .white, by: 0.5)).frame(width: 22, height: 22)
                }
                .offset(at(Double(s.fraction), r))
                .animation(reduceMotion ? nil : .linear(duration: 1), value: s.fraction)
            }
            if burst > 0 {
                Circle()
                    .stroke(palette.accent.opacity(0.6 * (1 - burst)), lineWidth: stroke * (1 - burst * 0.7))
                    .frame(width: (r + burst * 24) * 2, height: (r + burst * 24) * 2)
            }
            VStack(spacing: MekaSpace.xxs) {
                Text(s.topLine.uppercased())
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.textSecondary)
                Text(s.bigTime).font(FastingSheet.timeFont).monospacedDigit().foregroundStyle(palette.textPrimary)
                    .contentTransition(.numericText())
                if !s.isFasting {
                    Text(FastingRules.shared.IDLE_RING_CAPTION).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                Text(s.subLine).font(MekaType.caption).monospacedDigit().foregroundStyle(palette.accent)
                    .multilineTextAlignment(.center)
            }
            .frame(width: r * 1.5)
        }
        .frame(width: side, height: side)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(s.topLine). \(s.bigTime). \(s.subLine)")
        .accessibilityIdentifier("fasting-ring")
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) { breath = true }
        }
    }

    /// Where on the ring [fraction] of the way round from the top sits, from the centre.
    private func at(_ fraction: Double, _ r: CGFloat) -> CGSize {
        let a = fraction * 2 * .pi - .pi / 2
        return CGSize(width: cos(a) * r, height: sin(a) * r)
    }
}

/// One of this week's seven small rings, filling on the replan spring when it appears or changes.
private struct FastWeekRing: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let ring: FastingWeekRing
    let palette: MekaPalette
    @State private var shown: Double = 0

    var body: some View {
        let color: Color = ring.onGoal ? palette.accent
            : (ring.isToday ? palette.accent.mix(with: .white, by: 0.3) : palette.accent.opacity(0.5))
        VStack(spacing: MekaSpace.xxs) {
            ZStack {
                Circle().stroke(palette.surfaceRaised, lineWidth: 5)
                if shown > 0 {
                    Circle().trim(from: 0, to: shown)
                        .stroke(color, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
            }
            .frame(width: 36, height: 36)
            Text(ring.day).font(ring.isToday ? MekaType.captionStrong : MekaType.caption)
                .foregroundStyle(ring.isToday ? palette.accent : palette.textTertiary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ring.spoken)
        .onChange(of: ring.fill, initial: true) { _, fill in
            if reduceMotion { shown = Double(fill) } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = Double(fill) } }
        }
    }
}

/// The evidence badge: gold for strong, plain for moderate, amber for early.
private struct EvidenceBadge: View {
    let evidence: String
    let tone: EvidenceTone
    let palette: MekaPalette

    var body: some View {
        let color: Color = tone == .strong ? palette.accent : (tone == .early ? palette.calendar3 : palette.textSecondary)
        Text(evidence).font(MekaType.micro).foregroundStyle(color).lineLimit(1)
            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
            .background(Capsule().fill(color.opacity(0.14)))
    }
}

/// A titled row of choice chips, wrapping onto a second row past five; a lit chip fills gold.
private struct FastChips: View {
    let title: String
    let labels: [String]
    let lit: (String) -> Bool
    let palette: MekaPalette
    let pick: (Int) -> Void

    var body: some View {
        let rows = stride(from: 0, to: labels.count, by: 5).map { Array($0..<min($0 + 5, labels.count)) }
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(title).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            ForEach(rows, id: \.self) { row in
                HStack(spacing: MekaSpace.xs) {
                    ForEach(row, id: \.self) { i in
                        let on = lit(labels[i])
                        Button { pick(i) } label: {
                            Text(labels[i]).font(MekaType.captionStrong).monospacedDigit()
                                .foregroundStyle(on ? palette.onAccent : palette.accent)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(Capsule().fill(on ? palette.accent : palette.surfaceRaised))
                        }
                        .buttonStyle(MekaPressStyle())
                    }
                }
            }
        }
    }
}

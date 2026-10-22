import Combine
@preconcurrency import MekaKit
import SwiftUI

/// FASTING on the Mac, Goals' small tile (Fasting rebuild slice 3, like the Fold's FastingTile): a small ring,
/// "Fasting · 13:30" or "Not fasting", when it ends or the next one starts, and the plan. One click opens the Fasting
/// sheet ([FastingSheet], the approved design), so the habits sit right under it. Every word comes from core
/// FastingScreenRules. The ring sweeps with the clock (a one-second linear glide); Reduce Motion: it steps each tick.
struct FastingSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var hovering = false

    var body: some View {
        let running = model.fasting?.current != nil
        let planLine = model.fasting?.plan.line ?? ""
        TimelineView(.periodic(from: .now, by: running ? 1 : 30)) { ctx in
            if let s = model.fastingScreen(atMs: Int64(ctx.date.timeIntervalSince1970 * 1000)) {
                Button { model.showFasting = true } label: {
                    HStack(spacing: MekaSpace.m) {
                        ZStack {
                            Circle().stroke(palette.surfaceRaised, lineWidth: 5)
                            if s.isFasting {
                                Circle().trim(from: 0, to: CGFloat(s.fraction))
                                    .stroke(palette.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                                    .rotationEffect(.degrees(-90))
                                    .animation(reduceMotion ? nil : .linear(duration: 1), value: s.fraction)
                            }
                        }
                        .frame(width: MekaSpace.xxl, height: MekaSpace.xxl)
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(s.tileTitle).font(MekaType.itemTitle).monospacedDigit().foregroundStyle(palette.textPrimary)
                            Text(s.subLine).font(MekaType.itemMeta).monospacedDigit()
                                .foregroundStyle(s.isFasting ? palette.accent : palette.textSecondary)
                            Text(planLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        }
                        Spacer(minLength: MekaSpace.xs)
                        Image(systemName: "chevron.right").foregroundStyle(palette.accent)
                    }
                    .padding(MekaSpace.m)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(hovering ? palette.surfaceRaised : palette.surface))
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .onHover { h in withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h } }
                .accessibilityLabel("\(s.tileTitle). \(s.subLine). Opens Fasting")
                .accessibilityIdentifier("fasting-tile")
            }
        }
    }
}

/// A custom "until" end on the Mac: any day and time from 12 hours to ten days on, checked by the core as it changes
/// ("Goal 4 d 18 h · starts now"). Start is enabled only when it's valid.
struct FastUntilPicker: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
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
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
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


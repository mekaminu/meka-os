@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Countdowns on the Mac (Countdowns, slice 1), like the Fold's CountdownsPane and the approved design
/// (`docs/design/countdowns/Countdowns.dc.html`): the days, or sleeps for the boys, to a holiday, a birthday, an
/// anniversary or Christmas. The one on Today (else the soonest) sits large with a ring; the rest are two-column cards.
/// + Add opens a field ("Holiday 13 Feb", "… every year"); birthdays in Gifts, trips and Christmas appear by
/// themselves. Click one to show it big (name, a huge number, twinkling dots) with Show on Today, Remove or Hide, and
/// Done. Only Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); the hero, switch and cards stagger in; the ring sweeps from the top to its
/// fill on the replan spring; numbers cross-fade when Days · Sleeps flips; the field unfolds on the expand spring; the
/// big view scale-fades in and its dots twinkle (2.4 s, staggered). Reduce Motion: cross-fades, the ring shown filled,
/// the dots still.
struct CountdownsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var adding = false
    @State private var text = ""
    @State private var said: CoreModel.GiftSaid?
    /// The countdown shown big, if any.
    @State private var big: String?

    var body: some View {
        let v = model.countdowns
        let hero = v?.hero
        let rows = v?.rows ?? []
        let all = (hero.map { [$0] } ?? []) + rows
        ZStack {
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                HStack {
                    Text(CountdownRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
                    Spacer()
                    Button("+ Add") {
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle() }
                        said = nil
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.metaStrong)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("Add a countdown")
                }
                if adding {
                    TextField(CountdownRules.shared.ADD_HINT, text: $text)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel(CountdownRules.shared.ADD_DESCRIPTION)
                        .onSubmit {
                            let typed = text
                            text = ""
                            Task { said = await model.addCountdown(typed) }
                        }
                        .transition(.opacity)
                    if let said {
                        Text(said.line).font(MekaType.caption)
                            .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                UnitSwitch(sleeps: v?.sleeps ?? false, palette: palette) { model.setCountdownSleeps($0) }
                    .staggeredAppear(1)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        if let hero {
                            HeroCard(row: hero, onToday: v?.heroOnToday ?? false, palette: palette) { big = hero.key }
                                .staggeredAppear(2)
                        }
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: MekaSpace.xs), GridItem(.flexible(), spacing: MekaSpace.xs)], spacing: MekaSpace.xs) {
                            ForEach(Array(rows.enumerated()), id: \.element.key) { i, r in
                                SmallCard(row: r, palette: palette) { big = r.key }
                                    .transition(.opacity)
                                    .staggeredAppear(3 + i / 2)
                            }
                        }
                        if let n = v?.hiddenCount, n > 0 {
                            Button("Show hidden · \(n)") { model.showHiddenCountdowns() }
                                .buttonStyle(MekaPressStyle())
                                .font(MekaType.itemMeta)
                                .foregroundStyle(palette.accent)
                        }
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: all.map(\.key))
                }
                .frame(maxHeight: 420)
                Text(CountdownRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
            }
            .padding(MekaSpace.l)
            if let key = big, let r = all.first(where: { $0.key == key }) {
                let onToday = (v?.heroOnToday ?? false) && hero?.key == r.key
                BigCountdown(
                    row: r, onToday: onToday, palette: palette,
                    onShow: {
                        model.showCountdownOnToday(onToday ? "" : r.key)
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { big = nil }
                    },
                    onRemove: {
                        model.removeCountdown(r.key)
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { big = nil }
                    },
                    onDone: { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { big = nil } }
                )
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: big)
        .frame(width: 520)
        .background(palette.surface)
    }
}

private struct UnitSwitch: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let sleeps: Bool
    let palette: MekaPalette
    let onChange: (Bool) -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xxs) {
            ForEach([false, true], id: \.self) { s in
                let on = s == sleeps
                Button(s ? "Sleeps" : "Days") { if !on { onChange(s) } }
                    .buttonStyle(MekaPressStyle())
                    .font(on ? MekaType.captionStrong : MekaType.caption)
                    .foregroundStyle(on ? palette.textPrimary : palette.textSecondary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(on ? palette.surfaceRaised : Color.clear, in: Capsule())
                    .accessibilityLabel("Count in \(s ? "sleeps" : "days")")
                    .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(MekaSpace.xxs)
        .background(palette.background, in: Capsule())
        .animation(MekaMotion.appear(reduced: reduceMotion), value: sleeps)
    }
}

/// The ring: a track and the accent arc from the top, sweeping to `fill` (shown at once with Reduce Motion).
private struct CountdownRing: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let fill: Double
    let palette: MekaPalette
    @State private var shown = 0.0

    var body: some View {
        ZStack {
            Circle().stroke(palette.hairline, lineWidth: 7)
            Circle().trim(from: 0, to: shown)
                .stroke(palette.accent, style: StrokeStyle(lineWidth: 7, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .onAppear {
            if reduceMotion { shown = fill } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = fill } }
        }
        .onChange(of: fill) { _, f in withAnimation(MekaMotion.replan(reduced: reduceMotion)) { shown = f } }
    }
}

private struct HeroCard: View {
    let row: CountdownRow
    let onToday: Bool
    let palette: MekaPalette
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: MekaSpace.m) {
                ZStack {
                    CountdownRing(fill: row.fill, palette: palette)
                    VStack(spacing: 0) {
                        Text(row.number).font(row.number.count > 3 ? MekaType.greetingCompact : MekaType.greeting)
                            .foregroundStyle(palette.textPrimary).contentTransition(.numericText())
                        if !row.unit.isEmpty {
                            Text(row.unit).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                        }
                    }
                }
                .frame(width: 96, height: 96)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(onToday ? "NEXT UP · ON TODAY" : "NEXT UP")
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.accent)
                    Text(row.name).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                    Text(row.dateLine).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    if let extra = row.extra {
                        Text(extra).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(MekaSpace.m)
            .background(palette.accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(palette.accent.opacity(0.18), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(row.spoken + (onToday ? ". On Today" : ""))
    }
}

/// The card's SF Symbol for its icon.
private func countdownSymbol(_ icon: CountdownIcon) -> String {
    switch icon {
    case .gift: "gift"
    case .cake: "birthday.cake"
    case .plane: "airplane"
    case .heart: "heart"
    default: "star"
    }
}

private struct SmallCard: View {
    let row: CountdownRow
    let palette: MekaPalette
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack {
                    Image(systemName: countdownSymbol(row.icon))
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .frame(width: 26, height: 26)
                        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
                    Spacer()
                    Text(row.dateLine).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
                HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xs) {
                    Text(row.number).font(row.number.count > 3 ? MekaType.upNextTitle : MekaType.greetingCompact)
                        .foregroundStyle(palette.textPrimary).contentTransition(.numericText())
                    if !row.unit.isEmpty {
                        Text(row.unit).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                    }
                }
                Text(row.name).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary).lineLimit(2)
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(row.spoken)
    }
}

/// Where the twinkling dots sit, as fractions of the view (the design's eight).
private let countdownStars: [(Double, Double)] = [(0.12, 0.14), (0.78, 0.09), (0.30, 0.30), (0.88, 0.36), (0.08, 0.62), (0.70, 0.70), (0.22, 0.86), (0.90, 0.88)]

private struct BigCountdown: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let row: CountdownRow
    let onToday: Bool
    let palette: MekaPalette
    let onShow: () -> Void
    let onRemove: () -> Void
    let onDone: () -> Void

    var body: some View {
        GeometryReader { g in
            ZStack {
                palette.background
                TimelineView(.animation(paused: reduceMotion)) { t in
                    let now = t.date.timeIntervalSinceReferenceDate
                    ForEach(0..<countdownStars.count, id: \.self) { i in
                        let star = countdownStars[i]
                        // 2.4 s a twinkle, each 0.3 s behind the one before; still at 0.6 with Reduce Motion.
                        let phase = (now / 2.4 + Double(i) * 0.125).truncatingRemainder(dividingBy: 1)
                        let a = reduceMotion ? 0.6 : 0.25 + 0.75 * (0.5 - 0.5 * cos(phase * 2 * .pi))
                        RoundedRectangle(cornerRadius: 2).fill(palette.accent)
                            .frame(width: 4, height: 4)
                            .opacity(a)
                            .position(x: g.size.width * star.0, y: g.size.height * star.1)
                    }
                }
                VStack(spacing: MekaSpace.xs) {
                    Text(row.name.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.accent)
                    Text(row.number).font(row.number.count > 3 ? MekaType.clock : .system(size: 120, weight: .bold)) // type: ok, the design's huge number
                        .foregroundStyle(palette.textPrimary)
                    if !row.unit.isEmpty {
                        Text("\(row.unit) to go").font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                    }
                    Text(row.dateLine + (row.extra.map { " · \($0)" } ?? "")).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    HStack(spacing: MekaSpace.xs) {
                        Button(onToday ? "Take off Today" : "Show on Today", action: onShow)
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemMeta)
                            .foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surface, in: Capsule())
                        Button("Done", action: onDone)
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.metaStrong)
                            .foregroundStyle(palette.onAccent)
                            .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                            .background(palette.accent, in: Capsule())
                            .keyboardShortcut(.cancelAction)
                    }
                    .padding(.top, MekaSpace.m)
                    Button(row.canRemove ? "Remove" : "Hide", action: onRemove)
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityLabel((row.canRemove ? "Remove " : "Hide ") + row.name)
                }
                .padding(MekaSpace.l)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(row.spoken)
            }
        }
    }
}

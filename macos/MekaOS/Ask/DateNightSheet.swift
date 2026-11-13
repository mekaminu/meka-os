@preconcurrency import MekaKit
import SwiftUI

/// Ask → Date night on the Mac in the page shape (every page under Ask in one shape, slice 11; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's DateNightPane: the title with its ⓘ, one gold + Add (The
/// evening: the weekday, the start time and which of the next two weeks it starts, as chips in its form), three numbers
/// (how soon the next one is, whether it's booked, the time), then Coming up (the next four nights) and The evening (its
/// row opens the same chooser with Turn off). A night's row opens in place with ‹ Date night back: the gold Booked (or
/// Not booked yet) and Skip this one / Keep it. The planner and Gym bookings keep each evening clear; Needs you asks a
/// week before. Only Ints, an Int64 and a Bool cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; the chooser and the ⓘ unfold on the expand spring; a
/// row opens and closes with a cross-fade and slide from the trailing edge; the summary and the line after an action
/// cross-fade; a chip's fill blends as it is chosen (tick haptic); Booked gives a light haptic. Reduce Motion:
/// cross-fades.
struct DateNightSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var choosing = false
    /// The night that's open, if any (its key: "night:…").
    @State private var open: String?

    var body: some View {
        let rules = DateNightPageRules.shared
        let v = model.dateNight ?? DateNightView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                nightDetail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let page = model.dateNightPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { choosing.toggle() }
                }
                .staggeredAppear(0)
                if choosing { chooser(v).transition(.opacity) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summaryShown).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if !choosing, let said = model.dateNightSaid {
                        Text(said).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            let card = page.cardAt(index: Int32(i))
                            LifePageCardView(card: card, palette: palette, onMore: {}, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                                    if key == rules.EVENING_KEY { choosing = true } else { open = key }
                                }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(DateNightRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxHeight: 420)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.on)
        .onDisappear { model.forgetDateNightSaid() }
    }

    /// An open night: its line, Booked (or Not booked yet) and Skip this one / Keep it.
    @ViewBuilder
    private func nightDetail(_ key: String, _ v: DateNightView) -> some View {
        let rules = DateNightPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let night = rules.night(view: v, key: key) {
                let day = night.day
                let label = night.label
                let booked = night.booked
                let skipped = night.skipped
                Text(label).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(night.line).font(MekaType.body)
                    .foregroundStyle(skipped ? palette.textTertiary : palette.textPrimary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: night.line)
                HStack(spacing: MekaSpace.xs) {
                    if rules.canBook(r: night) {
                        let bookLabel = rules.bookLabel(r: night)
                        Button(bookLabel) { model.markDateNightBooked(day: day, booked: !booked) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemMeta)
                            .foregroundStyle(booked ? palette.textSecondary : palette.onAccent)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(booked ? palette.surfaceRaised : palette.accent, in: Capsule())
                            .accessibilityLabel("\(bookLabel), \(label)")
                    }
                    Button(rules.skipLabel(r: night)) { model.skipDateNight(day: day, skip: !skipped) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(skipped ? palette.accent : palette.textTertiary)
                        .accessibilityLabel(skipped ? "Keep \(label)" : "Skip \(label)")
                }
                if let said = model.dateNightSaid {
                    Text(said).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
            } else {
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// + Add → The evening: a weekday chip, a start time, which of the next two weeks it starts, Turn off.
    @ViewBuilder
    private func chooser(_ v: DateNightView) -> some View {
        let rules = DateNightRules.shared
        let page = DateNightPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.EVENING).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            Text(page.WEEKDAY).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(rules.WEEKDAY_CHOICES.enumerated()), id: \.offset) { i, day in
                    let weekday = Int32(i + 1)
                    chip(day, lit: v.on && v.weekday == weekday) {
                        model.setDateNight(weekday: weekday, startMin: v.startMin, firstDay: anchor(v, weekday))
                    }
                }
            }
            Text(page.START).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(rules.START_CHOICES.map { $0.int32Value }, id: \.self) { m in
                    chip(LocalClock.companion.formatMinute(m: m), lit: rules.startLit(on: v.on, startMin: v.startMin, choice: m)) {
                        model.setDateNight(weekday: v.weekday, startMin: m, firstDay: anchor(v, v.weekday))
                    }
                }
            }
            if v.on && !v.starts.isEmpty {
                Text(page.FIRST).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                HStack(spacing: MekaSpace.xs) {
                    ForEach(v.starts, id: \.day) { s in
                        chip(s.label, lit: s.chosen) { model.setDateNight(weekday: v.weekday, startMin: v.startMin, firstDay: s.day) }
                    }
                }
                .transition(.opacity)
            }
            if v.on {
                Button(rules.OFF_LABEL) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { choosing = false }
                    model.dateNightOff()
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .transition(.opacity)
            }
        }
    }

    /// A night that stays one when only the time changes (so the fortnight isn't moved); else the weekday's next.
    private func anchor(_ v: DateNightView, _ weekday: Int32) -> Int64 {
        if v.on, v.weekday == weekday, let first = v.nights.first { return first.day }
        return DateNightRules.shared.firstNight(weekday: weekday, today: model.todayEpochDay, nextWeek: false)
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

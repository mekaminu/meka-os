@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life admin hour on the Mac in the page shape (week 5's walk-through A16; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's LifeAdminPane and the Mac's Date night: the title with its ⓘ,
/// one gold + Add (The hour: the weekday and the start time as chips in its form, with Turn off), three numbers (how
/// soon the next hour is, the jobs waiting, how many are overdue), then Gathered into it (five jobs, See all for the
/// rest) and The hour (this week's hour opens Skip this week / Keep it in place; the setting's row opens the chooser).
/// A task's row opens in place with Done. Off, it is one raised card with Pick the day. Only Ints, Strings and Bools
/// cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; the chooser and the ⓘ unfold on the expand spring; a row
/// opens and closes with a cross-fade and slide from the trailing edge; the line after an action cross-fades; a chip's
/// fill blends as it is chosen (tick haptic). Reduce Motion: cross-fades.
struct LifeAdminSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var choosing = false
    @State private var all = false
    /// The row that's open in place, if any ("week", "next" or "job:…").
    @State private var open: String?

    var body: some View {
        let rules = LifeAdminPageRules.shared
        let v = model.lifeAdmin ?? LifeAdminView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                detail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let page = model.lifeAdminPage(all: all) {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { choosing.toggle() }
                }
                .staggeredAppear(0)
                if choosing { chooser(v).transition(.opacity) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if rules.blank(view: v) && !choosing {
                        LifeEmptyCard(line: rules.EMPTY_LINE, button: rules.ADD_FIRST, palette: palette) {
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { choosing = true }
                        }
                        .transition(.opacity)
                    }
                    if !choosing, let said = model.lifeAdminSaid {
                        Text(said).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                .staggeredAppear(1)
                if page.tileCount > 0 {
                    LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            let card = page.cardAt(index: Int32(i))
                            LifePageCardView(card: card, palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { all.toggle() }
                            }, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                                    if key == rules.EVERY_KEY { choosing = true } else { open = key }
                                }
                            })
                            .staggeredAppear(3 + i)
                        }
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
        .onDisappear { model.forgetLifeAdminSaid() }
    }

    /// An open row: this week's hour with Skip this week / Keep it, or a gathered job with Done (a task) or where its
    /// renewal is kept.
    @ViewBuilder
    private func detail(_ key: String, _ v: LifeAdminView) -> some View {
        let rules = LifeAdminPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if key == rules.WEEK_KEY || key == rules.NEXT_KEY {
                Text(v.thisWeek?.whenLine ?? v.next?.whenLine ?? rules.HOUR)
                    .font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(v.skipped ? rules.SKIPPED_LINE : rules.BOOKED_LINE).font(MekaType.body)
                    .foregroundStyle(v.skipped ? palette.textTertiary : palette.textPrimary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.skipped)
                if v.thisWeek != nil {
                    let skipped = v.skipped
                    Button(rules.skipLabel(view: v)) { model.skipLifeAdmin(!skipped) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.textPrimary)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(palette.surfaceRaised, in: Capsule())
                }
                if let said = model.lifeAdminSaid {
                    Text(said).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
            } else if let job = rules.job(view: v, key: key) {
                Text(job.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                if let line = job.lineShown {
                    Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                }
                if let taskId = rules.taskId(job: job) {
                    let title = job.title
                    Button(rules.DONE) {
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                        model.complete(taskId)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("\(rules.DONE), \(title)")
                } else {
                    Text(rules.RENEWAL_IN_LISTS).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// + Add → The hour: a weekday chip, a start time, Turn off.
    @ViewBuilder
    private func chooser(_ v: LifeAdminView) -> some View {
        let rules = LifeAdminRules.shared
        let page = LifeAdminPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.HOUR).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            Text(page.WEEKDAY).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(rules.WEEKDAY_CHOICES.enumerated()), id: \.offset) { i, day in
                    let weekday = Int32(i + 1)
                    let startMin = v.startMin
                    chip(day, lit: v.on && v.weekday == weekday) { model.setLifeAdmin(weekday: weekday, startMin: startMin) }
                }
            }
            Text(page.START).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(rules.START_CHOICES.map { $0.int32Value }, id: \.self) { m in
                    let weekday = v.weekday
                    chip(LocalClock.companion.formatMinute(m: m), lit: rules.startLit(on: v.on, startMin: v.startMin, choice: m)) {
                        model.setLifeAdmin(weekday: weekday, startMin: m)
                    }
                }
            }
            if v.on {
                Button(rules.OFF_LABEL) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { choosing = false }
                    model.lifeAdminOff()
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textSecondary)
                .transition(.opacity)
            }
        }
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

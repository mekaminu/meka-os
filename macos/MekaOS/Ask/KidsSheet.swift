@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Kids on the Mac (Kids' admin, slices 1–3), like the Fold's KidsPane: a card per child from About you with
/// their sizes (shoes, football boots, tops, trousers, coat, school jumper), when they were last checked (in the accent
/// three months on) and All still fit. Type a size and press Return to keep it; clear it to remove it, with Undo.
/// Slice 2: CLUB FEES under the sizes (each a bill on the radar, the line in the accent within 3 days of due) with Paid
/// and Remove (Undo), and a field that reads "Football £35 monthly from 1 Nov". Slice 3: THIS WEEK over the cards — what
/// they need in the next 7 days from School, club sessions and fees. Slice 5: CHORES AND POCKET MONEY under the fees —
/// the weekly amount, each chore with a dot per day of this week (click today's, or an earlier day, to tick it), the
/// week's sum, Paid £8 and Remove with Undo. Only Strings and day numbers cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and each card's lines cross-fade; cards stagger in; the checked
/// line blends to the accent when due; a save gives a light haptic, a refusal a tick. Reduce Motion: cross-fades.
struct KidsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Per "child|size": what the field holds while it's being edited.
    @State private var texts: [String: String] = [:]
    /// Per child: what the last save or All still fit said.
    @State private var said: [String: CoreModel.KidSaid] = [:]
    /// Slice 2, per child: the fee field's text and what the last fee action said.
    @State private var feeTexts: [String: String] = [:]
    @State private var feeSaid: [String: CoreModel.KidSaid] = [:]
    /// Slice 5, per child: the chore and pocket money fields and what the last chore/money action said.
    @State private var choreTexts: [String: String] = [:]
    @State private var pocketTexts: [String: String] = [:]
    @State private var moneySaid: [String: CoreModel.KidSaid] = [:]

    var body: some View {
        let v = model.kids ?? KidsView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(KidsRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    if let empty = v.empty {
                        Button("About you…") { dismiss(); model.showAboutYou = true }
                            .accessibilityLabel(empty)
                    }
                    if let w = v.week {
                        week(w).staggeredAppear(2)
                    }
                    ForEach(Array(v.kids.enumerated()), id: \.element.name) { i, kid in
                        card(kid).staggeredAppear(2 + i)
                    }
                }
            }
            .frame(maxHeight: 520)
            Text(KidsRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    /// Slice 3: THIS WEEK — what they need in the next 7 days, one block per day; fees and soon school things in the accent.
    private func week(_ w: KidWeek) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(KidsWeekRules.shared.HEAD).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Text(w.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: w.line)
            ForEach(w.days, id: \.day) { d in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(d.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    ForEach(Array(d.items.enumerated()), id: \.offset) { _, thing in
                        Text(thing.text).font(MekaType.body)
                            .foregroundStyle(thing.lit ? palette.accent : palette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityLabel(thing.spoken)
                    }
                }
                .transition(.opacity)
            }
            if !w.isEmpty {
                Text(KidsWeekRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: w.line)
    }

    @ViewBuilder
    private func card(_ kid: KidCard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(kid.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(kid.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(kid.spoken)
                Spacer()
                if kid.sizesLine != nil {
                    Button("All still fit") {
                        Task {
                            guard let line = await model.kidSizesFit(child: kid.name) else { return }
                            said[kid.name] = CoreModel.KidSaid(line: line, refused: false, undo: nil)
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                }
            }
            Text(kid.checkedLine).font(MekaType.caption)
                .foregroundStyle(kid.due ? palette.accent : palette.textTertiary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: kid.checkedLine)
            ForEach(kid.sizes, id: \.size) { row in
                sizeRow(kid: kid.name, row: row)
            }
            if let line = said[kid.name] {
                HStack(spacing: MekaSpace.xs) {
                    Text(line.line).font(MekaType.caption)
                        .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let undo = line.undo, let size = undoSize[kid.name] {
                        Button("Undo") {
                            Task {
                                _ = await model.setKidSize(child: kid.name, size: size, text: undo)
                                said[kid.name] = nil
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                    }
                }
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
            fees(kid)
            money(kid)
        }
        .padding(MekaSpace.m)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Which size the last clear was, per child, so Undo writes it back there.
    @State private var undoSize: [String: String] = [:]

    private func sizeRow(kid: String, row: KidSizeRow) -> some View {
        let key = "\(kid)|\(row.size)"
        return HStack(spacing: MekaSpace.xs) {
            Text(row.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .frame(width: 130, alignment: .leading)
            TextField(row.hint, text: Binding(get: { texts[key] ?? row.value ?? "" }, set: { texts[key] = $0 }))
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("\(kid)'s \(row.label.lowercased()) size, for example \(row.hint)")
                .onSubmit {
                    let typed = texts[key] ?? row.value ?? ""
                    let size = row.size
                    Task {
                        guard let r = await model.setKidSize(child: kid, size: size, text: typed) else { return }
                        said[kid] = r
                        if r.undo != nil { undoSize[kid] = size }
                        if !r.refused { texts[key] = nil } // the kept value shows again
                    }
                }
        }
    }

    /// Slice 2: the child's club fees, Paid / Remove with Undo, and the field that adds one.
    @ViewBuilder
    private func fees(_ kid: KidCard) -> some View {
        let name = kid.name
        Text(KidsRules.shared.FEE_HEAD).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
        ForEach(kid.fees, id: \.id) { fee in
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(fee.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(fee.line).font(MekaType.caption)
                        .foregroundStyle(fee.due ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: fee.line)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(fee.spoken)
                Spacer()
                let id = fee.id
                Button("Paid") {
                    Task { if let r = await model.kidFeePaid(id: id) { feeSaid[name] = r } }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                Button("Remove") {
                    Task { if let r = await model.removeKidFee(id: id) { feeSaid[name] = r } }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        TextField(KidsRules.shared.FEE_HINT, text: Binding(get: { feeTexts[name] ?? "" }, set: { feeTexts[name] = $0 }))
            .textFieldStyle(.roundedBorder)
            .onSubmit {
                let typed = feeTexts[name] ?? ""
                Task {
                    guard let r = await model.addKidFee(child: name, text: typed) else { return }
                    feeSaid[name] = r
                    if !r.refused { feeTexts[name] = nil }
                }
            }
        if let line = feeSaid[name] {
            HStack(spacing: MekaSpace.xs) {
                Text(line.line).font(MekaType.caption)
                    .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let id = line.feeUndo {
                    Button("Undo") {
                        Task {
                            if await model.undoKidFee(id: id) { feeSaid[name] = nil }
                            else { feeSaid[name] = CoreModel.KidSaid(line: KidsRules.shared.FEE_NOTHING_TO_UNDO, refused: true, undo: nil) }
                        }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
        }
    }

    /// Slice 5: CHORES AND POCKET MONEY — the weekly amount, each chore with its dots, the field that adds one, the
    /// week's sum, Paid and the last action's line with Undo.
    @ViewBuilder
    private func money(_ kid: KidCard) -> some View {
        let name = kid.name
        let m = kid.money
        Text(KidChoreRules.shared.HEAD).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
        HStack(spacing: MekaSpace.xs) {
            Text(KidChoreRules.shared.POCKET_LABEL).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .frame(width: 130, alignment: .leading)
            TextField(KidChoreRules.shared.POCKET_HINT,
                      text: Binding(get: { pocketTexts[name] ?? m.pocketValue ?? "" }, set: { pocketTexts[name] = $0 }))
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = pocketTexts[name] ?? m.pocketValue ?? ""
                    Task {
                        guard let r = await model.setKidPocketMoney(child: name, text: typed) else { return }
                        moneySaid[name] = r
                        if !r.refused { pocketTexts[name] = nil }
                    }
                }
        }
        ForEach(m.chores, id: \.id) { chore in
            choreRow(name: name, chore: chore)
        }
        TextField(KidChoreRules.shared.CHORE_HINT, text: Binding(get: { choreTexts[name] ?? "" }, set: { choreTexts[name] = $0 }))
            .textFieldStyle(.roundedBorder)
            .onSubmit {
                let typed = choreTexts[name] ?? ""
                Task {
                    guard let r = await model.addKidChore(child: name, text: typed) else { return }
                    moneySaid[name] = r
                    if !r.refused { choreTexts[name] = nil }
                }
            }
        if let week = m.weekLine {
            Text(week).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: week)
        }
        if m.paidLine != nil || m.payLabel != nil {
            HStack(spacing: MekaSpace.xs) {
                Text(m.paidLine ?? "").font(MekaType.caption)
                    .foregroundStyle(m.lit ? palette.accent : palette.textTertiary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: m.paidLine)
                Spacer()
                if let label = m.payLabel {
                    Button(label) {
                        Task { if let r = await model.kidPocketMoneyPaid(child: name) { moneySaid[name] = r } }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    .accessibilityLabel("\(label) to \(name) for this week")
                }
            }
        }
        if let line = moneySaid[name] {
            HStack(spacing: MekaSpace.xs) {
                Text(line.line).font(MekaType.caption)
                    .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let token = line.moneyUndo {
                    Button("Undo") {
                        Task {
                            if await model.undoKidMoney(token: token) { moneySaid[name] = nil }
                            else { moneySaid[name] = CoreModel.KidSaid(line: KidChoreRules.shared.NOTHING_TO_UNDO, refused: true, undo: nil) }
                        }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
        }
        if m.shown {
            Text(KidChoreRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// One chore: title and line, Remove, and seven day dots (accent when ticked, ringed today, dimmed days to come).
    private func choreRow(name: String, chore: KidChoreRow) -> some View {
        let id = chore.id
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(chore.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(chore.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: chore.line)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(chore.spoken)
                Spacer()
                Button("Remove") {
                    Task { if let r = await model.removeKidChore(id: id) { moneySaid[name] = r } }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(chore.days, id: \.day) { d in
                    let day = d.day
                    Button {
                        Task { if let r = await model.tickKidChore(id: id, day: day) { moneySaid[name] = r } }
                    } label: {
                        Text(d.letter).font(MekaType.caption)
                            .foregroundStyle(d.done ? palette.onAccent : (d.canTick ? palette.textSecondary : palette.textTertiary))
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(d.done ? palette.accent : palette.surfaceRaised))
                            .overlay(Circle().stroke(d.today ? palette.accent : Color.clear, lineWidth: 1))
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: d.done)
                    }
                    .buttonStyle(.plain)
                    .disabled(!d.canTick)
                    .accessibilityLabel("\(chore.title), \(d.spoken)")
                }
            }
        }
    }
}

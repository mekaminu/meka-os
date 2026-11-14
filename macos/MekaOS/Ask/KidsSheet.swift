@preconcurrency import MekaKit
import SwiftUI

/// Ask → Kids on the Mac in the page shape (every page under Ask in one shape, slice 15; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's KidsPane: the title with its ⓘ, one gold + Add (A club fee ·
/// A chore · Pocket money a week, each one typed line, "Alex: …" first when there's more than one child), three numbers
/// (what they need this week, the next club fee, what's to pay this week or the sizes to check), then This week,
/// Children, Club fees and Chores and pocket money (five rows each, See all for the rest). A row opens in place with
/// ‹ Kids back: a child's card (sizes with Undo, All still fit, club fees with Paid / Remove and the fee field, chores
/// and pocket money), a fee's line with Paid and Remove (Undo), a child's chores and pocket money, or a thing this week.
/// Only Strings and day numbers cross to the core; Navilife never pays a fee or moves money.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and field and the ⓘ unfold on the
/// expand spring; a row opens and closes with a cross-fade and slide from the trailing edge; the lines cross-fade, the
/// checked line and a refusal in the accent; a save gives a light haptic, a refusal a tick. Reduce Motion: cross-fades.
struct KidsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Per "child|size": what the field holds while it's being edited.
    @State private var texts: [String: String] = [:]
    /// Per child: what the last save or All still fit said.
    @State private var said: [String: CoreModel.KidSaid] = [:]
    /// Per child: the fee field's text and what the last fee action said.
    @State private var feeTexts: [String: String] = [:]
    @State private var feeSaid: [String: CoreModel.KidSaid] = [:]
    /// Per child: the chore and pocket money fields and what the last chore/money action said.
    @State private var choreTexts: [String: String] = [:]
    @State private var pocketTexts: [String: String] = [:]
    @State private var moneySaid: [String: CoreModel.KidSaid] = [:]
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    /// What the last add said.
    @State private var addSaid: CoreModel.KidSaid?
    /// The row that's open, if any (its key).
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?
    /// A fee's open row: what Paid or Remove said (kept while the fee itself is gone, for its Undo).
    @State private var feeLine: CoreModel.KidSaid?

    var body: some View {
        let rules = KidsPageRules.shared
        let v = model.kids ?? KidsView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil; feeLine = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                ScrollView {
                    rowDetail(key, v)
                }
                .frame(maxHeight: 520)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(rules.cardTitle(cardKey: card)).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card, today: model.todayEpochDay), id: \.key) { r in
                            let key = r.key
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil } } label: {
                                HStack(spacing: MekaSpace.xs) {
                                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                    }
                                    Spacer(minLength: MekaSpace.xs)
                                    Text(r.whenText).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textTertiary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
                .frame(maxHeight: 400)
                .transition(.opacity)
            } else if let page = model.kidsPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); addSaid = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if !adding, let addSaid {
                        Text(addSaid.line).font(MekaType.caption)
                            .foregroundStyle(addSaid.refused ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                    }
                    if let empty = v.empty {
                        Button("About you…") { dismiss(); model.showAboutYou = true }
                            .accessibilityLabel(empty)
                    }
                }
                .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            let card = page.cardAt(index: Int32(i))
                            let cardKey = card.key
                            LifePageCardView(card: card, palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = cardKey }
                            }, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; addSaid = nil; feeLine = nil }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(KidsRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }

    /// An open row as it is now: a child's card, a fee, a child's chores and pocket money, or a thing this week.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: KidsView) -> some View {
        let rules = KidsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rules.sheetTitle(view: v, key: key)).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            if let kid = rules.kid(view: v, key: key) {
                if rules.isMoney(key: key) {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) { money(kid) }
                } else {
                    card(kid)
                }
            } else if let fee = rules.feeRow(view: v, key: key), let owner = rules.feeOwner(view: v, key: key) {
                feeDetail(kid: owner, fee: fee)
            } else if let thing = rules.weekThing(view: v, key: key) {
                Text(thing.text).font(MekaType.body).foregroundStyle(thing.lit ? palette.accent : palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel(thing.spoken)
                Text(KidsWeekRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            } else if feeLine == nil {
                // Removed (here or on the Fold) while it was open.
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if let line = feeLine {
                HStack(spacing: MekaSpace.xs) {
                    Text(line.line).font(MekaType.caption)
                        .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let id = line.feeUndo {
                        Button("Undo") {
                            Task {
                                if await model.undoKidFee(id: id) { feeLine = nil }
                                else { feeLine = CoreModel.KidSaid(line: KidsRules.shared.FEE_NOTHING_TO_UNDO, refused: true, undo: nil) }
                            }
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A fee's row: whose it is, its line, Paid and Remove; what they said shows under it with Undo.
    @ViewBuilder
    private func feeDetail(kid: KidCard, fee: KidFeeRow) -> some View {
        let id = fee.id
        Text(kid.name).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            Text(fee.line).font(MekaType.body)
                .foregroundStyle(fee.due ? palette.accent : palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: fee.line)
                .accessibilityLabel(fee.spoken)
            Spacer()
            Button("Paid") {
                Task { if let r = await model.kidFeePaid(id: id) { feeLine = r } }
            }
            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
            .accessibilityLabel("\(fee.title) paid")
            Button("Remove") {
                Task { if let r = await model.removeKidFee(id: id) { feeLine = r } }
            }
            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .accessibilityLabel("Remove \(fee.title)")
        }
        Text(KidsRules.shared.FEE_FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = KidsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    let key = c.key
                    chip(c.label, lit: addChoice == key) { addChoice = key; addSaid = nil }
                        .help(c.hint)
                }
            }
            if let choice = addChoice {
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(rules.spokenHint(choice: choice))
                    .onSubmit { submit(choice) }
                if let addSaid, addSaid.refused {
                    Text(addSaid.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .transition(.opacity)
    }

    private func submit(_ choice: String) {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let m = model
        Task {
            guard let r = await m.addKids(choice: choice, text: typed) else { return }
            addSaid = r
            // A refusal keeps its words to fix.
            if !r.refused { text = ""; adding = false; addChoice = nil }
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
                    Button(kid.fitButton) {
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
            TextField(row.placeholder, text: Binding(get: { texts[key] ?? row.value ?? "" }, set: { texts[key] = $0 }))
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

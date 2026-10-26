@preconcurrency import MekaKit
import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Ask → Life → Money on the Mac (Money manager, slice 1), like the Fold's MoneyPane: behind Touch ID (the Mac's
/// password on a Mac without it), the month's spending by category from imported statement files — ‹ month ›, what
/// went out and came in, against the 6 months before, each category with its share as a bar (in the accent when well
/// over its average), and the month's payments; click one to put that shop in another category from now on (Undo).
/// Import: name the account, choose the CSV file from the bank. Remove an account (click twice), Delete all money data
/// (asks first). Read-only; Navilife never pays or moves anything. Only Strings cross to the core.
///
/// Slice 2: this month also shows tips from his own numbers (each with Hide tips like this, Undo, Show them again)
/// and his regular payments with their monthly total.
///
/// Slice 3: this month leads with WORTH A LOOK (a possible double charge, a payment much bigger than usual or a first big
/// one, fees), each with That's fine (both devices, Undo); Navilife never acts on them.
///
/// Slice 6: UNTIL PAYDAY — payday from his pay, what's still to go out before it and, with the pay account's balance,
/// what's safe to spend a day (lit when it's tight).
///
/// Slice 7: CARDS — each account he marks as a card: when it's due (lit three days out; Paid, Undo), the statement and
/// minimum, the limit used (lit from 75%), 0% ending (lit within 30 days) and fees; Edit details types what the
/// statement says. Navilife never pays.
///
/// Slice 5: REFUNDS ON THE WAY — each refund on Waiting for, back in his statement (Close it, Undo) or not yet, and after
/// two weeks a chase to copy (Copy the chase marks it chased; he sends it himself).
///
/// Locking: the core holds no figures for the screen until `openMoney()` after Touch ID's yes; the sheet closing, the
/// screen sleeping or the session locking calls `closeMoney()`.
///
/// Motion: the sheet scale-fades (system); the month's lines cross-fade, its sections stagger in, each bar grows to its
/// share (snaps with Reduce Motion), a refused line turns the accent with a tick haptic; a save gives a light haptic.
struct MoneySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var account = ""
    @State private var said: CoreModel.MoneySaid?
    @State private var removing: String?
    @State private var deleting = false
    @State private var busy = false
    @State private var extra = ""
    @State private var balance = ""
    @State private var editingCard: String?
    @State private var cardTexts: [String: String] = [:]
    @State private var showOtherCards = false
    @State private var worthText = ""
    @State private var sharedText = ""
    @State private var funText = ""
    @State private var capText = ""
    @State private var tradingText = ""
    @State private var exporting = false
    @State private var worthBalances: [String: String] = [:]
    /// Slice 18: the year in review opened or folded by him, per year (else January's finished year opens by itself).
    @State private var yearOpen: [Int32: Bool] = [:]
    /// Money redesign (B), slice 1: the tab shown (Spending · Bills · Plan · Trading · Accounts); opens on Spending.
    @State private var tab: MoneyTab = MoneyTabs.shared.FIRST
    /// Money redesign (B2): the category picked on Spending's ring, and whether all categories / all payments show.
    @State private var focus: String?
    @State private var allCats = false
    @State private var allPays = false

    var body: some View {
        let v = model.money ?? MoneyView.companion.LOCKED
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.locked && v.summary == MoneyRules.shared.LOCKED ? MoneyRules.shared.LOCKED_MAC : v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            // Speed (4c): while the fresh view is built, the last one shows with "bringing it up to date…" under the title.
            if let line = v.opening, !v.skeleton {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary).transition(.opacity)
            }
            if v.skeleton {
                Spacer(minLength: 0)
            } else if v.locked {
                Button(MoneyRules.shared.UNLOCK) { unlock() }
                    .keyboardShortcut(.defaultAction)
                    .staggeredAppear(2)
            } else {
                tabPills(v).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        if let e = MoneyTabs.shared.empty(tab: tab, v: v) {
                            Text(e).font(MekaType.body).foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true).transition(.opacity)
                        }
                        if let b = v.bank, has(.bank, v) { bank(b).staggeredAppear(2) }
                        if let t = v.trading, has(.trading, v) { trading(t).staggeredAppear(2) }
                        if let m = v.month, tabOfMonth { month(m, v).staggeredAppear(2) }
                        if let g = v.goals, has(.goals, v) { moneyGoals(g).staggeredAppear(3) }
                        if let ch = v.challenges, has(.challenges, v) { challenges(ch).staggeredAppear(3) }
                        if let tv = v.travel, has(.travel, v) { travel(tv).transition(.opacity) }
                        if let w = v.worth, has(.worth, v) { worth(w).staggeredAppear(3) }
                        if let b = v.business, has(.business, v) { business(b).staggeredAppear(3) }
                        if let sh = v.shared, has(.sharedCosts, v) { shared(sh).staggeredAppear(3) }
                        if let s = said { saidLine(s) }
                        if has(.importStatement, v) { importer(v).staggeredAppear(3) }
                        if has(.accounts, v) { accounts(v).staggeredAppear(4) }
                        if has(.foot, v) { foot(v) }
                    }
                    .id(tab.name)
                    .transition(.opacity)
                }
                .animation(MekaMotion.appear(reduced: reduceMotion), value: tab.name)
                .scrollIndicators(.never)
                .frame(maxHeight: 560)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(v.locked ? .cancelAction : .defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 520)
        .background(palette.surface)
        .onAppear { if model.money?.locked ?? true { unlock() } }
        .onDisappear { model.closeMoney() }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.screensDidSleepNotification)) { _ in model.closeMoney() }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidResignActiveNotification)) { _ in model.closeMoney() }
    }

    /// Whether the chosen tab shows section [s] of [v] (MoneyTabs decides, as on the Fold).
    private func has(_ s: MoneySection, _ v: MoneyView) -> Bool { MoneyTabs.shared.shows(tab: tab, s: s, v: v) }

    /// Spending and Bills draw from the month's block (this month's payday, cards and refunds live there).
    private var tabOfMonth: Bool { tab == MoneyTab.spending || tab == MoneyTab.bills || tab == MoneyTab.plan }

    /// Money redesign (B), slice 1: Spending · Bills · Plan · Trading · Accounts as pills; the chosen one is lit and
    /// blends there; a click gives a tick. Bills and Plan step back to this month (MoneyTabs.stepOnPick).
    private func tabPills(_ v: MoneyView) -> some View {
        HStack(spacing: MekaSpace.xs) {
            ForEach(MoneyTabs.shared.ALL, id: \.name) { t in
                let lit = t == tab
                Button {
                    guard !lit else { return }
                    MekaHaptics.tick()
                    tab = t
                    if let step = MoneyTabs.shared.stepOnPick(tab: t, v: v) { model.moneyStep(step.int32Value) }
                } label: {
                    Text(t.label).font(MekaType.itemMeta).lineLimit(1)
                        .foregroundStyle(lit ? palette.accent : palette.textSecondary)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(lit ? palette.surfaceRaised : palette.background))
                        .contentShape(Capsule())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(MoneyTabs.shared.spoken(tab: t, chosen: lit))
                .accessibilityAddTraits(lit ? [.isSelected] : [])
            }
            Spacer(minLength: 0)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tab.name)
    }

    private func unlock() {
        let model = self.model // the observable model (main actor), not the view, crosses into the callback
        MacBiometric.unlock(reason: MoneyRules.shared.UNLOCK_REASON) { ok in
            if ok { MekaHaptics.light(); model.openMoney() } else { model.moneyUnlockRefused(); MekaHaptics.tick() }
        }
    }

    private func month(_ m: MoneyMonth, _ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if has(.month, v) {
            HStack {
                Button("‹") { model.moneyStep(-1) }.disabled(!v.earlier).accessibilityLabel("Month before")
                Text(m.label).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    .contentTransition(.opacity)
                Spacer()
                Button("›") { model.moneyStep(1) }.disabled(!v.later).accessibilityLabel("Month after")
            }
            if let sp = m.spending {
                // Money redesign (B2): the ring with the month's total in the middle, then the tiles.
                MoneyRingView(spending: sp, focus: focus, palette: palette, reduced: reduceMotion) { focus = nil }
                if !sp.tiles.isEmpty { MoneyTilesView(tiles: sp.tiles, palette: palette) }
            } else {
            Text(m.spentLine).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
            if let vs = m.vsLine {
                Text(vs).font(MekaType.caption).foregroundStyle(m.vsLit ? palette.accent : palette.textSecondary).contentTransition(.opacity)
            }
            }
            }
            if let b = v.travel?.banner, has(.travelBanner, v) {
                // Slice 17: abroad, or a trip near — the card to use leads this month.
                Text(b).font(MekaType.body).foregroundStyle(palette.accent).contentTransition(.opacity)
                    .fixedSize(horizontal: false, vertical: true).padding(.top, MekaSpace.xxs)
            }
            if let rv = v.review, has(.review, v) { review(rv).transition(.opacity) }
            if has(.alerts, v) {
                Text(MoneyWatch.shared.head(n: Int32(v.alerts.count))).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs).contentTransition(.opacity)
                ForEach(v.alerts, id: \.id) { a in alert(a).transition(.opacity) }
                Text(MoneyWatch.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if let p = v.payday, has(.payday, v) { payday(p).transition(.opacity) }
            if let c = v.cards, has(.cards, v) { cards(c).transition(.opacity) }
            if let yv = v.year, has(.year, v) { year(yv).transition(.opacity) }
            if let r = v.refunds, has(.refunds, v) {
                Text(r.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs).contentTransition(.opacity)
                ForEach(r.rows, id: \.id) { row in refund(row).transition(.opacity) }
                if let f = r.foot { Text(f).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            }
            if has(.tips, v) && !v.tips.isEmpty {
                Text("TIPS FROM YOUR NUMBERS").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                ForEach(v.tips, id: \.id) { t in tip(t).transition(.opacity) }
            }
            if let h = v.hiddenTips, has(.tips, v) {
                HStack {
                    Text(h).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity)
                    Spacer()
                    Button(MoneyTips.shared.SHOW_AGAIN) { Task { said = await model.showMoneyTips() } }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            if let r = v.regular, has(.regular, v) {
                Text("REGULAR PAYMENTS").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                Text(r.head).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                ForEach(r.rows, id: \.key) { row in regular(row).transition(.opacity) }
            }
            if let f = v.forecast, has(.forecast, v) { forecast(f).transition(.opacity) }
            if has(.categories, v), let sp = m.spending {
                MoneyHeadWithLink(head: "WHERE IT WENT", link: MoneySpending.shared.allLabel(rows: Int32(sp.rows.count), open: allCats), palette: palette) {
                    allCats.toggle()
                }
                ForEach(MoneySpending.shared.shownRows(v: sp, open: allCats), id: \.key) { r in
                    let key = r.key
                    MoneySpendRowView(row: r, picked: focus == key, palette: palette, reduced: reduceMotion) {
                        MekaHaptics.tick()
                        focus = focus == key ? nil : key
                    }
                    .transition(.opacity)
                }
            } else if has(.categories, v) {
                Text("BY CATEGORY").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                ForEach(m.categories, id: \.key) { c in category(c).transition(.opacity) }
            }
            if has(.payments, v), let sp = m.spending, !allPays {
                MoneyHeadWithLink(head: MoneySpending.shared.LATEST_HEAD, link: sp.seeAll, palette: palette) { allPays = true }
                ForEach(sp.latest, id: \.label) { d in
                    MoneyDayHead(day: d, palette: palette)
                    ForEach(d.rows, id: \.id) { t in txn(t, chip: sp, showDay: false) }
                }
            } else if has(.payments, v) {
                if m.spending != nil {
                    MoneyHeadWithLink(head: "ALL PAYMENTS THIS MONTH", link: MoneySpending.shared.SEE_ALL_CLOSE, palette: palette) { allPays = false }
                }
                Text("PAYMENTS · CLICK ONE TO CHANGE ITS CATEGORY").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                if let l = m.receiptsLine {
                    Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                ForEach(m.txns, id: \.id) { t in txn(t, chip: m.spending) }
                if m.more > 0 {
                    Text("and \(m.more) more this month").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: m.label)
    }

    /// Slice 6: payday, what's still to go out before it, the pay account's balance (typed here or from a statement)
    /// and what's safe to spend, lit when it's tight.
    private func payday(_ p: MoneyPaydayView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(p.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let n = p.notReady {
                Text(n).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if let l = p.payLine { Text(l).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity) }
            if let f = p.fromLine { Text(f).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            if let s = p.sinceLine { Text(s).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
            if let g = p.goingOut { Text(g).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity) }
            ForEach(p.rows, id: \.key) { r in
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(r.day).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    Text(r.amount).font(MekaType.body).foregroundStyle(palette.textPrimary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(r.spoken)
            }
            if p.more > 0 { Text("+ \(p.more) more").font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            if let b = p.balanceLine {
                Text(b).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let id = p.account {
                TextField(MoneyPayday.shared.BALANCE_HINT, text: $balance)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        let text = balance
                        balance = ""
                        Task { said = await model.moneyBalance(accountId: id, text: text) }
                    }
            }
            if let s = p.safe {
                Text(s).font(MekaType.body).foregroundStyle(p.safeLit ? palette.accent : palette.textPrimary).contentTransition(.opacity)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(p.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: p.safe)
    }

    /// Slice 7: his cards, then the accounts he could mark as one.
    private func cards(_ cv: MoneyCardsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(cv.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            ForEach(cv.rows, id: \.id) { c in card(c).transition(.opacity) }
            if let l = cv.offerLine {
                Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            // Quality gate slice 13: a current or savings account waits behind "Show 1 more account".
            ForEach(Array(cv.offers.prefix(4)) + (showOtherCards ? cv.others : []), id: \.id) { o in
                HStack {
                    Text(o.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Button(MoneyCards.shared.MARK) {
                        let id = o.id
                        Task { said = await model.moneyCardKind(accountId: id, card: true, charge: false) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    .accessibilityLabel("\(o.name) is a card")
                }
            }
            if let l = cv.othersLabel, !showOtherCards {
                Button(l) { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { showOtherCards = true } }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if !cv.rows.isEmpty {
                Text(cv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Slice 7: one card — due with Paid, statement, limit used, 0% ending, fees; Edit details for the typed fields.
    /// One of a card's typed fields (split out of `card` so the type checker keeps up, 2026-10-17 nightly).
    private func cardField(cardId: String, _ f: MoneyCardField) -> some View {
        let key: String = cardId + "|" + f.key
        let prompt: String = f.value.isEmpty ? f.hint : f.label + " · now " + f.value
        let text: Binding<String> = Binding(get: { cardTexts[key] ?? "" }, set: { cardTexts[key] = $0 })
        return TextField(prompt, text: text)
            .textFieldStyle(.roundedBorder)
            .onSubmit {
                let typed: String = cardTexts[key] ?? ""
                cardTexts[key] = nil
                let id: String = cardId
                let field: String = f.key
                Task { said = await model.moneyCardField(accountId: id, field: field, text: typed) }
            }
    }

    private func card(_ c: MoneyCardRow) -> some View {
        let open = editingCard == c.id || c.statementLine == nil
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(c.name).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(c.kindLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text(c.dueLine).font(MekaType.body).foregroundStyle(c.dueLit ? palette.accent : palette.textPrimary).contentTransition(.opacity)
                if let p = c.paidLine { Text(p).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                if let s = c.statementLine { Text(s).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                if let u = c.useLine { Text(u).font(MekaType.caption).foregroundStyle(c.useLit ? palette.accent : palette.textSecondary) }
                if let p = c.promoLine { Text(p).font(MekaType.caption).foregroundStyle(c.promoLit ? palette.accent : palette.textSecondary) }
                if let f = c.feesLine { Text(f).font(MekaType.caption).foregroundStyle(palette.accent).fixedSize(horizontal: false, vertical: true) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(c.spoken)
            HStack(spacing: MekaSpace.m) {
                if c.canPay {
                    Button(MoneyCards.shared.PAID) {
                        let id = c.id
                        Task { said = await model.moneyCardPaid(accountId: id) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
                Button(open ? "Done editing" : "Edit details") { editingCard = open ? nil : c.id }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if open {
                ForEach(c.fields, id: \.key) { f in cardField(cardId: c.id, f) }
                HStack(spacing: MekaSpace.m) {
                    Button(MoneyCards.shared.CHARGE_SWITCH + (c.charge ? " ✓" : "")) {
                        let id = c.id
                        let charge = !c.charge
                        Task { said = await model.moneyCardKind(accountId: id, card: true, charge: charge) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(c.charge ? palette.accent : palette.textSecondary)
                    Button(MoneyCards.shared.NOT_CARD) {
                        let id = c.id
                        Task { said = await model.moneyCardKind(accountId: id, card: false, charge: false) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                .transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: open)
    }

    /// Slice 15: CHALLENGES — the No-spend days switch and, when on, the month's days as dots (brass = no spending) with
    /// the count and the streak; fun money for the week and each category cap as a bar (brass from 90%) with what's left
    /// and the run (Remove); fields to set them.
    private func challenges(_ ch: MoneyChallengesView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(ch.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let i = ch.intro {
                Text(i).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Text(MoneyChallenges.shared.SWITCH).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                Button(ch.noSpendOn ? "On ✓" : "Count them") {
                    let on = !ch.noSpendOn
                    Task { said = await model.moneyNoSpend(on: on) }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption)
                .foregroundStyle(ch.noSpendOn ? palette.accent : palette.textSecondary)
                .accessibilityLabel("\(MoneyChallenges.shared.SWITCH)\(ch.noSpendOn ? ", on" : ", off")")
            }
            if let ns = ch.noSpend {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if ns.days.count > 0 {
                        HStack(spacing: 3) { // rhythm: ok (the month's day dots are one mark)
                            ForEach(0..<ns.days.count, id: \.self) { i in
                                Circle().fill(ns.dayAt(i: Int32(i)) ? palette.accent : palette.surfaceRaised).frame(width: 6, height: 6)
                            }
                        }
                    }
                    Text(ns.line).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    if let st = ns.streakLine {
                        Text(st).font(MekaType.caption).foregroundStyle(ns.lit ? palette.accent : palette.textSecondary).contentTransition(.opacity)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(ns.spoken)
            }
            if let f = ch.funMoney { challengeBar(f) }
            ForEach(ch.caps, id: \.key) { c in challengeBar(c).transition(.opacity) }
            if let t = ch.throughLine { Text(t).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            TextField(ch.funHint, text: $funText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = funText
                    funText = ""
                    Task { said = await model.moneyFunMoney(text: text) }
                }
            TextField(ch.capHint, text: $capText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = capText
                    capText = ""
                    Task { said = await model.moneyCap(text: text) }
                }
            Text(ch.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: ch.caps.count)
    }

    /// Money manager (6): MONEY GOALS — each £ goal from Goals with where it stands (a bar), which account it follows and
    /// a chip per account (the chosen one in brass; click it again to stop following). Lines cross-fade as they change.
    private func moneyGoals(_ gv: MoneyGoalsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(gv.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let e = gv.empty {
                Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(gv.rows, id: \.id) { r in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(r.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Text(r.line).font(MekaType.body).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule().fill(palette.surfaceRaised)
                                Capsule().fill(palette.accent).frame(width: geo.size.width * CGFloat(min(max(Int(r.pct), 0), 100)) / 100)
                            }
                        }
                        .frame(height: 4)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.pct)
                        Text(r.follows ?? "Follow an account's balance:").font(MekaType.caption)
                            .foregroundStyle(r.follows != nil ? palette.accent : palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(r.spoken)
                    ScrollView(.horizontal) {
                        HStack(spacing: MekaSpace.xs) {
                            ForEach(r.choices, id: \.accountId) { c in
                                Button {
                                    let gid = r.id
                                    let aid: String? = c.picked ? nil : c.accountId
                                    Task { said = await model.moneyGoalFollow(goalId: gid, accountId: aid) }
                                } label: {
                                    Text(c.name + (c.picked ? " ✓" : "")).font(MekaType.caption)
                                        .foregroundStyle(c.picked ? palette.accent : palette.textSecondary)
                                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                                }
                                .buttonStyle(MekaPressStyle())
                                .accessibilityLabel(c.picked ? "Stop following \(c.name)" : "Follow \(c.name)")
                            }
                        }
                    }
                    .scrollIndicators(.never)
                }
                .animation(MekaMotion.appear(reduced: reduceMotion), value: r.follows)
            }
            Text(gv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Slice 17: PAYING ABROAD — the tip (brass when a card had no foreign fees, or he's going), then each account with
    /// a brass dot by one that charged none. Lines cross-fade as they change.
    private func travel(_ tv: MoneyTravelView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(tv.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let t = tv.tip {
                Text(t).font(MekaType.body).foregroundStyle(tv.tipLit ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(tv.rows, id: \.id) { r in
                HStack(spacing: MekaSpace.xs) {
                    Circle().fill(r.good ? palette.accent : palette.surfaceRaised).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(r.spoken)
                .transition(.opacity)
            }
            Text(tv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tv.rows.count)
    }

    /// Money manager (7): the month in review — a few plain sentences (the first in brass when more went out than came
    /// in), then where it's from; the finished month shown, or last month leading this one for its first days.
    private func review(_ rv: MoneyMonthReviewView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rv.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            ForEach(Array(rv.sentences.enumerated()), id: \.offset) { i, l in
                Text(l).font(MekaType.body)
                    .foregroundStyle(i == 0 ? (rv.firstLit ? palette.accent : palette.textPrimary) : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
            }
            Text(rv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(rv.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: rv.month)
    }

    /// Slice 18: the year in review — the folded line with Open/Fold (January's finished year opens by itself), then
    /// what was spent and came in, left over, against the year before, each category with its bar, the busiest and
    /// quietest months and the biggest payment. Unfolds on the expand spring.
    private func year(_ yv: MoneyYearView) -> some View {
        let open = yearOpen[yv.year] ?? yv.unfolded
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(yv.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            Button {
                let y = yv.year
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { yearOpen[y] = !open }
            } label: {
                HStack {
                    // Quality gate slice 13: the head above already names the year, so the line is the title alone.
                    Text(yv.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    Text(open ? "Fold" : "Open").font(MekaType.body).foregroundStyle(palette.accent)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(open ? yv.spoken : yv.folded)
            if open {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if let l = yv.leftLine {
                        Text(l).font(MekaType.body).foregroundStyle(yv.leftLit ? palette.accent : palette.textSecondary)
                    }
                    if let vs = yv.vsLine {
                        Text(vs).font(MekaType.caption).foregroundStyle(yv.vsLit ? palette.accent : palette.textSecondary)
                    }
                    ForEach(yv.categories, id: \.key) { c in
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            HStack {
                                Text(c.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                Spacer()
                                Text(c.amount).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            }
                            MoneyBar(share: Double(c.share), lit: c.lit, palette: palette, reduced: reduceMotion)
                            Text(c.line).font(MekaType.caption).foregroundStyle(c.lit ? palette.accent : palette.textSecondary)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(c.spoken)
                    }
                    if let ml = yv.monthsLine { Text(ml).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                    if let b = yv.biggest { Text(b).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                    Text(yv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// Slice 15: one allowance or cap — its title and amount, a bar to its share (brass from 90%), what's left and Remove.
    private func challengeBar(_ b: MoneyChallengeBar) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack {
                Text(b.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(b.amount).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(b.spoken)
            MoneyBar(share: min(Double(b.share), 1), lit: b.lit, palette: palette, reduced: reduceMotion)
            HStack {
                Text(b.line).font(MekaType.caption).foregroundStyle(b.lit ? palette.accent : palette.textSecondary).contentTransition(.opacity)
                Spacer()
                Button("Remove") {
                    let key = b.key
                    Task { said = await model.moneyChallengeRemove(key: key) }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(b.title)")
            }
        }
        .padding(.top, MekaSpace.xs)
    }

    /// Slice 14: SHARED WITH <PARTNER> — since when, what each paid, the split with its chips, who owes whom (in brass
    /// when someone does), this month's shared total, what the partner paid (Remove), a field to type it and Settled up.
    private func shared(_ sh: MoneySharedView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sh.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let i = sh.intro {
                Text(i).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if let bal = sh.balanceLine {
                VStack(alignment: .leading, spacing: 0) {
                    if let l = sh.sinceLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
                    Text(bal).font(MekaType.itemTitle).foregroundStyle(sh.balanceLit ? palette.accent : palette.textPrimary).contentTransition(.opacity)
                    if let l = sh.youLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                    if let l = sh.partnerLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                    if let l = sh.splitLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(sh.spoken)
                HStack(spacing: MekaSpace.xs) {
                    ForEach(sh.chips, id: \.key) { c in
                        Button {
                            guard !c.on else { return }
                            let key = c.key
                            Task { said = await model.moneySharedShare(key: key) }
                        } label: {
                            Text(c.label).font(MekaType.caption).foregroundStyle(c.on ? palette.accent : palette.textSecondary)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel("Your share \(c.label)\(c.on ? ", chosen" : "")")
                    }
                }
                if let label = sh.settle {
                    Button(label) { Task { said = await model.moneySharedSettle() } }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        .accessibilityLabel("\(label) with \(sh.partner)")
                }
                if let l = sh.monthLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
            }
            ForEach(sh.rows, id: \.id) { r in
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(r.spoken)
                    Spacer()
                    Text(r.amount).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Button("Remove") {
                        let id = r.id
                        Task { said = await model.moneySharedRemove(id: id) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(r.name) from what was paid")
                }
                .transition(.opacity)
            }
            if let m = sh.merchantsLine { Text(m).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            TextField(sh.hint, text: $sharedText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = sharedText
                    sharedText = ""
                    Task { said = await model.moneySharedAdd(text: text) }
                }
            Text(sh.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: sh.balanceLine)
    }

    /// Slice 13c: FROM YOUR BUSINESS EMAIL — each email's money with whether the statements show it (lit when they
    /// should by now and don't), its subject as a quote, and a Business chip that counts the matched payment's payer as
    /// the side hustle's. Lines cross-fade as a match lands.
    private func businessMail(_ m: MoneyBusinessMailView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(m.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            Text(m.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
            ForEach(m.rows, id: \.id) { r in
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(r.line).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary).contentTransition(.opacity)
                        if let q = r.quote {
                            Text(q).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1).truncationMode(.tail)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(r.spoken)
                    Spacer()
                    if let txn = r.businessTxnId {
                        Button {
                            Task { said = await model.moneyBusinessMerchant(txnId: txn, on: true) }
                        } label: {
                            Text(MoneyBusinessMail.shared.CHIP).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel(MoneyBusinessMail.shared.chipSpoken(row: r))
                    }
                }
            }
            if let more = m.moreLine { Text(more).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            Text(m.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: m.line)
    }

    /// Slice 13: SIDE HUSTLE AND TAX — the tax year so far (profit in brass), the share to put aside with its chips, the
    /// trading allowance, the biggest costs, last tax year, the next Self Assessment dates (lit within 30 days) and each
    /// account's Side hustle account switch.
    private func business(_ b: MoneyBusinessView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(b.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let i = b.intro {
                Text(i).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if let profit = b.profitLine {
                VStack(alignment: .leading, spacing: 0) {
                    if let y = b.yearLine { Text(y).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
                    Text(profit).font(MekaType.itemTitle).foregroundStyle(b.profitUp ? palette.accent : palette.textPrimary).contentTransition(.opacity)
                    if let i = b.inLine { Text(i).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                    if let o = b.outLine { Text(o).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(b.spoken)
                if let a = b.setAsideLine { Text(a).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity) }
                HStack(spacing: MekaSpace.xs) {
                    ForEach(b.chips, id: \.key) { c in
                        Button {
                            guard !c.on else { return }
                            let key = c.key
                            Task { said = await model.moneySetAside(key: key) }
                        } label: {
                            Text(c.label).font(MekaType.caption).foregroundStyle(c.on ? palette.accent : palette.textSecondary)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel("Put aside \(c.label)\(c.on ? ", chosen" : "")")
                    }
                }
                if let l = b.allowanceLine {
                    Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(b.costs, id: \.key) { c in
                    HStack {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(c.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                        Spacer()
                        Text(c.amount).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    }
                    .accessibilityElement(children: .combine)
                }
                if let l = b.lastYearLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                // Slice 13b: YEAR-END EXPORT — a button per tax year, then a save panel.
                if let e = b.export { tradingExport(e, business: true) }
                ForEach(b.dates, id: \.day) { d in
                    VStack(alignment: .leading, spacing: 0) {
                        Text(d.line).font(MekaType.body).foregroundStyle(d.lit ? palette.accent : palette.textPrimary)
                        Text(d.title).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            // Slice 13c: FROM YOUR BUSINESS EMAIL — this tax year's money in his Business mail against the statements.
            if let m = b.mail { businessMail(m) }
            if let m = b.merchantsLine { Text(m).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            ForEach(b.accounts, id: \.id) { a in
                HStack {
                    Text(a.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Button(MoneyBusiness.shared.SWITCH + (a.on ? " ✓" : "")) {
                        let id = a.id
                        let on = !a.on
                        Task { said = await model.moneyBusinessAccount(accountId: id, on: on) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(a.on ? palette.accent : palette.textSecondary)
                    .accessibilityLabel("\(a.name): \(MoneyBusiness.shared.SWITCH), \(a.on ? "on" : "off")")
                }
            }
            Text(b.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.profitLine)
    }

    /// Slice 8: net worth — the total, have/owe, the change since last month, the month-by-month line, each account,
    /// card and typed thing (a field for an account with no balance; Remove on a typed one), and a field to add one.
    private func worth(_ w: MoneyWorthView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(w.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let t = w.total {
                VStack(alignment: .leading, spacing: 0) {
                    Text(t).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    if let s = w.splitLine { Text(s).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                    if let c = w.changeLine { Text(c).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(w.spoken)
            }
            if let e = w.empty {
                Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if w.points.count >= 2 { MoneyWorthLine(points: w.points, palette: palette, reduced: reduceMotion) }
            if let l = w.chartLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            ForEach(w.rows, id: \.id) { r in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(r.spoken)
                        Spacer()
                        if !r.amount.isEmpty { Text(r.amount).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity) }
                        if r.removable {
                            Button("Remove") {
                                let id = r.id
                                Task { said = await model.moneyWorthRemove(id: id) }
                            }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .accessibilityLabel("Remove \(r.name) from your net worth")
                        }
                    }
                    if r.needsBalance {
                        TextField(MoneyWorth.shared.BALANCE_HINT, text: Binding(
                            get: { worthBalances[r.id] ?? "" },
                            set: { worthBalances[r.id] = $0 }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .onSubmit {
                            let id = r.id
                            let text = worthBalances[id] ?? ""
                            worthBalances[id] = nil
                            Task { said = await model.moneyBalance(accountId: id, text: text) }
                        }
                    }
                }
                .transition(.opacity)
            }
            if let m = w.missingLine { Text(m).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            TextField(w.hint, text: $worthText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = worthText
                    worthText = ""
                    Task { said = await model.moneyWorthAdd(text: text) }
                }
            Text(w.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: w.total)
    }

    /// Slice 4: the forecast at his pace, with what-ifs on this Mac only (they go when Money locks).
    private func forecast(_ f: MoneyForecastView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(f.head).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            if let n = f.notReady {
                Text(n).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            } else {
                if let p = f.pace { Text(p).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity) }
                if let l = f.left {
                    Text(l).font(MekaType.caption).foregroundStyle(f.leftLit ? palette.accent : palette.textSecondary).contentTransition(.opacity)
                }
                ForEach(f.rows, id: \.label) { r in
                    HStack {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(r.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                        }
                        Spacer()
                        Text(r.amount).font(MekaType.body).foregroundStyle(r.lit ? palette.accent : palette.textPrimary).contentTransition(.opacity)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(r.spoken)
                }
                Text(MoneyForecast.shared.WHAT_IF).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                if !f.cuts.isEmpty {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(f.cuts, id: \.key) { c in cutChip(c) }
                    }
                }
                // Trading tracker, slice 11: trading at the pace it has gone, on its own row, and where it's from.
                if let t = f.trading { cutChip(t) }
                if let l = f.tradingLine {
                    Text(l).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                }
                TextField(MoneyForecast.shared.EXTRA_HINT, text: $extra)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        let text = extra
                        Task { said = await model.moneyForecastExtra(text) }
                    }
                if let e = f.extraLine { Text(e).font(MekaType.caption).foregroundStyle(palette.accent) }
                if let b = f.basis {
                    Text(b).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: f.rows.map { $0.line })
    }

    /// A what-if chip (a halved category, or slice 11's trading); a click turns it on or off on this Mac only.
    private func cutChip(_ c: MoneyCutChip) -> some View {
        Button {
            model.moneyForecastCut(c.key)
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                Text(c.label).font(MekaType.caption).foregroundStyle(c.on ? palette.accent : palette.textSecondary)
                Text(c.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(c.spoken)
    }

    /// Slice 3: one payment worth a look, with That's fine (Undo in the line below).
    private func alert(_ a: MoneyAlert) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(a.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(a.line).font(MekaType.caption).foregroundStyle(a.lit ? palette.accent : palette.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(a.spoken)
            Button(MoneyWatch.shared.FINE) {
                let id = a.id
                Task { said = await model.moneyAlertFine(alertId: id) }
            }
            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Slice 5: one refund on Waiting for, checked against his statements: Close it when it's back (Undo in the line
    /// below), the chase to copy when it's late (he sends it himself; Navilife sends nothing).
    private func refund(_ r: MoneyRefundRow) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(r.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(r.line).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(r.spoken)
            if r.state == MoneyRefunds.shared.BACK {
                Button(MoneyRefunds.shared.CLOSE) {
                    let id = r.id
                    Task { said = await model.moneyRefundClose(waitingId: id) }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
            }
            if let chase = r.chase {
                Text(chase).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                Button(MoneyRefunds.shared.COPY) {
                    let pb = NSPasteboard.general
                    pb.clearContents()
                    pb.setString(chase, forType: .string)
                    let id = r.id
                    Task { said = await model.moneyRefundChased(waitingId: id) }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                Text(MoneyRefunds.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Slice 2: one tip from his own numbers, with Hide tips like this (Undo in the line below).
    private func tip(_ t: MoneyTip) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(t.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(t.line).font(MekaType.caption).foregroundStyle(t.lit ? palette.accent : palette.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(t.spoken)
            Button(MoneyTips.shared.HIDE) {
                let kind = t.kind
                Task { said = await model.hideMoneyTips(kind: kind) }
            }
            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Slice 2: one regular payment, lit when it went up.
    private func regular(_ r: MoneyRegularRow) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 0) {
                Text(r.merchant).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(r.line).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
            }
            Spacer()
            Text(r.amount).font(MekaType.body).foregroundStyle(palette.textPrimary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(r.spoken)
    }

    private func category(_ c: MoneyCategoryRow) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack {
                Text(c.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(c.amount).font(MekaType.body).foregroundStyle(palette.textPrimary)
            }
            MoneyBar(share: Double(c.share), lit: c.over, palette: palette, reduced: reduceMotion)
            Text(c.line).font(MekaType.caption).foregroundStyle(c.over ? palette.accent : palette.textSecondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(c.spoken)
    }

    private func txn(_ t: MoneyTxnRow, chip: MoneySpendingView? = nil, showDay: Bool = true) -> some View {
        Menu {
            // Slice 13: the payer counts as the side hustle's (or not), on every account.
            Button(t.business ? MoneyBusiness.shared.MENU_ON : MoneyBusiness.shared.MENU_OFF) {
                let id = t.id
                let on = !t.business
                Task { said = await model.moneyBusinessMerchant(txnId: id, on: on) }
            }
            // Slice 14: this payment, or every payment to this payer, shared with his partner.
            Button(t.shared ? MoneyShared.shared.MENU_ON : MoneyShared.shared.MENU_OFF) {
                let id = t.id
                let on = !t.shared
                Task { said = await model.moneySharedTxn(txnId: id, on: on) }
            }
            Button(t.sharedAlways ? MoneyShared.shared.MENU_ALWAYS_ON : MoneyShared.shared.MENU_ALWAYS_OFF) {
                let id = t.id
                let on = !t.sharedAlways
                Task { said = await model.moneySharedMerchant(txnId: id, on: on) }
            }
            Divider()
            ForEach(MoneyRules.shared.CHOICES, id: \.key) { choice in
                Button(choice.key == t.category ? "✓ \(choice.label)" : choice.label) {
                    guard choice.key != t.category else { return }
                    Task { said = await model.setMoneyCategory(txnId: t.id, category: choice.key) }
                }
            }
        } label: {
            HStack(spacing: chip == nil ? nil : MekaSpace.m) {
                if let sp = chip {
                    MoneyIconChip(icon: MoneySpending.shared.icon(key: t.category), tone: MoneySpending.shared.txnTone(v: sp, category: t.category), palette: palette)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(t.merchant).font(chip == nil ? MekaType.body : MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                    Text("\(showDay ? "\(t.day) · " : "")\(t.categoryLabel)\(t.taught ? " · yours" : "")\(t.business ? " · Business" : "")\(t.shared ? " · Shared" : "") · \(t.account)")
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    // Money at a glance: what the payment was for, from its receipt or order email (the shop's words).
                    if let r = t.receipt {
                        Text(r).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    if let d = t.receiptDetail {
                        Text(d).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(2)
                    }
                }
                Spacer()
                Text(t.amount).font(MekaType.body).foregroundStyle(t.moneyIn ? palette.accent : palette.textPrimary)
            }
            .contentShape(Rectangle())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .accessibilityLabel(t.spoken + ". Change category")
    }

    private func saidLine(_ s: CoreModel.MoneySaid) -> some View {
        HStack {
            Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            if let u = s.undo {
                Button("Undo") { Task { _ = await model.undoMoneyCategory(token: u); said = nil } }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
            }
        }
        .transition(.opacity)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: s)
    }

    /// Bank sync: FROM YOUR BANKS — when Lunch Flow last gave his banks (lit when something needs him), each account to
    /// reconnect, Refresh (Refreshing… until the server has been), Open Lunch Flow, Sync them again for removed accounts.
    private func bank(_ b: MoneyBankView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(b.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Text(b.line).font(MekaType.body).foregroundStyle(b.lit ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
            ForEach(b.reconnect, id: \.id) { r in
                Text(r.line).font(MekaType.caption).foregroundStyle(palette.accent).fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: MekaSpace.m) {
                Button(b.refreshing ? MoneyBank.shared.REFRESHING : MoneyBank.shared.REFRESH) {
                    Task { said = await model.moneyBankRefresh() }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption)
                .foregroundStyle(b.refreshing ? palette.textTertiary : palette.accent)
                .disabled(b.refreshing)
                if b.lit {
                    Button(MoneyBank.shared.OPEN) {
                        MekaHaptics.light()
                        if let url = URL(string: MoneyBank.shared.LUNCH_FLOW_URL) { NSWorkspace.shared.open(url) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            if let r = b.restoreLine {
                HStack {
                    Text(r).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    Button(MoneyBank.shared.RESTORE) { Task { said = await model.moneyBankRestore() } }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.refreshing)
    }

    /// Trading tracker: TRADING · HYPERLIQUID — equity and when it was read, P&L (up in brass), open positions nearest
    /// liquidation first (lit within 10%), each public address with Remove, and a field to add one. Read-only.
    private func trading(_ t: TradingView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(t.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            if let total = t.total {
                VStack(alignment: .leading, spacing: 0) {
                    Text(total).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    if let l = t.totalLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(t.spoken)
            }
            if let e = t.empty {
                Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if let c = t.currency { tradingCurrency(c) }
            ForEach(t.pnl, id: \.label) { r in
                HStack {
                    Text(r.label).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    Spacer()
                    Text(r.amount).font(MekaType.body)
                        .foregroundStyle(r.up?.boolValue == true ? palette.accent : palette.textPrimary)
                        .contentTransition(.opacity)
                }
            }
            if let u = t.unrealisedLine { Text(u).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            ForEach(t.positions, id: \.id) { p in
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text(p.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(p.amount).font(MekaType.body)
                            .foregroundStyle(p.up?.boolValue == true ? palette.accent : palette.textPrimary)
                    }
                    if !p.line.isEmpty { Text(p.line).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                    if let l = p.liquidationLine {
                        Text(l).font(MekaType.caption).foregroundStyle(p.lit ? palette.accent : palette.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(p.spoken)
                .transition(.opacity)
            }
            if let c = t.curve { tradingCurve(c) }
            if let tr = t.trades { trades(tr) }
            if let r = t.review { tradingReview(r) }
            ForEach(t.wallets, id: \.id) { w in
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(w.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(w.line).font(MekaType.caption).foregroundStyle(w.lit ? palette.accent : palette.textSecondary)
                    }
                    Spacer()
                    Button("Remove") {
                        let id = w.id
                        Task { said = await model.tradingWalletRemove(id: id) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Stop following \(w.name)")
                }
                .transition(.opacity)
            }
            TextField(t.hint, text: $tradingText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = tradingText
                    tradingText = ""
                    Task { said = await model.tradingWalletAdd(text: text) }
                }
            if let a = t.alerts { tradingAlerts(a) }
            if let e = t.export { tradingExport(e) }
            Text(t.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: t.total)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: t.wallets.count)
    }

    /// Trading tracker, slice 3: ALERTS — what he's told and when (near liquidation, even in quiet hours; a drop from the
    /// month's high; slice 7: a big order and a day's high funding), with a row of choices for each; the chosen one in brass.
    private func tradingAlerts(_ a: TradingAlertsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(a.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Text(a.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
            alertChips(a.liqLabel, a.liqChips)
            alertChips(a.dropLabel, a.dropChips)
            if !a.fillChips.isEmpty { alertChips(a.fillLabel, a.fillChips) }
            if !a.fundingChips.isEmpty { alertChips(a.fundingLabel, a.fundingChips) }
        }
        .padding(.top, MekaSpace.m)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: a.line)
    }

    /// Trading tracker, slice 8: EXPORT FOR TAX — a button per UK tax year; while the server reads the year they dim and
    /// the line says so, then a save panel asks where the CSV goes.
    /// Money slice 13b: the same block serves the side hustle's YEAR-END EXPORT (`business`), made on the Mac at once.
    private func tradingExport(_ e: TradingExportView, business: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(e.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Text(exporting && !business ? TradingExportRules.shared.WORKING : e.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
            HStack(spacing: MekaSpace.xs) {
                ForEach(e.chips, id: \.key) { c in
                    Button {
                        let key = c.key
                        exportYear(key, business: business)
                    } label: {
                        Text(c.label).font(MekaType.caption).foregroundStyle(exporting ? palette.textTertiary : palette.accent)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    }
                    .buttonStyle(MekaPressStyle())
                    .disabled(exporting)
                    .accessibilityLabel(c.spoken)
                }
            }
        }
        .padding(.top, business ? MekaSpace.xs : MekaSpace.m)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: exporting)
    }

    private func exportYear(_ key: String, business: Bool = false) {
        guard !exporting else { return }
        exporting = true
        Task {
            let file: CoreModel.TradingExportFile?
            if business {
                file = await model.moneyBusinessExport(key: key)
            } else {
                file = await model.tradingExport(key: key)
            }
            exporting = false
            guard let file else { return }
            said = file.said
            guard let csv = file.csv, let name = file.fileName else { return }
            let panel = NSSavePanel()
            panel.allowedContentTypes = [.commaSeparatedText]
            panel.nameFieldStringValue = name
            panel.message = business ? "Where to save the spreadsheet of your side hustle's money · for your accountant"
                : "Where to save the spreadsheet of your trades · for your accountant"
            panel.prompt = "Save"
            guard panel.runModal() == .OK, let url = panel.url else { return }
            do {
                try csv.write(to: url, atomically: true, encoding: .utf8)
                MekaHaptics.light()
                said = CoreModel.MoneySaid(line: "Saved the spreadsheet · open it in Numbers or Excel, or send it to your accountant", refused: false, undo: nil)
            } catch {
                MekaHaptics.tick()
                said = CoreModel.MoneySaid(line: "Couldn't save the spreadsheet there · try another place", refused: true, undo: nil)
            }
        }
    }

    /// Trading tracker, slice 12: SHOW IN · Dollars · Pounds (synced through the alerts setting) and what it means.
    private func tradingCurrency(_ c: TradingCurrencyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.xs) {
                Text(c.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                ForEach(c.chips, id: \.key) { ch in
                    Button {
                        guard !ch.on else { return }
                        let kind = ch.kind
                        let key = ch.key
                        Task { said = await model.setTradingAlert(kind: kind, key: key) }
                    } label: {
                        Text(ch.label).font(MekaType.caption).foregroundStyle(ch.on ? palette.accent : palette.textSecondary)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(ch.spoken)
                }
            }
            Text(c.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: c.line)
    }

    private func alertChips(_ label: String, _ chips: [TradingAlertChip]) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(label).font(MekaType.body).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(chips, id: \.key) { c in
                    Button {
                        guard !c.on else { return }
                        let kind = c.kind
                        let key = c.key
                        Task { said = await model.setTradingAlert(kind: kind, key: key) }
                    } label: {
                        Text(c.label).font(MekaType.caption).foregroundStyle(c.on ? palette.accent : palette.textSecondary)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(c.spoken)
                }
            }
        }
    }

    /// Trading tracker, slice 4: P&L · LAST 30 DAYS — the month's P&L (up in brass), its high and low, and the line day
    /// by day ending at Now (only the picked day's dot); click a day for its figure.
    private func tradingCurve(_ c: TradingCurveView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(c.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Text(c.summary).font(MekaType.body)
                .foregroundStyle(c.up?.boolValue == true ? palette.accent : palette.textPrimary)
                .contentTransition(.opacity)
                .accessibilityLabel(c.spoken)
            if let r = c.rangeLine { Text(r).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            MoneyWorthLine(points: c.points, palette: palette, reduced: reduceMotion, dots: false)
            Text(c.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, MekaSpace.m)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: c.summary)
    }

    /// Trading tracker, slice 2: YOUR TRADES · LAST 30 DAYS — trades closed and won, closed P&L, fees, funding and
    /// what's left after them (up in brass), the average win and loss, the best and worst trade, and each coin.
    private func trades(_ tr: TradingTradesView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(tr.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            if let e = tr.empty { Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            if let s = tr.summary {
                Text(s).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    .accessibilityLabel(tr.spoken)
            }
            ForEach(tr.lines, id: \.label) { r in
                HStack {
                    Text(r.label).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    Spacer()
                    Text(r.amount).font(MekaType.body)
                        .foregroundStyle(r.up?.boolValue == true ? palette.accent : palette.textPrimary)
                        .contentTransition(.opacity)
                }
            }
            if let l = tr.averageLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            if let l = tr.bestLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            ForEach(tr.coins, id: \.id) { c in
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text(c.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(c.amount).font(MekaType.body)
                            .foregroundStyle(c.up?.boolValue == true ? palette.accent : palette.textPrimary)
                            .contentTransition(.opacity)
                    }
                    Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(c.spoken)
                .transition(.opacity)
            }
            if let m = tr.moreLine { Text(m).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            Text(tr.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, MekaSpace.m)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tr.summary)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tr.coins.count)
    }

    /// Trading tracker, slice 9: last month in review — the month's result after fees and funding (up in brass) and
    /// plain sentences, facts only; a new month cross-fades in.
    private func tradingReview(_ r: TradingReviewView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(r.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack {
                Text(r.summary.components(separatedBy: " · ").first ?? r.summary).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                if let a = r.amount {
                    Text(a).font(MekaType.body)
                        .foregroundStyle(r.up?.boolValue == true ? palette.accent : palette.textPrimary)
                        .contentTransition(.opacity)
                }
            }
            if r.sentences.isEmpty { Text(r.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            ForEach(Array(r.sentences.enumerated()), id: \.offset) { _, line in
                Text(line).font(MekaType.body).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            Text(r.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, MekaSpace.m)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(r.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.summary)
    }

    private func importer(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("IMPORT A STATEMENT").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            if let e = v.empty {
                Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if !v.accounts.isEmpty {
                // Money bugs (A5): the account chips scroll sideways, each one line, never squashed.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(v.accounts, id: \.id) { a in
                            Button(a.name) { account = a.name }
                                .buttonStyle(MekaPressStyle()).font(MekaType.caption)
                                .lineLimit(1).fixedSize()
                                .foregroundStyle(a.name == account ? palette.accent : palette.textSecondary)
                        }
                    }
                }
            }
            TextField(MoneyRules.shared.ACCOUNT_HINT, text: $account).textFieldStyle(.roundedBorder)
            Button(busy ? "Reading the file…" : MoneyRules.shared.IMPORT + "…") { chooseFile() }
                .disabled(busy)
            if v.bank == nil {
                Text(MoneyRules.shared.CONNECT_LATER).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func chooseFile() {
        let name = account
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            MekaHaptics.tick()
            said = CoreModel.MoneySaid(line: MoneyRules.shared.NO_ACCOUNT, refused: true, undo: nil)
            return
        }
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.commaSeparatedText, .plainText, .text]
        panel.allowsMultipleSelection = false
        panel.message = "Pick the statement you downloaded from your bank (CSV) · read on this Mac"
        panel.prompt = "Import"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        busy = true
        Task {
            let text = (try? String(contentsOf: url, encoding: .utf8)) ?? (try? String(contentsOf: url, encoding: .isoLatin1))
            if let text {
                said = await model.importMoneyStatement(account: name, text: text)
            } else {
                MekaHaptics.tick()
                said = CoreModel.MoneySaid(line: "Couldn't read that file.", refused: true, undo: nil)
            }
            busy = false
        }
    }

    private func accounts(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("ACCOUNTS").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            ForEach(v.accounts, id: \.id) { a in
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(a.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(a.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    Spacer()
                    Button(removing == a.id ? "Remove it?" : "Remove") {
                        if removing != a.id { removing = a.id; MekaHaptics.tick(); return }
                        removing = nil
                        Task { said = await model.removeMoneyAccount(id: a.id) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption)
                    .foregroundStyle(removing == a.id ? palette.accent : palette.textTertiary)
                    .accessibilityLabel("Remove \(a.name) and its transactions")
                }
            }
        }
    }

    private func foot(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if !v.accounts.isEmpty || (v.worth?.rows.contains { $0.removable } ?? false) {
                if deleting {
                    Text(MoneyRules.shared.DELETE_ASK).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Button("Delete") { deleting = false; Task { said = await model.deleteAllMoney() } }
                        Button("Cancel") { deleting = false }
                    }
                } else {
                    Button(MoneyRules.shared.DELETE_ALL) { deleting = true; MekaHaptics.tick() }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
        }
        .padding(.top, MekaSpace.m)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: deleting)
    }
}

/// A category's share of the month as a thin bar that grows to it once (snaps with Reduce Motion).
private struct MoneyBar: View {
    let share: Double
    let lit: Bool
    let palette: MekaPalette
    let reduced: Bool
    @State private var shown: Double = 0

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(palette.surfaceRaised)
                Capsule().fill(lit ? palette.accent : palette.textTertiary)
                    .frame(width: g.size.width * min(max(shown, 0), 1))
            }
        }
        .frame(height: 4)
        .onAppear { withAnimation(reduced ? nil : MekaMotion.appear(reduced: false)) { shown = share } }
        .onChange(of: share) { _, s in withAnimation(reduced ? nil : MekaMotion.appear(reduced: false)) { shown = s } }
    }
}

/// Slice 8: net worth month by month — one brass 2 pt line with an 8 pt dot per month (the picked one filled) over a
/// hairline baseline, drawn left to right once (snaps with Reduce Motion); click near a month for its total.
private struct MoneyWorthLine: View {
    let points: [MoneyWorthPoint]
    let palette: MekaPalette
    let reduced: Bool
    /// A day-by-day line (the trading curve) shows only the picked point's dot.
    var dots: Bool = true
    @State private var drawn: CGFloat = 0
    @State private var picked: Int?

    var body: some View {
        let pick = min(picked ?? points.count - 1, points.count - 1)
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            GeometryReader { g in
                let pad: CGFloat = 6
                let lo = points.map { $0.pence }.min() ?? 0
                let hi = points.map { $0.pence }.max() ?? 0
                let span = Double(max(hi - lo, 1))
                let step = (g.size.width - 2 * pad) / CGFloat(max(points.count - 1, 1))
                let at: (Int) -> CGPoint = { i in
                    CGPoint(x: pad + CGFloat(i) * step,
                            y: pad + (g.size.height - 2 * pad) * CGFloat(1 - Double(points[i].pence - lo) / span))
                }
                ZStack(alignment: .topLeading) {
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: g.size.height - 0.5))
                        p.addLine(to: CGPoint(x: g.size.width, y: g.size.height - 0.5))
                    }
                    .stroke(palette.hairline, lineWidth: 1)
                    Path { p in
                        p.move(to: at(0))
                        for i in 1..<points.count { p.addLine(to: at(i)) }
                    }
                    .trim(from: 0, to: drawn)
                    .stroke(palette.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    ForEach(points.indices.filter { dots || $0 == pick }, id: \.self) { i in
                        Circle()
                            .strokeBorder(palette.accent, lineWidth: i == pick ? 4 : 2)
                            .background(Circle().fill(palette.surface))
                            .frame(width: 8, height: 8)
                            .position(at(i))
                            .opacity(drawn >= CGFloat(i) / CGFloat(max(points.count - 1, 1)) ? 1 : 0)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { loc in
                    let i = Int((loc.x / max(step, 1)).rounded())
                    picked = min(max(i, 0), points.count - 1)
                }
            }
            .frame(height: 96)
            .accessibilityElement()
            .accessibilityLabel(points.map { $0.said }.joined(separator: ", "))
            HStack {
                Text(points.first?.label ?? "").font(MekaType.micro).foregroundStyle(palette.textTertiary)
                Spacer()
                Text(points.last?.label ?? "").font(MekaType.micro).foregroundStyle(palette.textTertiary)
            }
            Text(points[pick].said).font(MekaType.caption).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduced), value: pick)
        }
        .onAppear {
            if reduced { drawn = 1 } else { withAnimation(MekaMotion.appear(reduced: false)) { drawn = 1 } }
        }
    }
}

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
    @State private var worthText = ""
    @State private var worthBalances: [String: String] = [:]

    var body: some View {
        let v = model.money ?? MoneyView.companion.LOCKED
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.locked ? MoneyRules.shared.LOCKED_MAC : v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            if v.locked {
                Button(MoneyRules.shared.UNLOCK) { unlock() }
                    .keyboardShortcut(.defaultAction)
                    .staggeredAppear(2)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        if let m = v.month { month(m, v).staggeredAppear(2) }
                        if let w = v.worth { worth(w).staggeredAppear(3) }
                        if let s = said { saidLine(s) }
                        importer(v).staggeredAppear(3)
                        if !v.accounts.isEmpty { accounts(v).staggeredAppear(4) }
                        foot(v)
                    }
                }
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

    private func unlock() {
        let model = self.model // the observable model (main actor), not the view, crosses into the callback
        MacBiometric.unlock(reason: MoneyRules.shared.UNLOCK_REASON) { ok in
            if ok { MekaHaptics.light(); model.openMoney() } else { MekaHaptics.tick() }
        }
    }

    private func month(_ m: MoneyMonth, _ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Button("‹") { model.moneyStep(-1) }.disabled(!v.earlier).accessibilityLabel("Month before")
                Text(m.label).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    .contentTransition(.opacity)
                Spacer()
                Button("›") { model.moneyStep(1) }.disabled(!v.later).accessibilityLabel("Month after")
            }
            Text(m.spentLine).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
            if let vs = m.vsLine {
                Text(vs).font(MekaType.caption).foregroundStyle(m.vsLit ? palette.accent : palette.textSecondary).contentTransition(.opacity)
            }
            if !v.alerts.isEmpty {
                Text(MoneyWatch.shared.head(n: Int32(v.alerts.count))).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs).contentTransition(.opacity)
                ForEach(v.alerts, id: \.id) { a in alert(a).transition(.opacity) }
                Text(MoneyWatch.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if let p = v.payday { payday(p).transition(.opacity) }
            if let c = v.cards, !(c.rows.isEmpty && c.offers.isEmpty) { cards(c).transition(.opacity) }
            if let r = v.refunds {
                Text(r.head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs).contentTransition(.opacity)
                ForEach(r.rows, id: \.id) { row in refund(row).transition(.opacity) }
                if let f = r.foot { Text(f).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            }
            if !v.tips.isEmpty {
                Text("TIPS FROM YOUR NUMBERS").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                ForEach(v.tips, id: \.id) { t in tip(t).transition(.opacity) }
            }
            if let h = v.hiddenTips {
                HStack {
                    Text(h).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity)
                    Spacer()
                    Button(MoneyTips.shared.SHOW_AGAIN) { Task { said = await model.showMoneyTips() } }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            if let r = v.regular {
                Text("REGULAR PAYMENTS").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                Text(r.head).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                ForEach(r.rows, id: \.key) { row in regular(row).transition(.opacity) }
            }
            if let f = v.forecast { forecast(f).transition(.opacity) }
            if !m.categories.isEmpty {
                Text("BY CATEGORY").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                ForEach(m.categories, id: \.key) { c in category(c).transition(.opacity) }
            }
            if !m.txns.isEmpty {
                Text("PAYMENTS · CLICK ONE TO CHANGE ITS CATEGORY").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                ForEach(m.txns, id: \.id) { t in txn(t) }
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
            ForEach(cv.offers.prefix(4), id: \.id) { o in
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
            if !cv.rows.isEmpty {
                Text(cv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// Slice 7: one card — due with Paid, statement, limit used, 0% ending, fees; Edit details for the typed fields.
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
                ForEach(c.fields, id: \.key) { f in
                    TextField(f.value.isEmpty ? f.hint : "\(f.label) · now \(f.value)", text: Binding(
                        get: { cardTexts[c.id + "|" + f.key] ?? "" },
                        set: { cardTexts[c.id + "|" + f.key] = $0 }
                    ))
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        let key = c.id + "|" + f.key
                        let text = cardTexts[key] ?? ""
                        cardTexts[key] = nil
                        let id = c.id
                        let field = f.key
                        Task { said = await model.moneyCardField(accountId: id, field: field, text: text) }
                    }
                }
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
                        ForEach(f.cuts, id: \.key) { c in
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
                    }
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

    private func txn(_ t: MoneyTxnRow) -> some View {
        Menu {
            ForEach(MoneyRules.shared.CHOICES, id: \.key) { choice in
                Button(choice.key == t.category ? "✓ \(choice.label)" : choice.label) {
                    guard choice.key != t.category else { return }
                    Task { said = await model.setMoneyCategory(txnId: t.id, category: choice.key) }
                }
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text(t.merchant).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text("\(t.day) · \(t.categoryLabel)\(t.taught ? " · yours" : "") · \(t.account)")
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
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

    private func importer(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("IMPORT A STATEMENT").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            if let e = v.empty {
                Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if !v.accounts.isEmpty {
                HStack {
                    ForEach(v.accounts.prefix(4), id: \.id) { a in
                        Button(a.name) { account = a.name }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption)
                            .foregroundStyle(a.name == account ? palette.accent : palette.textSecondary)
                    }
                }
            }
            TextField(MoneyRules.shared.ACCOUNT_HINT, text: $account).textFieldStyle(.roundedBorder)
            Button(busy ? "Reading the file…" : MoneyRules.shared.IMPORT + "…") { chooseFile() }
                .disabled(busy)
            Text(MoneyRules.shared.CONNECT_LATER).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
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
                    ForEach(points.indices, id: \.self) { i in
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

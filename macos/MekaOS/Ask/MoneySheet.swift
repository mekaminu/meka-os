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
    /// Ask Obi (slice 1): the money chat's popover, its turns while Money is open (never stored), its Try questions,
    /// whether an answer is on its way, and the next turn's id.
    @State private var chatOpen = false
    /// Ask Obi, slice 3: the popover was opened from the bar's mic (Dictation starts in its field).
    @State private var chatListen = false
    @State private var chatTurns: [CoreModel.MoneyChatLine] = []
    @State private var chatTry: [String] = []
    @State private var chatWorking = false
    @State private var chatNext = 0
    @State private var deleting = false
    @State private var busy = false
    @State private var extra = ""
    @State private var balance = ""
    /// Money review (Meka 2026-10-26 22:12, Bills): the card whose popover is open, and whether Until payday's is.
    @State private var editingCard: String?
    @State private var paydayOpen = false
    @State private var cardTexts: [String: String] = [:]
    @State private var worthText = ""
    @State private var sharedText = ""
    @State private var funText = ""
    @State private var capText = ""
    @State private var tradingText = ""
    /// Layout rules on Trading (d): every open position, not only the first five.
    @State private var tradingAll = false
    /// The equity card's range (1D · 1W · 1M): this Mac's choice, the month first as the mockup.
    @State private var tradingRange: String = TradingEquity.shared.DEFAULT
    @State private var exporting = false
    /// Layout rule (c): the ⓘ whose popover is open ("review" or "foot").
    @State private var infoOpen: String? = nil
    /// Plan's goals card: the goal whose popover is open.
    @State private var goalOpenId: String? = nil
    /// Net worth's typed things (Money review, layout rule (e)): whether their popover is open.
    @State private var worthOtherOpen = false
    /// Trading's layout rule (e): the add-an-address popover with the field.
    @State private var tradingAddOpen = false
    /// Trading's ALERTS as one row (README item 5): whether its popover with the choices is open.
    @State private var tradingAlertsOpen = false
    /// Slice 18: the year in review opened or folded by him, per year (else January's finished year opens by itself).
    @State private var yearOpen: [Int32: Bool] = [:]
    /// Money redesign (B), slice 1: the tab shown (Spending · Bills · Plan · Trading · Accounts); opens on Spending.
    @State private var tab: MoneyTab = MoneyTabs.shared.FIRST
    /// Money redesign (B2): the category picked on Spending's ring, and whether all categories / all payments show.
    @State private var focus: String?
    /// Needs and wants: Spending's view chips (Categories · Needs vs wants) swap the ring and the rows.
    @State private var needsView = false
    @State private var allCats = false
    @State private var allPays = false
    /// The payment sheet: the payment whose popover is open.
    @State private var sheetTxn: String?
    /// His own categories: the New category form in the payment's popover, its name, colour (a tone) and icon (by name).
    @State private var makingCategory = false
    @State private var newCategoryName = ""
    @State private var newCategoryTone: Int32 = 0
    @State private var newCategoryIcon = "DOTS"
    /// Slice 3: the category of his whose popover is open (Accounts → YOUR CATEGORIES), and whether Delete is asking.
    @State private var ownSheet: String?
    @State private var askingDelete = false
    /// His categories in caps (2026-10-27): what he is typing in a category's MONTHLY CAP field.
    @State private var ownCapText = ""
    /// Money review (Meka 2026-10-26 22:12): Worth a look's open row, whether all its rows show, and the ones put away
    /// while the write runs.
    @State private var openLook: String?
    @State private var allLooks = false
    /// Layout rules on Bills: "See all" on the bills card, "2 more" on the refunds, and the refund whose popover is open.
    @State private var allBills = false
    @State private var allRefunds = false
    /// Layout rules on Plan: "2 more" on the tips, the tip whose popover is open, and the What if… and Set a challenge
    /// popovers.
    /// Obi's money check: "2 more" on the ideas and the idea whose popover is open.
    @State private var allIdeas = false
    /// Obi's wording: true while Ask Obi to look again is on its way.
    @State private var obiLooking = false
    @State private var ideaOpenId: String? = nil
    @State private var whatIfOpen = false
    @State private var setChallengeOpen = false
    @State private var refundOpenId: String? = nil
    @State private var finedLooks: Set<String> = []
    /// Money review: the quick sorter's popover, the payees skipped and the ones picked while the write runs.
    @State private var sorting = false
    @State private var sortSkipped: Set<String> = []
    @State private var sortDone: Set<String> = []
    // Money review, Accounts: the account whose popover is open, its balance field, Remove's ask, and Import's popover.
    @State private var accountSheet: String?
    @State private var accountBalance = ""
    @State private var askingRemove = false
    /// Accounts' rest: the MORE row whose popover is open, and the Name field in an account's popover.
    @State private var moreOpen: MoneyMoreKind?
    @State private var accountName = ""

    var body: some View {
        let v = model.money ?? MoneyView.companion.LOCKED
        // Money on the Mac as the mockup (Mac-*.dc.html): once open, a wider window with the tabs down the side and each
        // tab's body in two columns (core MoneyColumns, the same split as the open Fold); locked, the narrow sheet.
        let wide = !v.locked && !v.skeleton
        HStack(alignment: .top, spacing: 0) {
            if wide { sideTabs(v).transition(.opacity) }
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                // Money's top as the mockup (MoneyHeader): "Money" with the Updated pill on its right (a click asks the banks
                // for a sync); the line under the title only when locked or nothing is in yet.
                HStack(alignment: .center, spacing: MekaSpace.xs) {
                    Text(MoneyRules.shared.TITLE).font(MekaType.upNextTitle)
                    Spacer(minLength: MekaSpace.xs)
                    if let pill = MoneyHeader.shared.pill(bank: v.bank), !v.locked {
                        MoneyUpdatedPill(text: pill, palette: palette, reduced: reduceMotion) {
                            MekaHaptics.tick()
                            Task { said = await model.moneyBankRefresh() }
                        }
                    }
                }
                .staggeredAppear(0)
                if MoneyHeader.shared.showsSummary(v: v) {
                    Text(v.locked && v.summary == MoneyRules.shared.LOCKED ? MoneyRules.shared.LOCKED_MAC : v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                        .staggeredAppear(1)
                }
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
                    // Ask Obi: the bar on every tab opens Obi · money.
                    MoneyAskBarView(palette: palette, onMic: { openChat(listen: true) }) { openChat(listen: false) }
                    .frame(maxWidth: MoneyMacLayout.askWidth, alignment: .leading)
                    .staggeredAppear(2)
                    .popover(isPresented: $chatOpen, arrowEdge: .bottom) { chatPanel(v) }
                    ScrollView {
                        VStack(alignment: .leading, spacing: MekaSpace.m) {
                            HStack(alignment: .top, spacing: MoneyMacLayout.gap) {
                                parts(v, .start).frame(maxWidth: .infinity, alignment: .topLeading)
                                parts(v, .end).frame(maxWidth: .infinity, alignment: .topLeading)
                            }
                            // Full width under the columns: the empty line, the said line and the tab's foot.
                            parts(v, .full)
                        }
                        .id(tab.name)
                        .transition(.opacity)
                    }
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: tab.name)
                    .scrollIndicators(.never)
                    .frame(maxHeight: MoneyMacLayout.scrollHeight)
                }
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }.keyboardShortcut(v.locked ? .cancelAction : .defaultAction)
                }
                .padding(.top, MekaSpace.m)
            }
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .padding(MekaSpace.l)
        }
        // The side list's darker strip runs the sheet's full height (a background, so it never stretches the sheet).
        .background(alignment: .leading) {
            if wide {
                palette.background.frame(width: MoneyMacLayout.sideWidth)
                    .overlay(alignment: .trailing) { Rectangle().fill(palette.hairline).frame(width: 1) }
            }
        }
        .frame(width: wide ? MoneyMacLayout.wideWidth : MoneyMacLayout.narrowWidth)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wide)
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

    /// Money on the Mac as the mockup (Mac-*.dc.html): the tabs down the side under a small MONEY label instead of pills;
    /// the chosen one is lit and blends there; a click gives a tick. Bills and Plan step back to this month
    /// (MoneyTabs.stepOnPick).
    private func sideTabs(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            MoneyLabel(MoneyColumns.shared.SIDE_HEAD, palette: palette)
                .padding(.horizontal, MekaSpace.xs).padding(.bottom, MekaSpace.xxs)
                .accessibilityAddTraits(.isHeader)
            ForEach(MoneyTabs.shared.ALL, id: \.name) { t in
                let lit = t == tab
                Button {
                    guard !lit else { return }
                    MekaHaptics.tick()
                    tab = t
                    if let step = MoneyTabs.shared.stepOnPick(tab: t, v: v) { model.moneyStep(step.int32Value) }
                } label: {
                    // As the mockup: the chosen tab has the brass tint and every label is semibold.
                    Text(t.label).font(MekaType.upNextCardTitle).lineLimit(1)
                        .foregroundStyle(lit ? palette.accent : palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xs)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(lit ? palette.accent.opacity(0.16) : Color.clear))
                        .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(MoneyTabs.shared.spoken(tab: t, chosen: lit))
                .accessibilityAddTraits(lit ? [.isSelected] : [])
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, MekaSpace.l).padding(.horizontal, MekaSpace.xs)
        .frame(width: MoneyMacLayout.sideWidth)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tab.name)
    }

    /// Whether the part keyed [key] (the Fold list's key, so both apps split alike) belongs in column [col].
    private func on(_ key: String, _ col: MoneyColumn) -> Bool { MoneyColumns.shared.of(key: key) == col }

    /// The chosen tab's parts that sit in column [col]: the left or right column, or (FULL) what spans both under them.
    private func parts(_ v: MoneyView, _ col: MoneyColumn) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let e = MoneyTabs.shared.empty(tab: tab, v: v), on("tab-empty-\(tab.name)", col) {
                Text(e).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).transition(.opacity)
            }
            if let b = v.bank, has(.bank, v), on("bank", col) { bank(b).staggeredAppear(2) }
            if let t = v.trading, has(.trading, v) { trading(t, col).staggeredAppear(2) }
            if let m = v.month, tabOfMonth { month(m, v, col).staggeredAppear(2) }
            if let g = v.goals, has(.goals, v), on("goals", col) { moneyGoals(g).staggeredAppear(3) }
            if let ch = v.challenges, has(.challenges, v), on("challenges", col) { challenges(ch).staggeredAppear(3) }
            if let tv = v.travel, has(.travel, v), on("travel", col) { travel(tv).transition(.opacity) }
            // Layout rule (c): Plan says "read-only · not financial advice" once, at its foot; how each part is worked
            // out behind ⓘ.
            if has(.planFoot, v), on("plan-foot", col), let lines = MoneyTabs.shared.info(tab: MoneyTab.plan, v: v) {
                HStack(spacing: MekaSpace.xs) {
                    Text(MoneyTabs.shared.FOOT_SHORT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    infoButton("foot-plan", title: MoneyTabs.shared.INFO_TITLE, lines: lines)
                }
                .padding(.top, MekaSpace.m)
            }
            if let w = v.worth, has(.worth, v), on("worth", col) { worth(w).staggeredAppear(3) }
            // Money review: the accounts as one clean list under net worth, each opening its popover.
            if has(.accounts, v), on("accounts", col) { accounts(v).staggeredAppear(3) }
            // Money review: the year in review lives on Accounts, under net worth.
            if let yv = v.year, has(.year, v), on("year", col) { year(yv).transition(.opacity) }
            if has(.ownCategories, v), on("own", col) { ownCategories(v).staggeredAppear(3) }
            // Accounts' rest (mockup): Side hustle and tax, Shared costs, and Import, export and delete as one card of
            // rows, each opening its popover.
            if has(.more, v), on("more", col) { more(v).staggeredAppear(3) }
            if let s = said, on("said", col) { saidLine(s) }
            if has(.foot, v), on("foot", col) { foot(v) }
        }
    }

    private func unlock() {
        let model = self.model // the observable model (main actor), not the view, crosses into the callback
        MacBiometric.unlock(reason: MoneyRules.shared.UNLOCK_REASON) { ok in
            if ok { MekaHaptics.light(); model.openMoney() } else { model.moneyUnlockRefused(); MekaHaptics.tick() }
        }
    }

    /// The month's parts that sit in column [col] (the Fold list's keys decide, as MoneyColumns says).
    private func month(_ m: MoneyMonth, _ v: MoneyView, _ col: MoneyColumn) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if has(.month, v), on("month", col) {
            // As the mockup: ‹ October 2026 › centred over the ring.
            HStack(spacing: MekaSpace.xs) {
                Spacer(minLength: 0)
                Button("‹") { model.moneyStep(-1) }.disabled(!v.earlier).accessibilityLabel("Month before")
                Text(m.label).font(MekaType.upNextCardTitle).foregroundStyle(palette.textPrimary)
                    .frame(minWidth: 120)
                    .contentTransition(.opacity)
                Button("›") { model.moneyStep(1) }.disabled(!v.later).accessibilityLabel("Month after")
                Spacer(minLength: 0)
            }
            if let full = m.spending {
                // Needs and wants: the ring as the view chips (over the category rows, MoneyHeader) have it.
                let sp = MoneyNeeds.shared.shown(v: full, needs: needsView)
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
            if let b = v.travel?.banner, has(.travelBanner, v), on("travel-banner", col) {
                // Slice 17: abroad, or a trip near — the card to use leads this month.
                Text(b).font(MekaType.body).foregroundStyle(palette.accent).contentTransition(.opacity)
                    .fixedSize(horizontal: false, vertical: true).padding(.top, MekaSpace.xxs)
            }
            if let rv = v.review, has(.review, v), on("review", col) { review(rv).transition(.opacity) }
            if has(.alerts, v), on("alerts-head", col) {
                Text(MoneyWatch.shared.head(n: Int32(v.alerts.count))).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs).contentTransition(.opacity)
                // Money review: compact one-line rows, three then "2 more ›"; a click opens one with That's fine.
                let looks = MoneyLook.shared.rows(alerts: v.alerts).filter { !finedLooks.contains($0.id) }
                ForEach(MoneyLook.shared.shown(rows: looks, open: allLooks), id: \.id) { r in lookRow(r).transition(.opacity) }
                if let more = MoneyLook.shared.moreLabel(rows: Int32(looks.count), open: allLooks) {
                    Button(more) { MekaHaptics.tick(); allLooks.toggle() }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            // Money redesign (B3): the Bills tab's card, then the month's bills by date (to come first, paid greyed).
            if let b = v.bills, has(.bills, v) {
                if on("bills-card", col) {
                    MoneyBillsCardView(bills: b, palette: palette, reduced: reduceMotion).padding(.top, MekaSpace.xs).transition(.opacity)
                }
                // Layout rules (a) and (d): the month's bills in one card, five then "See all N ›"; where they're found
                // from is behind the tab's ⓘ at the foot.
                if !b.rows.isEmpty, on("bills-list", col) {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(MoneyBills.shared.shownRows(v: b, open: allBills), id: \.key) { r in
                            MoneyBillRowView(row: r, palette: palette).transition(.opacity)
                        }
                        if let more = MoneyBills.shared.allLabel(rows: Int32(b.rows.count), open: allBills) {
                            Button(more) { MekaHaptics.tick(); allBills.toggle() }
                                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        }
                    }
                    .moneyCard(palette)
                }
            }
            if let p = v.payday, has(.payday, v), on("payday", col) { paydayRow(p, withRows: MoneyTabs.shared.paydayRows(v: v)).transition(.opacity) }
            if let c = v.cards, has(.cards, v), on("cards", col) { cards(c).transition(.opacity) }
            if let r = v.refunds, has(.refunds, v), on("refunds", col) {
                MoneyLabel(r.head, palette: palette)
                    .padding(.top, MekaSpace.xs).contentTransition(.opacity)
                // Layout rules (b) and (d): compact rows (what · its state · ›), three then "2 more ›"; a click opens the
                // refund's popover with Close it or the chase to copy.
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    ForEach(MoneyRefunds.shared.shown(rows: r.rows, open: allRefunds), id: \.id) { row in
                        refundRow(row, foot: r.foot).transition(.opacity)
                    }
                    if let more = MoneyRefunds.shared.moreLabel(rows: Int32(r.rows.count), open: allRefunds) {
                        Button(more) { MekaHaptics.tick(); allRefunds.toggle() }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
                .moneyCard(palette)
            }
            // Obi's money check as the mockup (README item 6): a brass-bordered head card with its label inside, the yearly
            // total large and "Written by Obi · …" with Ask Obi to look again; the lead idea as its own card (why, its step,
            // Remind me, Not for me; a click on its title opens the payments behind it); then the next ideas as one-line
            // rows, "1 more ›", and "2 put away" with Show them again.
            if let c = v.check, has(.tips, v), on("check", col) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    MoneyLabel(MoneyCheck.shared.HEAD, palette: palette, color: palette.accent)
                        .accessibilityAddTraits(.isHeader)
                    HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xs) {
                        if let t = c.total {
                            Text(t).font(MekaType.greeting).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                        }
                        Text(c.from).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    if let e = c.empty {
                        Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    // Obi's wording: "Written by Obi · Sun 25 Oct" (or that he's reading them) and Ask Obi to look again.
                    HStack {
                        Text(obiLooking ? MoneyCheckWords.shared.LOOKING : (c.by ?? ""))
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity)
                        Spacer()
                        if !obiLooking {
                            Button(MoneyCheckWords.shared.LOOK_AGAIN) {
                                MekaHaptics.tick()
                                obiLooking = true
                                Task {
                                    said = await model.moneyCheckWords()
                                    obiLooking = false
                                }
                            }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        }
                    }
                }
                .moneyBrassCard(palette)
                .padding(.top, MekaSpace.xs)
                if let lead = MoneyCheck.shared.lead(ideas: c.ideas) {
                    leadIdeaCard(lead).id(lead.id).transition(.opacity)
                }
                let rest = MoneyCheck.shared.rest(ideas: c.ideas, open: allIdeas)
                let more = MoneyCheck.shared.more(n: Int32(c.ideas.count), open: allIdeas)
                if !rest.isEmpty || more != nil || c.away != nil {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        ForEach(rest, id: \.id) { i in ideaRow(i).transition(.opacity) }
                        if let more {
                            Button(more) { MekaHaptics.tick(); allIdeas.toggle() }
                                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        }
                        if let h = c.away {
                            HStack {
                                Text(h).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity)
                                Spacer()
                                Button(MoneyCheck.shared.SHOW_AGAIN) { Task { said = await model.moneyIdeasShow() } }
                                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                            }
                        }
                    }
                    .moneyCard(palette)
                }
            }
            // Money redesign, Plan (mockup): What if I save… — worked out on this Mac as he slides, Make it a goal.
            if let b = v.save, has(.save, v), on("save", col) {
                MoneyLabel(MoneySave.shared.HEAD, palette: palette).padding(.top, MekaSpace.xs)
                MoneySaveCardView(base: b, palette: palette, reduced: reduceMotion) { amount, months, rate in
                    Task { said = await model.moneySaveGoal(amountPence: amount, months: months, rateBp: rate) }
                }
                .transition(.opacity)
            }
            // Money redesign, Plan (mockup): Clear my cards — one amount a month across his cards, worked out here as he slides.
            if let b = v.clear, has(.clear, v), on("clear", col) {
                MoneyLabel(MoneyClear.shared.HEAD, palette: palette).padding(.top, MekaSpace.xs)
                MoneyClearCardView(base: b, palette: palette, reduced: reduceMotion) { pay, order in
                    Task { said = await model.moneyClearGoal(payPence: pay, order: order) }
                }
                .transition(.opacity)
            }
            if let r = v.regular, has(.regular, v), on("regular", col) {
                Text("REGULAR PAYMENTS").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                Text(r.head).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                ForEach(r.rows, id: \.key) { row in regular(row).transition(.opacity) }
            }
            // Layout rule (c): Bills says "read-only · not financial advice" once, at its foot; where it's from behind ⓘ.
            if has(.billsFoot, v), on("bills-foot", col), let lines = MoneyTabs.shared.info(tab: MoneyTab.bills, v: v) {
                HStack(spacing: MekaSpace.xs) {
                    Text(MoneyTabs.shared.FOOT_SHORT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    infoButton("foot-bills", title: MoneyTabs.shared.INFO_TITLE, lines: lines)
                }
                .padding(.top, MekaSpace.m)
            }
            if let f = v.forecast, has(.forecast, v), on("forecast", col) { forecast(f).transition(.opacity) }
            if has(.categories, v), on("cats-head", col), let full = m.spending {
                let sp = MoneyNeeds.shared.shown(v: full, needs: needsView)
                if MoneyHeader.shared.chipsInHead(sp: full) {
                    // As the mockup: Categories · Needs vs wants on the row over the rows, with "All N ›".
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(MoneyNeeds.shared.views(needs: needsView), id: \.label) { c in
                            let wantsNeeds = c.needs
                            sheetPill(c.label, on: c.on, spoken: c.spoken) {
                                guard wantsNeeds != needsView else { return }
                                MekaHaptics.tick()
                                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { needsView = wantsNeeds; focus = nil }
                            }
                        }
                        Spacer(minLength: 0)
                        if let l = MoneySpending.shared.allLabel(rows: Int32(sp.rows.count), open: allCats) {
                            Button(l) { allCats.toggle() }.buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        }
                    }
                    .padding(.top, MekaSpace.xs)
                } else {
                    MoneyHeadWithLink(head: "WHERE IT WENT", link: MoneySpending.shared.allLabel(rows: Int32(sp.rows.count), open: allCats), palette: palette) {
                        allCats.toggle()
                    }
                }
                // Layout rule (a): the category rows in one card under their label.
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(MoneySpending.shared.shownRows(v: sp, open: allCats), id: \.key) { r in
                        let key = r.key
                        MoneySpendRowView(row: r, picked: focus == key, palette: palette, reduced: reduceMotion) {
                            MekaHaptics.tick()
                            focus = focus == key ? nil : key
                        }
                        .transition(.opacity)
                    }
                }
                .moneyCard(palette)
            }
            if let u = m.unsorted, has(.unsorted, v), on("unsorted", col) { unsortedRow(u) }
            if has(.categories, v), on("cats-head", col), m.spending == nil {
                Text("BY CATEGORY").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                ForEach(m.categories, id: \.key) { c in category(c).transition(.opacity) }
            }
            if has(.payments, v), on("latest-head", col), let sp = m.spending, !allPays {
                MoneyHeadWithLink(head: MoneySpending.shared.LATEST_HEAD, link: sp.seeAll, palette: palette) { allPays = true }
                // Layout rule (a): the latest payments, by day, in one card.
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(sp.latest, id: \.label) { d in
                        MoneyDayHead(day: d, palette: palette)
                        ForEach(d.rows, id: \.id) { t in txn(t, chip: sp, showDay: false) }
                    }
                }
                .moneyCard(palette)
            } else if has(.payments, v), on("txns-head", col) {
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
            // Layout rule (c): "read-only · not financial advice" once, at the foot of Spending; where it's from behind ⓘ.
            if has(.spendFoot, v), on("spend-foot", col), let lines = MoneyTabs.shared.info(tab: MoneyTab.spending, v: v) {
                HStack(spacing: MekaSpace.xs) {
                    Text(MoneyTabs.shared.FOOT_SHORT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    infoButton("foot", title: MoneyTabs.shared.INFO_TITLE, lines: lines)
                }
                .padding(.top, MekaSpace.m)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: m.label)
    }

    /// Slice 6: payday, what's still to go out before it, the pay account's balance (typed here or from a statement)
    /// and what's safe to spend, lit when it's tight.
    /// Money review (Meka 2026-10-26 22:12, Bills): Until payday as one row (payday over what's safe to spend); a click
    /// opens the rest as a popover, so the balance field never sits open on the page.
    private func paydayRow(_ p: MoneyPaydayView, withRows: Bool) -> some View {
        let b = MoneyPayday.shared.brief(p: p)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(p.head, palette: palette).padding(.top, MekaSpace.xs)
            Button {
                MekaHaptics.tick()
                said = nil
                paydayOpen = true
            } label: {
                HStack(spacing: MekaSpace.m) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(b.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1).contentTransition(.opacity)
                        if let l = b.line {
                            Text(l).font(MekaType.caption).foregroundStyle(b.lit ? palette.accent : palette.textSecondary)
                                .lineLimit(2).contentTransition(.opacity)
                        }
                    }
                    Spacer()
                    Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
                }
                .padding(MekaSpace.m)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(b.spoken)
            .popover(isPresented: $paydayOpen, arrowEdge: .bottom) {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("Until payday").font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                    payday(p, withRows: withRows)
                    if let s = said {
                        Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                    }
                    Button(MoneyPayday.shared.DONE) { paydayOpen = false }.keyboardShortcut(.defaultAction)
                }
                .padding(MekaSpace.m)
                .frame(width: 380)
            }
        }
    }

    private func payday(_ p: MoneyPaydayView, withRows: Bool) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let n = p.notReady {
                Text(n).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            if let l = p.payLine { Text(l).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity) }
            if let f = p.fromLine { Text(f).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            if let s = p.sinceLine { Text(s).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity) }
            // Money redesign (B3): with the Bills card above, its list holds these rows.
            if withRows, let g = p.goingOut { Text(g).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity) }
            ForEach(withRows ? p.rows : [], id: \.key) { r in
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
            if withRows && p.more > 0 { Text("+ \(p.more) more").font(MekaType.caption).foregroundStyle(palette.textTertiary) }
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

    /// Slice 7: his cards as one card of one-line rows (Money review: "due ? · click to add"); a click opens the card's
    /// popover with its details, Paid and the fields.
    private func cards(_ cv: MoneyCardsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(cv.head, palette: palette).padding(.top, MekaSpace.xs)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(MoneyCards.shared.briefs(cv: cv), id: \.id) { b in
                    let id = b.id
                    Button {
                        MekaHaptics.tick()
                        said = nil
                        editingCard = id
                    } label: {
                        HStack(spacing: MekaSpace.m) {
                            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                Text(b.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text(b.line).font(MekaType.caption).foregroundStyle(b.lit ? palette.accent : palette.textSecondary)
                                    .lineLimit(1).contentTransition(.opacity)
                            }
                            Spacer()
                            if let a = b.amount { Text(a).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary) }
                            Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(b.spoken)
                    .popover(isPresented: Binding(get: { editingCard == id }, set: { if !$0 && editingCard == id { editingCard = nil } }), arrowEdge: .trailing) {
                        cardSheet(id)
                    }
                }
            }
            .padding(MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous))
        }
    }

    /// The card's popover: found again in each view (Not a card closes it), its details and a Done button.
    @ViewBuilder
    private func cardSheet(_ id: String) -> some View {
        if let c = model.money?.cards?.rows.first(where: { $0.id == id }) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(c.name).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                card(c)
                if let s = said {
                    Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                }
                Text(MoneyCards.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                Button(MoneyCards.shared.DONE) { editingCard = nil }.keyboardShortcut(.defaultAction)
            }
            .padding(MekaSpace.m)
            .frame(width: 380)
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
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: 0) {
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
            if c.canPay {
                Button(MoneyCards.shared.PAID) {
                    let id = c.id
                    Task { said = await model.moneyCardPaid(accountId: id) }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
            }
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
                    editingCard = nil
                    Task { said = await model.moneyCardKind(accountId: id, card: false, charge: false) }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Slice 15: CHALLENGES — the No-spend days switch and, when on, the month's days as dots (brass = no spending) with
    /// the count and the streak; fun money for the week and each category cap as a bar (brass from 90%) with what's left
    /// and the run (Remove); fields to set them.
    private func challenges(_ ch: MoneyChallengesView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(ch.head, palette: palette).padding(.top, MekaSpace.xs)
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
            // Layout rules on Plan (e): fun money and caps are set in a popover, not in fields on the page.
            linkRow(MoneyPlanLayout.shared.SET_CHALLENGE, line: MoneyPlanLayout.shared.SET_CHALLENGE_LINE, lit: false) { setChallengeOpen = true }
                .popover(isPresented: $setChallengeOpen, arrowEdge: .trailing) {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(MoneyPlanLayout.shared.SET_CHALLENGE_SHEET).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        challengeFields(ch)
                        if let s = said { Text(s.line).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                        Button(MoneyCards.shared.DONE) { setChallengeOpen = false }.keyboardShortcut(.defaultAction)
                    }
                    .padding(MekaSpace.m)
                    .frame(width: 360)
                }
        }
        .moneyCard(palette)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: ch.caps.count)
    }

    /// Layout rules on Plan (e): the two challenge fields, only in the Set a challenge popover.
    @ViewBuilder
    private func challengeFields(_ ch: MoneyChallengesView) -> some View {
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
    }

    /// Money redesign, Plan's goals card (mockup), like the Fold's MoneyGoalsCard: GOALS as one card, each £ goal's name
    /// and "£240 of £600", a bar in its own colour and its pace note (amber when behind). A click opens the goal's
    /// popover, where it can follow an account's balance (the chips were open on the page before). The bar glides and
    /// the lines cross-fade (snap with Reduce Motion).
    private func moneyGoals(_ gv: MoneyGoalsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(gv.head, palette: palette).padding(.top, MekaSpace.xs)
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                if gv.rows.isEmpty {
                    Text(gv.empty ?? MoneyGoals.shared.EMPTY).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ForEach(gv.rows, id: \.id) { r in goalRow(r) }
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.l))
        }
    }

    private func goalRow(_ r: MoneyGoalRow) -> some View {
        let id = r.id
        return Button {
            MekaHaptics.tick()
            said = nil
            goalOpenId = id
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(spacing: MekaSpace.xs) {
                    Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    Spacer()
                    Text(r.amount).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                    Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
                }
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(palette.surfaceRaised)
                        Capsule().fill(palette.moneyTone(r.tone)).frame(width: geo.size.width * CGFloat(min(max(r.share, 0), 1)))
                    }
                }
                .frame(height: 6)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: r.share)
                Text(r.note).font(MekaType.caption).foregroundStyle(r.behind ? palette.money3 : palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .popover(isPresented: Binding(get: { goalOpenId == id }, set: { if !$0 && goalOpenId == id { goalOpenId = nil } }), arrowEdge: .trailing) {
            goalPopover(id)
        }
    }

    /// A goal's popover: where it stands and its pace, then a chip per account to follow (the chosen one in brass; click
    /// it again to stop) and the foot. Found again in each view; it closes when the goal goes.
    @ViewBuilder
    private func goalPopover(_ id: String) -> some View {
        if let gv = model.money?.goals, let r = gv.rows.first(where: { $0.id == id }) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(r.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(r.line).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                Text(r.note).font(MekaType.caption).foregroundStyle(r.behind ? palette.money3 : palette.textSecondary)
                Text(MoneyGoals.shared.FOLLOW).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                Text(r.follows ?? MoneyGoals.shared.NOT_FOLLOWING).font(MekaType.caption)
                    .foregroundStyle(r.follows != nil ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                if r.choices.isEmpty {
                    Text(MoneyGoals.shared.NO_ACCOUNTS).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ScrollView(.horizontal) {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(r.choices, id: \.accountId) { c in
                            Button {
                                let aid: String? = c.picked ? nil : c.accountId
                                MekaHaptics.tick()
                                Task { said = await model.moneyGoalFollow(goalId: id, accountId: aid) }
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
                Text(gv.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                if let s = said { saidLine(s) }
                HStack {
                    Spacer()
                    Button(MoneyGoals.shared.DONE) { goalOpenId = nil }.keyboardShortcut(.defaultAction)
                }
            }
            .padding(MekaSpace.m)
            .frame(width: 360)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: r.follows)
        }
    }

    /// Slice 17: PAYING ABROAD — the tip (brass when a card had no foreign fees, or he's going), then each account with
    /// a brass dot by one that charged none. Lines cross-fade as they change.
    private func travel(_ tv: MoneyTravelView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(tv.head, palette: palette).padding(.top, MekaSpace.xs)
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
        }
        .moneyCard(palette)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tv.rows.count)
    }

    /// Money manager (7): the month in review — a few plain sentences (the first in brass when more went out than came
    /// in), then where it's from; the finished month shown, or last month leading this one for its first days.
    /// Layout rules (Meka 2026-10-26 22:18): one card of Spent · Came in · Left and at most two short lines; the
    /// sentences and where they came from behind its ⓘ (a popover).
    private func review(_ rv: MoneyMonthReviewView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                MoneyLabel(rv.head, palette: palette)
                Spacer()
                infoButton("review", title: rv.infoTitle, lines: rv.info)
            }
            if !rv.tiles.isEmpty { MoneyTilesView(tiles: rv.tiles, palette: palette) }
            ForEach(rv.lines, id: \.self) { l in
                Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
            }
        }
        .moneyCard(palette)
        .padding(.top, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: rv.month)
    }

    /// Layout rule (c): a small ⓘ whose popover says [lines] under [title].
    private func infoButton(_ id: String, title: String, lines: [String]) -> some View {
        Button { MekaHaptics.tick(); infoOpen = id } label: {
            Text(MoneyTabs.shared.INFO).font(MekaType.body).foregroundStyle(palette.textTertiary)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(id.hasPrefix("foot") ? MoneyTabs.shared.INFO_SPOKEN : "About \(title.lowercased())")
        .popover(isPresented: Binding(get: { infoOpen == id }, set: { if !$0 && infoOpen == id { infoOpen = nil } }), arrowEdge: .trailing) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                ForEach(Array(lines.enumerated()), id: \.offset) { i, l in
                    Text(l).font(i == 0 ? MekaType.body : MekaType.caption)
                        .foregroundStyle(i == 0 ? palette.textPrimary : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button(MoneyTabs.shared.INFO_DONE) { infoOpen = nil }.keyboardShortcut(.defaultAction)
            }
            .padding(MekaSpace.m)
            .frame(width: 340)
        }
    }

    /// Slice 18: the year in review — the folded line with Open/Fold (January's finished year opens by itself), then
    /// what was spent and came in, left over, against the year before, each category with its bar, the busiest and
    /// quietest months and the biggest payment. Unfolds on the expand spring.
    private func year(_ yv: MoneyYearView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(yv.head, palette: palette).padding(.top, MekaSpace.xs)
            // Layout rules on Accounts (a), (c): the year in one card; what it covers behind the tab's foot ⓘ.
            yearCard(yv)
        }
    }

    private func yearCard(_ yv: MoneyYearView) -> some View {
        let open = yearOpen[yv.year] ?? yv.unfolded
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .moneyCard(palette)
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
            MoneyLabel(sh.head, palette: palette).padding(.top, MekaSpace.xs)
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
            MoneyLabel(m.head, palette: palette).padding(.top, MekaSpace.xs)
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
            MoneyLabel(b.head, palette: palette).padding(.top, MekaSpace.xs)
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
            // Money review, Accounts: "Side hustle account" is a switch in each account's popover now.
            Text(b.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.profitLine)
    }

    /// Slice 8: net worth — since the mockup check's item 7 the mockup's total card: "Across N accounts and trading" over
    /// the total (large) and one breakdown line (core MoneyTotal), then the change since last month and the month-by-month
    /// line; what he typed outside his accounts is one row ("Outside these accounts") opening a popover.
    private func worth(_ w: MoneyWorthView) -> some View {
        // Layout rules on Accounts (a), (c): net worth in one card; how it's worked out behind the tab's foot ⓘ.
        worthCard(w).padding(.top, MekaSpace.xs)
    }

    @ViewBuilder
    private func worthCard(_ w: MoneyWorthView) -> some View {
        if let c = MoneyTotal.shared.card(w: w) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(c.head).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                    if let t = c.total {
                        Text(t).font(MekaType.greeting).foregroundStyle(palette.textPrimary).lineLimit(1).contentTransition(.opacity)
                    }
                    if let b = c.breakdown {
                        Text(b).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
                    }
                    if let e = c.empty {
                        Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(c.spoken)
                if let ch = w.changeLine { Text(ch).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity) }
                if w.points.count >= 2 { MoneyWorthLine(points: w.points, palette: palette, reduced: reduceMotion) }
                if let l = w.chartLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
                if let m = w.missingLine { Text(m).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
                // Money review (layout rule (e)): what he typed outside his accounts is one row opening its popover.
                if let r = MoneyWorthOther.shared.row(w: w) { worthOtherRow(r) }
            }
            .moneyCard(palette)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: c.total)
        }
    }

    /// Net worth's one row for what he typed: the title over the names (or what to add), the total or "Add…", ›.
    private func worthOtherRow(_ r: MoneyWorthOtherRow) -> some View {
        Button {
            MekaHaptics.tick()
            said = nil
            worthOtherOpen = true
        } label: {
            HStack(spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(3).fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
                }
                Spacer()
                Text(r.amount ?? MoneyWorthOther.shared.ADD)
                    .font(r.add ? MekaType.caption : MekaType.body)
                    .foregroundStyle(r.add ? palette.textSecondary : (r.owes ? palette.accent : palette.textPrimary))
                    .contentTransition(.opacity)
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .popover(isPresented: $worthOtherOpen, arrowEdge: .trailing) { worthOtherPopover() }
    }

    /// The typed things' popover: each with its amount and Remove (Undo in the said line), then the field (typing a
    /// name again updates it). Found again in each view.
    @ViewBuilder
    private func worthOtherPopover() -> some View {
        let rows = MoneyWorthOther.shared.rows(w: model.money?.worth)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyWorthOther.shared.TITLE).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            ForEach(rows, id: \.id) { r in
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(r.spoken)
                    Spacer()
                    Text(r.amount).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    Button(MoneyWorthOther.shared.REMOVE) {
                        let id = r.id
                        MekaHaptics.tick()
                        Task { said = await model.moneyWorthRemove(id: id) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(r.name) from your net worth")
                }
                .transition(.opacity)
            }
            TextField(MoneyWorthOther.shared.HINT, text: $worthText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = worthText
                    worthText = ""
                    Task { said = await model.moneyWorthAdd(text: text) }
                }
            Text(MoneyWorthOther.shared.LINE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if let s = said {
                Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                if let u = s.undo {
                    Button("Undo") {
                        Task { _ = await model.undoMoneyCategory(token: u); said = nil }
                    }
                }
            }
            Button(MoneyWorthOther.shared.DONE) { worthOtherOpen = false }.keyboardShortcut(.defaultAction)
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: rows.count)
    }

    /// Slice 4: the forecast at his pace, with what-ifs on this Mac only (they go when Money locks).
    private func forecast(_ f: MoneyForecastView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(f.head, palette: palette).padding(.top, MekaSpace.xs)
            if let n = f.notReady {
                Text(n).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            } else {
                // Layout rules on Plan (a), (e): the forecast in one card; the what-ifs behind its What if… row (a popover).
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    forecastRows(f)
                    linkRow(MoneyPlanLayout.shared.WHAT_IF_ROW, line: MoneyPlanLayout.shared.whatIfBrief(f: f),
                            lit: MoneyPlanLayout.shared.whatIfOn(f: f)) { whatIfOpen = true }
                        .popover(isPresented: $whatIfOpen, arrowEdge: .trailing) {
                            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                                Text(MoneyPlanLayout.shared.WHAT_IF_SHEET).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                                whatIf(f)
                                Button(MoneyCards.shared.DONE) { whatIfOpen = false }.keyboardShortcut(.defaultAction)
                            }
                            .padding(MekaSpace.m)
                            .frame(width: 380)
                        }
                }
                .moneyCard(palette)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: f.rows.map { $0.line })
    }

    /// The forecast's pace, what's left and each horizon (on the page, and in the What if… popover as he switches).
    @ViewBuilder
    private func forecastRows(_ f: MoneyForecastView) -> some View {
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
    }

    /// Layout rules on Plan (e): the forecast's what-ifs, only in the What if… popover — the cut chips, trading at the
    /// pace it has gone, and the extra-a-month field with what it adds.
    @ViewBuilder
    private func whatIf(_ f: MoneyForecastView) -> some View {
                forecastRows(f)
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
    }

    /// One row inside a Plan card that opens a popover (What if…, Set a challenge): its name over a short line (in the
    /// accent when [lit]) and a chevron. Layout rule (e): fields live in the popover, never open on the page.
    private func linkRow(_ title: String, line: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button { MekaHaptics.tick(); said = nil; action() } label: {
            HStack(spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(line).font(MekaType.caption).foregroundStyle(lit ? palette.accent : palette.textSecondary)
                        .lineLimit(1).contentTransition(.opacity)
                }
                Spacer(minLength: MekaSpace.xs)
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel("\(title), \(line)")
        .padding(.top, MekaSpace.xs)
    }

    /// The lead idea as its own card (README item 6): title (a click opens the payments behind it) and figure, why, then
    /// its step, Remind me and Not for me as the mockup's small buttons.
    private func leadIdeaCard(_ i: MoneyIdea) -> some View {
        let id = i.id
        let act = i.actKey
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button { MekaHaptics.tick(); said = nil; ideaOpenId = id } label: {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
                    Text(i.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: MekaSpace.xs)
                    Text(i.figure).font(MekaType.itemTitle).foregroundStyle(i.lit ? palette.accent : palette.textSecondary).lineLimit(1)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(i.spoken)
            .popover(isPresented: Binding(get: { ideaOpenId == id }, set: { if !$0 && ideaOpenId == id { ideaOpenId = nil } }), arrowEdge: .trailing) {
                ideaDetails(i)
            }
            Text(i.why).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: MekaSpace.xs) {
                if let a = i.act {
                    leadPill(a, ink: palette.accent) {
                        if act == MoneyCheck.shared.ACT_CAP { setChallengeOpen = true }
                    }
                }
                leadPill(MoneyCheck.shared.REMIND, ink: palette.textPrimary) {
                    Task { said = await model.moneyIdeaRemind(id: id) }
                }
                Button(MoneyCheck.shared.NOT_FOR_ME) { MekaHaptics.tick(); Task { said = await model.moneyIdeaNotFor(id: id) } }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .moneyCard(palette)
    }

    /// A small pill button on the lead idea's card (the plain surface on the card's raised one, caption type).
    private func leadPill(_ label: String, ink: Color, action: @escaping () -> Void) -> some View {
        Button { MekaHaptics.tick(); action() } label: {
            Text(label).font(MekaType.caption).foregroundStyle(ink).lineLimit(1)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
                .background(palette.surface, in: Capsule())
        }
        .buttonStyle(MekaPressStyle())
    }

    /// Obi's money check: one idea as a compact row (its title · its figure, lit in brass · ›); a click opens its
    /// popover with why, the payments behind it, its step, Remind me and Not for me.
    private func ideaRow(_ i: MoneyIdea) -> some View {
        let id = i.id
        return Button { MekaHaptics.tick(); said = nil; ideaOpenId = id } label: {
            HStack(spacing: MekaSpace.m) {
                Text(i.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                Spacer(minLength: MekaSpace.xs)
                Text(i.figure).font(MekaType.body).foregroundStyle(i.lit ? palette.accent : palette.textSecondary).lineLimit(1)
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(i.spoken)
        .popover(isPresented: Binding(get: { ideaOpenId == id }, set: { if !$0 && ideaOpenId == id { ideaOpenId = nil } }), arrowEdge: .trailing) {
            ideaDetails(i)
        }
    }

    /// An idea's popover: title and figure, why, THE PAYMENTS BEHIND IT, then its step, Remind me and Not for me.
    private func ideaDetails(_ i: MoneyIdea) -> some View {
        let id = i.id
        let act = i.actKey
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                Text(i.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Spacer(minLength: MekaSpace.xs)
                Text(i.figure).font(MekaType.itemTitle).foregroundStyle(i.lit ? palette.accent : palette.textSecondary)
            }
            .accessibilityElement(children: .combine)
            Text(i.why).font(MekaType.body).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            if !i.behind.isEmpty {
                Text(MoneyCheck.shared.BEHIND).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xxs)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    ForEach(Array(i.behind.enumerated()), id: \.offset) { _, p in
                        HStack {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(p.label).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text(p.date).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                            Spacer()
                            Text(p.amount).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .moneyCard(palette)
            }
            HStack(spacing: MekaSpace.xs) {
                if let a = i.act {
                    Button(a) {
                        MekaHaptics.tick()
                        ideaOpenId = nil
                        if act == MoneyCheck.shared.ACT_CAP {
                            // One popover at a time: Set a challenge opens once this one has gone.
                            Task { @MainActor in
                                try? await Task.sleep(nanoseconds: 300_000_000)
                                setChallengeOpen = true
                            }
                        }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
                Button(MoneyCheck.shared.REMIND) { Task { said = await model.moneyIdeaRemind(id: id) } }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                Button(MoneyCheck.shared.NOT_FOR_ME) { Task { said = await model.moneyIdeaNotFor(id: id) } }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Spacer()
                Button(MoneyCards.shared.DONE) { ideaOpenId = nil }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.xxs)
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
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

    /// Money review (Meka 2026-10-26 22:12): one payment worth a look as a compact row (what it is, the amount, a
    /// chevron); a click opens it on the expand spring with when and where, That's fine and the never-acts line (the
    /// row's context menu has That's fine too). It leaves in the same frame; a refused write brings it back.
    private func lookRow(_ r: MoneyLookRow) -> some View {
        let id = r.id
        let open = openLook == id
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button {
                MekaHaptics.tick()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { openLook = open ? nil : id }
            } label: {
                HStack(spacing: MekaSpace.xs) {
                    Text(r.name).font(MekaType.body).foregroundStyle(r.lit ? palette.accent : palette.textPrimary).lineLimit(1)
                    Spacer()
                    if !r.amount.isEmpty { Text(r.amount).font(MekaType.body).foregroundStyle(palette.textPrimary) }
                    Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary).rotationEffect(.degrees(open ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(r.spoken)
            if open {
                Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                Button(MoneyWatch.shared.FINE) { fineLook(id) }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    .accessibilityLabel(MoneyLook.shared.FINE_SPOKEN)
                Text(MoneyWatch.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .contextMenu { Button(MoneyWatch.shared.FINE) { fineLook(id) } }
    }

    private func fineLook(_ id: String) {
        MekaHaptics.light()
        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { _ = finedLooks.insert(id) }
        Task {
            let r = await model.moneyAlertFine(alertId: id)
            if r == nil || r?.refused == true { finedLooks.remove(id) }
            if let r { said = r }
        }
    }

    /// Money review: "52 payments without a category · Sort them ›" under the category rows; a click opens the quick
    /// sorter as a popover (one payee at a time).
    private func unsortedRow(_ u: MoneyUnsortedView) -> some View {
        Button {
            MekaHaptics.tick()
            said = nil
            sortSkipped = []
            sortDone = []
            makingCategory = false
            sorting = true
        } label: {
            HStack {
                Text(u.line).font(MekaType.body).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(MoneySorter.shared.ACTION + " ›").font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(u.spoken)
        .popover(isPresented: $sorting, arrowEdge: .bottom) { sorter() }
    }

    /// The payee the sorter shows: the first not skipped and not picked (while its write runs).
    private func sortItem() -> MoneySortItem? {
        model.money?.month?.unsorted?.items.first { !sortSkipped.contains($0.key) && !sortDone.contains($0.key) }
    }

    /// The quick sorter: SORT YOUR PAYMENTS · N LEFT, the payee and its month, category chips (a click moves every
    /// payment from the payee, past and future, and the next comes up at once), New category, the last change with Undo,
    /// Skip and Done. When none are left it says so.
    @ViewBuilder
    private func sorter() -> some View {
        let items = model.money?.month?.unsorted?.items ?? []
        let left = items.filter { !sortSkipped.contains($0.key) && !sortDone.contains($0.key) }.count
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let it = sortItem() {
                let id = it.row.id
                let key = it.key
                let order = model.money?.categoryOrder ?? []
                let own = model.money?.ownCategories ?? []
                Text(MoneySorter.shared.head(left: Int32(left))).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    .contentTransition(.opacity)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(it.row.merchant).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                    Text(it.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                .id(key)
                .transition(.opacity)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(MoneySorter.shared.spoken(item: it, left: Int32(left)))
                .accessibilityAddTraits(.isHeader)
                Text(MoneyPaymentSheet.shared.CATEGORY_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs)
                if makingCategory {
                    newCategoryForm(txnId: id).transition(.opacity)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: MekaSpace.xs, alignment: .leading)], alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(MoneyPaymentSheet.shared.chips(order: order, current: it.row.category, own: own), id: \.key) { c in
                            sheetPill(c.label, on: c.on, spoken: c.spoken) {
                                guard !c.on else { return }
                                let cat = c.key
                                MekaHaptics.light()
                                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { _ = sortDone.insert(key) }
                                Task {
                                    let r = await model.setMoneyCategory(txnId: id, category: cat)
                                    if r == nil || r?.refused == true { sortDone.remove(key) }
                                    if let r { said = r }
                                }
                            }
                        }
                        sheetPill(MoneyOwnCategories.shared.NEW, on: false, spoken: MoneyOwnCategories.shared.NEW_SPOKEN) {
                            MekaHaptics.tick()
                            newCategoryName = ""
                            newCategoryTone = MoneyOwnCategories.shared.nextTone(own: own)
                            newCategoryIcon = "DOTS"
                            makingCategory = true
                        }
                    }
                    Text(MoneyPaymentSheet.shared.ruleLine(row: it.row)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let s = said { saidLine(s).transition(.opacity) }
                HStack {
                    Button(MoneySorter.shared.SKIP) {
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { _ = sortSkipped.insert(key) }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                    .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                    .background(palette.surfaceRaised, in: Capsule())
                    Spacer()
                    Button(MoneyPaymentSheet.shared.DONE) { sorting = false }
                        .buttonStyle(MekaPressStyle()).font(MekaType.metaStrong).foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                        .background(palette.accent, in: Capsule())
                        .keyboardShortcut(.defaultAction)
                }
                .padding(.top, MekaSpace.xs)
            } else {
                let skipped = items.filter { sortSkipped.contains($0.key) }.count
                Text(MoneySorter.shared.doneLine(skipped: Int32(skipped))).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if let s = said { saidLine(s) }
                HStack {
                    Spacer()
                    Button(MoneyPaymentSheet.shared.DONE) { sorting = false }
                        .buttonStyle(MekaPressStyle()).font(MekaType.metaStrong).foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                        .background(palette.accent, in: Capsule())
                        .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: sortDone)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: sortSkipped)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: makingCategory)
    }

    /// Layout rules on Bills: one refund as a compact row (what · its state · ›), a late one's state in brass; a click
    /// opens its popover with the full line, Close it or the chase to copy, and the statements' note when they're old.
    private func refundRow(_ r: MoneyRefundRow, foot: String?) -> some View {
        let id = r.id
        return Button { MekaHaptics.tick(); said = nil; refundOpenId = id } label: {
            // Money review item 9: the state under the name (as the cards' rows), every state on the same left edge.
            HStack(spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(r.name).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary).lineLimit(2)
                    Text(r.brief).font(MekaType.caption).lineLimit(1).contentTransition(.opacity)
                        .foregroundStyle(r.lit ? palette.accent : (r.state == MoneyRefunds.shared.BACK ? palette.success : palette.textSecondary))
                }
                Spacer(minLength: MekaSpace.xs)
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .popover(isPresented: Binding(get: { refundOpenId == id }, set: { if !$0 && refundOpenId == id { refundOpenId = nil } }), arrowEdge: .trailing) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(r.name).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                refund(r)
                if let f = foot { Text(f).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true) }
                Button(MoneyCards.shared.DONE) { refundOpenId = nil }.keyboardShortcut(.defaultAction)
            }
            .padding(MekaSpace.m)
            .frame(width: 360)
        }
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

    /// The payment sheet (Meka 2026-10-26 23:18): a click opens it as a popover under the row, never a menu.
    private func txn(_ t: MoneyTxnRow, chip: MoneySpendingView? = nil, showDay: Bool = true) -> some View {
        let id = t.id
        return Button {
            MekaHaptics.tick()
            said = nil
            sheetTxn = id
        } label: {
            HStack(spacing: chip == nil ? nil : MekaSpace.m) {
                if let sp = chip {
                    MoneyIconChip(icon: MoneySpending.shared.txnIcon(v: sp, category: t.category), tone: MoneySpending.shared.txnTone(v: sp, category: t.category), palette: palette)
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
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(t.spoken + ". Open to change its category")
        .popover(isPresented: Binding(get: { sheetTxn == id }, set: { if !$0 && sheetTxn == id { sheetTxn = nil; makingCategory = false } }), arrowEdge: .bottom) {
            paymentSheet(id)
        }
    }

    /// The payment as the view has it now (so the sheet follows each click), from the month's rows or Spending's latest.
    private func sheetRow(_ id: String) -> MoneyTxnRow? {
        guard let m = model.money?.month else { return nil }
        if let r = m.txns.first(where: { $0.id == id }) { return r }
        for d in m.spending?.latest ?? [] {
            if let r = d.rows.first(where: { $0.id == id }) { return r }
        }
        return nil
    }

    /// The payment sheet: payee and amount, day and account, category chips in his order, what a pick does, the last
    /// change with Undo, Shared · Always shared · Side hustle, and Done. Esc or a click outside closes it.
    @ViewBuilder
    private func paymentSheet(_ id: String) -> some View {
        if let r = sheetRow(id) {
            let order = model.money?.categoryOrder ?? []
            let own = model.money?.ownCategories ?? []
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(alignment: .top) {
                    Text(r.merchant).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Text(r.amount).font(MekaType.nowTitle).foregroundStyle(r.moneyIn ? palette.accent : palette.textPrimary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(MoneyPaymentSheet.shared.spoken(row: r))
                .accessibilityAddTraits(.isHeader)
                Text(MoneyPaymentSheet.shared.sub(row: r)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(MoneyPaymentSheet.shared.CATEGORY_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs)
                if makingCategory {
                    newCategoryForm(txnId: id)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: MekaSpace.xs, alignment: .leading)], alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(MoneyPaymentSheet.shared.chips(order: order, current: r.category, own: own), id: \.key) { c in
                            sheetPill(c.label, on: c.on, spoken: c.spoken) {
                                guard !c.on else { return }
                                let key = c.key
                                Task { said = await model.setMoneyCategory(txnId: id, category: key) }
                            }
                        }
                        if !r.moneyIn {
                            sheetPill(MoneyOwnCategories.shared.NEW, on: false, spoken: MoneyOwnCategories.shared.NEW_SPOKEN) {
                                MekaHaptics.tick()
                                newCategoryName = ""
                                newCategoryTone = MoneyOwnCategories.shared.nextTone(own: own)
                                newCategoryIcon = "DOTS"
                                makingCategory = true
                            }
                        }
                    }
                    Text(MoneyPaymentSheet.shared.ruleLine(row: r)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let s = said { saidLine(s).transition(.opacity) }
                Text(MoneyPaymentSheet.shared.MARKS_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: MekaSpace.xs, alignment: .leading)], alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(MoneyPaymentSheet.shared.toggles(row: r), id: \.label) { t in
                        sheetPill(t.label, on: t.on, spoken: t.spoken) {
                            let on = !t.on
                            // A plain number crosses into the Task, never the Kotlin enum.
                            let which = t.mark == MoneySheetMark.need ? 3
                                : (t.mark == MoneySheetMark.paymentShared ? 0 : (t.mark == MoneySheetMark.payeeShared ? 1 : 2))
                            Task {
                                if which == 3 {
                                    said = await model.moneyNeedMerchant(txnId: id, on: on)
                                } else if which == 0 {
                                    said = await model.moneySharedTxn(txnId: id, on: on)
                                } else if which == 1 {
                                    said = await model.moneySharedMerchant(txnId: id, on: on)
                                } else {
                                    said = await model.moneyBusinessMerchant(txnId: id, on: on)
                                }
                            }
                        }
                    }
                }
                HStack {
                    Spacer()
                    Button(MoneyPaymentSheet.shared.DONE) { sheetTxn = nil }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.metaStrong)
                        .foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                        .background(palette.accent, in: Capsule())
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(MekaSpace.m)
            .frame(width: 380)
            .background(palette.surface)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: makingCategory)
        }
    }

    /// The New category form: a name, one of the seven money colours, an icon (drawn in that colour), then Create (which
    /// also puts this payment's payee in it, with Undo) or Cancel (Esc). Nothing is written until Create.
    /// Slice 3: with `editing` (one of his ids) the same form renames and recolours it: Edit category · Save.
    private func newCategoryForm(txnId: String?, editing: String? = nil) -> some View {
        let tones = MoneyOwnCategories.shared.TONES
        let icons = MoneyOwnCategories.shared.ICONS
        let ready = MoneyOwnCategories.shared.clean(raw: newCategoryName) != nil
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(editing == nil ? MoneyOwnCategories.shared.TITLE : MoneyOwnCategories.shared.EDIT_TITLE).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            TextField(MoneyOwnCategories.shared.NAME_HINT, text: $newCategoryName)
                .textFieldStyle(.roundedBorder)
                .onSubmit { if let e = editing { saveCategory(id: e) } else { createCategory(txnId: txnId) } }
                .onChange(of: newCategoryName) { _, n in
                    let most = Int(MoneyOwnCategories.shared.MAX_NAME)
                    if n.count > most { newCategoryName = String(n.prefix(most)) }
                }
            Text(MoneyOwnCategories.shared.COLOUR_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(tones, id: \.tone) { t in
                    let on = t.tone == newCategoryTone
                    let tone = t.tone
                    Button { newCategoryTone = tone; MekaHaptics.tick() } label: {
                        Circle().fill(palette.moneyTone(tone)).frame(width: 22, height: 22)
                            .overlay(Circle().strokeBorder(on ? palette.textPrimary : Color.clear, lineWidth: 2))
                            .frame(width: 32, height: 32).contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(MoneyOwnCategories.shared.toneSpoken(t: t, on: on))
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            Text(MoneyOwnCategories.shared.ICON_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 36), spacing: MekaSpace.xs)], alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(icons, id: \.name) { i in
                    let name = i.name
                    let on = name == newCategoryIcon
                    Button { newCategoryIcon = name; MekaHaptics.tick() } label: {
                        MoneyIconChip(icon: i, tone: newCategoryTone, palette: palette)
                            .overlay(RoundedRectangle(cornerRadius: MekaRadius.s, style: .continuous)
                                .strokeBorder(on ? palette.moneyTone(newCategoryTone) : Color.clear, lineWidth: 2))
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(MoneyOwnCategories.shared.iconSpoken(icon: i, on: on))
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            HStack {
                Button(MoneyOwnCategories.shared.CANCEL) { if editing != nil { ownSheet = nil } else { makingCategory = false } }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button(editing == nil ? MoneyOwnCategories.shared.CREATE : MoneyOwnCategories.shared.SAVE) {
                    if let e = editing { saveCategory(id: e) } else { createCategory(txnId: txnId) }
                }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.metaStrong)
                    .foregroundStyle(ready ? palette.onAccent : palette.textTertiary)
                    .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                    .background(ready ? palette.accent : palette.surfaceRaised, in: Capsule())
                    .disabled(!ready)
            }
        }
    }

    private func saveCategory(id: String) {
        guard let name = MoneyOwnCategories.shared.clean(raw: newCategoryName) else { return }
        let tone = newCategoryTone
        let icon = newCategoryIcon
        Task { said = await model.editMoneyCategory(id: id, name: name, tone: tone, icon: icon) }
    }

    /// Slice 3: YOUR CATEGORIES on Accounts, one card: each of his categories with its icon, name and "2 payees · hidden
    /// from the ring"; a click opens its popover.
    private func ownCategories(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(MoneyOwnCategories.shared.HEAD, palette: palette)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(v.ownRows, id: \.id) { r in
                    let id = r.id
                    Button {
                        MekaHaptics.tick()
                        said = nil
                        askingDelete = false
                        newCategoryName = r.name
                        newCategoryTone = r.tone
                        newCategoryIcon = r.icon.name
                        ownSheet = id
                    } label: {
                        HStack(spacing: MekaSpace.m) {
                            MoneyIconChip(icon: r.icon, tone: r.tone, palette: palette)
                            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                            }
                            Spacer()
                            Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(r.spoken)
                    .popover(isPresented: Binding(get: { ownSheet == id }, set: { if !$0 && ownSheet == id { ownSheet = nil; askingDelete = false } }), arrowEdge: .trailing) {
                        ownCategorySheet(id)
                    }
                }
            }
            .padding(MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous))
        }
    }

    /// Slice 3: one of his categories' popover: the edit form (name, colour, icon, Save), Hide from the ring, MERGE INTO
    /// (his others first, then the built-in ones) and Delete category, which asks first. Merge and Delete close it; the line
    /// with Undo shows on the page.
    @ViewBuilder
    private func ownCategorySheet(_ id: String) -> some View {
        if let r = model.money?.ownRows.first(where: { $0.id == id }) {
            let own = model.money?.ownCategories ?? []
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(spacing: MekaSpace.m) {
                    MoneyIconChip(icon: r.icon, tone: r.tone, palette: palette)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(r.name).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
                newCategoryForm(txnId: nil, editing: id)
                if let s = said { saidLine(s).transition(.opacity) }
                sheetPill(MoneyOwnCategories.shared.HIDE, on: r.hidden, spoken: MoneyOwnCategories.shared.HIDE + (r.hidden ? ", on" : ", off")) {
                    let hide = !r.hidden
                    MekaHaptics.tick()
                    Task { said = await model.hideMoneyCategory(id: id, hidden: hide) }
                }
                .padding(.top, MekaSpace.xs)
                // His categories in caps (2026-10-27): a monthly cap on this one, counting its payees.
                Text(MoneyChallenges.shared.OWN_CAP_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs)
                if let cap = r.cap {
                    challengeBar(cap).transition(.opacity)
                } else {
                    TextField(MoneyChallenges.shared.OWN_CAP_HINT, text: $ownCapText)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("\(MoneyChallenges.shared.OWN_CAP_HINT) for \(r.name)")
                        .onSubmit {
                            let text = ownCapText
                            ownCapText = ""
                            MekaHaptics.tick()
                            Task { said = await model.capMoneyCategory(id: id, text: text) }
                        }
                        .transition(.opacity)
                }
                Text(MoneyOwnCategories.shared.MERGE_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.xs)
                Text(MoneyOwnCategories.shared.MERGE_HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: MekaSpace.xs, alignment: .leading)], alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(MoneyOwnCategories.shared.mergeTargets(own: own, id: id), id: \.key) { t in
                        let key = t.key
                        sheetPill(t.label, on: false, spoken: "Merge \(r.name) into \(t.label)") {
                            ownSheet = nil
                            Task { said = await model.mergeMoneyCategory(id: id, intoKey: key) }
                        }
                    }
                }
                if askingDelete {
                    Text(MoneyOwnCategories.shared.deleteAsk(name: r.name, payees: r.payees)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .padding(.top, MekaSpace.xs)
                    HStack(spacing: MekaSpace.m) {
                        Button(MoneyOwnCategories.shared.DELETE_YES) {
                            askingDelete = false
                            ownSheet = nil
                            Task { said = await model.deleteMoneyCategory(id: id) }
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        Button(MoneyOwnCategories.shared.KEEP) { askingDelete = false }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .transition(.opacity)
                } else {
                    Button(MoneyOwnCategories.shared.DELETE) { MekaHaptics.tick(); askingDelete = true }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        .padding(.top, MekaSpace.xs)
                }
                HStack {
                    Spacer()
                    Button(MoneyOwnCategories.shared.DONE) { ownSheet = nil }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.metaStrong)
                        .foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                        .background(palette.accent, in: Capsule())
                }
            }
            .padding(MekaSpace.m)
            .frame(width: 380)
            .background(palette.surface)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: askingDelete)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: r.cap == nil)
        }
    }

    private func createCategory(txnId: String?) {
        guard let txnId, let name = MoneyOwnCategories.shared.clean(raw: newCategoryName) else { return }
        let tone = newCategoryTone
        let icon = newCategoryIcon
        makingCategory = false
        Task { said = await model.addMoneyCategory(name: name, tone: tone, icon: icon, txnId: txnId) }
    }

    private func sheetPill(_ label: String, on: Bool, spoken: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.caption)
            .lineLimit(1)
            .foregroundStyle(on ? palette.onAccent : palette.textPrimary)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(on ? palette.accent : palette.surfaceRaised, in: Capsule())
            .accessibilityLabel(spoken)
            .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func openChat(listen: Bool) {
        said = nil
        chatListen = listen
        chatOpen = true
        Task { chatTry = await model.moneyChatSuggestions() }
    }

    /// Ask Obi's popover: an answer's buttons make the goal, use the cards plan, or show Plan.
    private func chatPanel(_ v: MoneyView) -> some View {
        MoneyChatPanel(
            palette: palette, turns: chatTurns, suggestions: chatTry, working: chatWorking, said: said,
            onAsk: { q in
                guard !chatWorking else { return }
                MekaHaptics.tick()
                said = nil
                chatWorking = true
                let id = chatNext
                chatNext += 1
                Task {
                    let line = await model.moneyChat(q, id: id)
                    chatWorking = false
                    if let line { chatTurns = Array((chatTurns + [line]).suffix(Int(MoneyChat.shared.KEEP))) }
                }
            },
            onAction: { a in
                if a.kind == MoneyChat.shared.ACT_GOAL {
                    Task { said = await model.moneyChatGoal(amountPence: a.amountPence, months: a.months) }
                } else if a.kind == MoneyChat.shared.ACT_CLEAR {
                    Task { said = await model.moneyClearGoal(payPence: a.amountPence, order: a.order) }
                } else {
                    chatOpen = false
                    tab = MoneyTab.plan
                    if let step = MoneyTabs.shared.stepOnPick(tab: MoneyTab.plan, v: v) { model.moneyStep(step.int32Value) }
                }
            },
            onUndo: { u in Task { _ = await model.undoMoneyCategory(token: u); said = nil } },
            onDone: { chatOpen = false },
            listenOnOpen: chatListen
        )
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
            MoneyLabel(b.head, palette: palette)
            // Layout rules on Accounts (a): his banks in one card under their label.
            bankCard(b)
        }
    }

    private func bankCard(_ b: MoneyBankView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
        .moneyCard(palette)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.refreshing)
    }

    /// Trading tracker: TRADING · HYPERLIQUID, read-only from his public addresses. Layout rules on Trading (2026-10-28,
    /// MoneyTradingLayout): cards under their labels — equity (the total, a Read-only mark, Dollars · Pounds, the P&L),
    /// OPEN POSITIONS (five, then See all), the P&L line, YOUR TRADES, the month in review, YOUR ADDRESSES, ALERTS and
    /// EXPORT FOR TAX — and one "Read-only · not trading advice" foot whose ⓘ says where the figures come from.
    /// Trading's parts that sit in column [col]: equity, Obi's risk line, the curve and the month on the left; positions,
    /// Kestrel, trades, addresses, alerts and the export on the right; the foot under both (MoneyColumns, as the Fold).
    private func trading(_ t: TradingView, _ col: MoneyColumn) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // The equity card as the mockup carries its own label: TRADING · HYPERLIQUID only before the first read.
            if MoneyTradingLayout.shared.showHead(t: t), on("trading-head", col) {
                MoneyLabel(t.head, palette: palette)
            }
            if on("trading", col) { tradingEquity(t).moneyCard(palette) }
            // The month's four tiles under equity (mockup's 2×2): closed profit, funding, fees, winning trades.
            if let tl = t.tiles, on("trading-tiles", col) {
                tradingHead(tl.head)
                tradingTiles(tl)
            }
            if let r = t.risk, on("trading-risk", col) { tradingRisk(r).moneyCard(palette) }
            if MoneyTradingLayout.shared.hasPositions(t: t), on("trading-positions", col) {
                tradingHead(MoneyTradingLayout.shared.POSITIONS_HEAD)
                tradingPositions(t).moneyCard(palette)
            }
            if let k = model.tradingKestrel, on("trading-kestrel", col) {
                tradingHead(k.head)
                tradingKestrel(k).moneyCard(palette)
            }
            if let c = t.curve, MoneyTradingLayout.shared.showCurveCard(t: t), on("trading-curve", col) {
                tradingHead(c.head)
                tradingCurve(c).moneyCard(palette)
            }
            if let tr = TradingTiles.shared.trades(t: t), on("trading-trades", col) {
                tradingHead(tr.head)
                trades(tr).moneyCard(palette)
            }
            if let r = t.review, on("trading-review", col) {
                tradingHead(r.head)
                tradingReview(r).moneyCard(palette)
            }
            Group {
                if on("trading-addresses", col) {
                    tradingHead(MoneyTradingLayout.shared.ADDRESSES_HEAD)
                    tradingAddresses(t).moneyCard(palette)
                }
                if let a = t.alerts, on("trading-alerts", col) {
                    tradingHead(a.head)
                    tradingAlertsRow(TradingTiles.shared.alertsRow(a: a), a).moneyCard(palette)
                }
                if let e = t.export, on("trading-export", col) {
                    tradingHead(e.head)
                    tradingExport(e, inCard: true).moneyCard(palette)
                }
            }
            if on("trading-foot", col) {
                HStack(spacing: MekaSpace.xs) {
                    Text(MoneyTradingLayout.shared.FOOT_SHORT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    infoButton("foot-trading", title: MoneyTabs.shared.INFO_TITLE,
                               lines: MoneyTradingLayout.shared.info(t: t) + (model.tradingKestrel == nil ? [] : [TradingKestrel.shared.INFO]))
                }
                .padding(.top, MekaSpace.m)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: t.total)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: t.wallets.count)
    }

    /// Obi's risk line (mockup): his small caps label over the line, lit in brass when a position is near liquidation.
    private func tradingRisk(_ r: TradingRiskView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            MoneyLabel(r.head, palette: palette)
            Text(r.line).font(MekaType.body).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(r.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.line)
    }

    /// The Kestrel card: what's waiting for his OK, the newest request and when it expires, today's tally, and
    /// Review in Needs you. Only reads: approving stays in Needs you, behind Touch ID.
    private func tradingKestrel(_ k: TradingKestrelView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Group {
                Text(k.status).font(MekaType.body).foregroundStyle(k.lit ? palette.accent : palette.textSecondary)
                if let t = k.waitingTitle { Text(t).font(MekaType.body).foregroundStyle(palette.textPrimary) }
                if let l = k.waitingLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                if let t = k.tally { Text(t).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(k.spoken)
            if let r = k.review {
                Button {
                    MekaHaptics.tick()
                    model.reviewKestrelInNeedsYou()
                } label: {
                    Text(r + " ›").font(MekaType.caption).foregroundStyle(palette.accent)
                }
                .buttonStyle(MekaPressStyle())
                .padding(.top, MekaSpace.xs)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: k.status)
    }

    private func tradingHead(_ head: String) -> some View {
        MoneyLabel(head, palette: palette).padding(.top, MekaSpace.xs)
    }

    /// The equity card: the total and the mockup's Read-only mark, its other currency and when it was read,
    /// Dollars · Pounds, then today, the week, the month and all time (up in brass).
    @ViewBuilder
    private func tradingEquity(_ t: TradingView) -> some View {
        if let c = t.equity {
            tradingEquityCard(c)
        } else {
            tradingEquityOld(t)
        }
    }

    /// The equity card as the mockup (README item 5): "Hyperliquid · Main 0x…" with the Read-only mark, the value large
    /// with "≈ £…" beside it (a click shows Trading in that currency, both devices), the range's change (up in brass),
    /// its P&L line, the 1D · 1W · 1M chips (the chosen one brass-tinted) and one grey line: all time and when it was read.
    private func tradingEquityCard(_ c: TradingEquityCard) -> some View {
        let r = TradingEquity.shared.range(c: c, key: tradingRange)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .center) {
                Text(c.label).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1).truncationMode(.middle)
                Spacer()
                readOnlyMark
            }
            HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xs) {
                Text(c.total).font(MekaType.greeting).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                    .accessibilityLabel(c.spoken)
                if let other = c.other, let swap = c.swapKey {
                    Button {
                        MekaHaptics.tick()
                        let kind = TradingCurrencyRules.shared.KIND
                        let key = swap
                        Task { said = await model.setTradingAlert(kind: kind, key: key) }
                    } label: {
                        Text(other).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(c.otherSpoken ?? other)
                }
            }
            if let ch = r.change {
                Text(ch).font(MekaType.body)
                    .foregroundStyle(r.up?.boolValue == true ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
            }
            if r.points.count >= 2 {
                MoneyWorthLine(points: r.points, palette: palette, reduced: reduceMotion, dots: false).id(r.key)
                    .transition(.opacity)
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(TradingEquity.shared.chips(c: c, key: tradingRange), id: \.key) { ch in
                    Button {
                        guard !ch.on else { return }
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { tradingRange = ch.key }
                    } label: {
                        Text(ch.label).font(MekaType.captionStrong)
                            .foregroundStyle(ch.on ? palette.accent : palette.textSecondary)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(Capsule().fill(ch.on ? palette.accent.opacity(0.16) : palette.surfaceRaised))
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(ch.spoken)
                    .accessibilityAddTraits(ch.on ? .isSelected : [])
                }
            }
            if let f = c.foot { Text(f).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.change)
    }

    private func tradingEquityOld(_ t: TradingView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let total = t.total {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(total).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                        if let l = t.totalLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(t.spoken)
                    Spacer()
                    readOnlyMark
                }
            }
            if let e = t.empty {
                HStack(alignment: .top) {
                    Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    readOnlyMark
                }
            }
            if let w = MoneyTradingLayout.shared.waiting(t: t) {
                HStack(alignment: .top) {
                    Text(w).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    readOnlyMark
                }
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
        }
    }

    private var readOnlyMark: some View {
        Text(MoneyTradingLayout.shared.READ_ONLY).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .accessibilityLabel(MoneyTradingLayout.shared.READ_ONLY_SPOKEN)
    }

    /// OPEN POSITIONS: how many and what's not yet taken, then nearest liquidation first (lit within his alert's
    /// distance), five, then "See all N ›" (layout rule (d)), opening on the expand spring.
    private func tradingPositions(_ t: TradingView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let u = t.unrealisedLine { Text(u).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            ForEach(MoneyTradingLayout.shared.positions(t: t, all: tradingAll), id: \.id) { p in
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
            if let m = MoneyTradingLayout.shared.more(t: t, all: tradingAll) {
                Button {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { tradingAll.toggle() }
                } label: {
                    Text(m).font(MekaType.caption).foregroundStyle(palette.accent)
                }
                .buttonStyle(MekaPressStyle())
            }
        }
    }

    /// YOUR ADDRESSES: each public address with what was read and Remove, and one row opening the add popover.
    private func tradingAddresses(_ t: TradingView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
            tradingAddRow(MoneyTradingLayout.shared.addRow(t: t))
        }
    }

    /// Layout rule (e): "Add an address" (or the first one) over its line, in the accent, ›; opens the popover with the field.
    private func tradingAddRow(_ r: TradingAddRow) -> some View {
        Button {
            MekaHaptics.tick()
            said = nil
            tradingAddOpen = true
        } label: {
            HStack(spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(r.title).font(MekaType.body).foregroundStyle(palette.accent).lineLimit(1)
                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                }
                Spacer()
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .popover(isPresented: $tradingAddOpen, arrowEdge: .trailing) { tradingAddPopover() }
    }

    /// The add-an-address popover: the field (an address and a name), the line that it's the public address only, and
    /// what adding said.
    private func tradingAddPopover() -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyTradingLayout.shared.ADD_SHEET).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            TextField(MoneyTradingLayout.shared.ADD_HINT, text: $tradingText)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let text = tradingText
                    tradingText = ""
                    Task { said = await model.tradingWalletAdd(text: text) }
                }
            Text(MoneyTradingLayout.shared.ADD_SHEET_LINE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if let s = said {
                Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(MoneyTradingLayout.shared.ADD_DONE) { tradingAddOpen = false }.keyboardShortcut(.defaultAction)
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
    }

    /// The month's four tiles (mockup's 2×2): label over value in raised tiles, two a row; a gain in the accent. A value
    /// cross-fades when the trades are read again (reduced motion: no movement, the same fade).
    private func tradingTiles(_ v: TradingTilesView) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: MekaSpace.xs), GridItem(.flexible(), spacing: MekaSpace.xs)],
                  alignment: .leading, spacing: MekaSpace.xs) {
            ForEach(v.tiles, id: \.label) { t in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(t.label).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                    Text(t.value).font(MekaType.metaStrong)
                        .foregroundStyle(t.up?.boolValue == true ? palette.accent : palette.textPrimary)
                        .lineLimit(1).contentTransition(.opacity)
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(t.label), \(t.value)")
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.spoken)
    }

    /// ALERTS as one row (README item 5): how many are on over each one's choice, ›; opens the popover with the choices.
    private func tradingAlertsRow(_ r: TradingAlertsRow, _ a: TradingAlertsView) -> some View {
        Button {
            MekaHaptics.tick()
            said = nil
            tradingAlertsOpen = true
        } label: {
            HStack(spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1).contentTransition(.opacity)
                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(2).contentTransition(.opacity)
                }
                Spacer()
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.line)
        .popover(isPresented: $tradingAlertsOpen, arrowEdge: .trailing) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(TradingTiles.shared.ALERTS_SHEET).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                tradingAlerts(model.money?.trading?.alerts ?? a)
                if let s = said {
                    Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button(TradingTiles.shared.ALERTS_DONE) { tradingAlertsOpen = false }.keyboardShortcut(.defaultAction)
            }
            .padding(MekaSpace.m)
            .frame(width: 380)
        }
    }

    /// Trading tracker, slice 3: ALERTS — what he's told and when (near liquidation, even in quiet hours; a drop from the
    /// month's high; slice 7: a big order and a day's high funding), with a row of choices for each; the chosen one in brass.
    private func tradingAlerts(_ a: TradingAlertsView) -> some View {
        // Layout rules on Trading: the label (a.head) sits above the card.
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(a.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
            alertChips(a.liqLabel, a.liqChips)
            alertChips(a.dropLabel, a.dropChips)
            if !a.fillChips.isEmpty { alertChips(a.fillLabel, a.fillChips) }
            if !a.fundingChips.isEmpty { alertChips(a.fundingLabel, a.fundingChips) }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: a.line)
    }

    /// Trading tracker, slice 8: EXPORT FOR TAX — a button per UK tax year; while the server reads the year they dim and
    /// the line says so, then a save panel asks where the CSV goes.
    /// Money slice 13b: the same block serves the side hustle's YEAR-END EXPORT (`business`), made on the Mac at once.
    private func tradingExport(_ e: TradingExportView, business: Bool = false, inCard: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // Layout rules on Trading: in its card the label sits above it; the side hustle's keeps its own.
            if !inCard { MoneyLabel(e.head, palette: palette) }
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
        .padding(.top, inCard ? 0 : (business ? MekaSpace.xs : MekaSpace.m))
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
        // Layout rules on Trading: the label sits above the card; how to read the line is behind the foot's ⓘ.
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(c.summary).font(MekaType.body)
                .foregroundStyle(c.up?.boolValue == true ? palette.accent : palette.textPrimary)
                .contentTransition(.opacity)
                .accessibilityLabel(c.spoken)
            if let r = c.rangeLine { Text(r).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            MoneyWorthLine(points: c.points, palette: palette, reduced: reduceMotion, dots: false)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: c.summary)
    }

    /// Trading tracker, slice 2: YOUR TRADES · LAST 30 DAYS — trades closed and won, closed P&L, fees, funding and
    /// what's left after them (up in brass), the average win and loss, the best and worst trade, and each coin.
    private func trades(_ tr: TradingTradesView) -> some View {
        // Layout rules on Trading: the label sits above the card; how trades are counted is behind the foot's ⓘ.
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tr.summary)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tr.coins.count)
    }

    /// Trading tracker, slice 9: last month in review — the month's result after fees and funding (up in brass) and
    /// plain sentences, facts only; a new month cross-fades in.
    private func tradingReview(_ r: TradingReviewView) -> some View {
        // Layout rules on Trading: the label sits above the card; what the month covers is behind the foot's ⓘ.
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(r.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.summary)
    }

    /// Money review, Accounts: Import a statement is one row; its field and button live in a popover.
    /// Accounts' rest: the MORE card — each row is its title over its line and ›, opening a popover with what used to
    /// sit open on the page.
    private func more(_ v: MoneyView) -> some View {
        let rows = MoneyMore.shared.rows(v: v)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let e = v.empty {
                Text(e).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            MoneyLabel(MoneyMore.shared.HEAD, palette: palette).padding(.top, MekaSpace.xs)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(rows, id: \.title) { r in moreRow(r, v) }
            }
            .padding(MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous))
        }
    }

    private func moreRow(_ r: MoneyMoreRow, _ v: MoneyView) -> some View {
        let kind = r.kind
        return Button {
            MekaHaptics.tick()
            said = nil
            deleting = false
            if kind == MoneyMoreKind.data { account = "" }
            moreOpen = kind
        } label: {
            HStack(spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    Text(r.line).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
                        .lineLimit(2).contentTransition(.opacity)
                }
                Spacer()
                Text("›").font(MekaType.body).foregroundStyle(palette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .popover(isPresented: Binding(get: { moreOpen == kind }, set: { if !$0 && moreOpen == kind { moreOpen = nil } }), arrowEdge: .trailing) {
            morePopover(kind)
        }
    }

    /// A MORE row's popover: the side hustle's year, the shared costs, or Import, export and delete. Found again in each
    /// view, so it closes once its block has gone.
    @ViewBuilder
    private func morePopover(_ kind: MoneyMoreKind) -> some View {
        if let v = model.money {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(MoneyMore.shared.title(kind: kind, v: v)).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        if kind == MoneyMoreKind.sideHustle, let b = v.business { business(b) }
                        if kind == MoneyMoreKind.sharedCosts, let sh = v.shared { shared(sh) }
                        if kind == MoneyMoreKind.data { dataTools(v) }
                    }
                }
                .scrollIndicators(.never)
                .frame(maxHeight: 480)
                if let s = said {
                    Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                }
                Button(MoneyMore.shared.DONE) { moreOpen = nil }.keyboardShortcut(.defaultAction)
            }
            .padding(MekaSpace.m)
            .frame(width: 420)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: deleting)
        }
    }

    /// Import, export and delete: the statement's account and Choose file, Export all payments, and Delete all money
    /// data, which asks first.
    private func dataTools(_ v: MoneyView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyAccountList.shared.IMPORT_HEAD.uppercased()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Text(MoneyAccountList.shared.IMPORT_ROW_SUB).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            TextField(MoneyRules.shared.ACCOUNT_HINT, text: $account).textFieldStyle(.roundedBorder)
            Button(busy ? "Reading the file…" : MoneyRules.shared.IMPORT + "…") { chooseFile() }
                .disabled(busy)
            if v.bank == nil {
                Text(MoneyRules.shared.CONNECT_LATER).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("EXPORT").font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
            Text(MoneyMore.shared.EXPORT_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(MoneyMore.shared.EXPORT + "…") { MekaHaptics.tick(); exportAll() }
                .disabled(exporting)
            if !v.accounts.isEmpty || (v.worth?.rows.contains { $0.removable } ?? false) {
                if deleting {
                    Text(MoneyRules.shared.DELETE_ASK).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.m)
                    HStack {
                        Button("Delete") {
                            deleting = false
                            moreOpen = nil
                            Task { said = await model.deleteAllMoney() }
                        }
                        Button("Cancel") { deleting = false }
                    }
                } else {
                    Button(MoneyRules.shared.DELETE_ALL) { deleting = true; MekaHaptics.tick() }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .padding(.top, MekaSpace.m)
                }
            }
        }
    }

    /// Export all payments: the CSV made on this Mac, saved where he picks.
    private func exportAll() {
        guard !exporting else { return }
        exporting = true
        Task {
            let file = await model.moneyExport()
            exporting = false
            guard let file else { return }
            said = file.said
            guard let csv = file.csv, let name = file.fileName else { return }
            let panel = NSSavePanel()
            panel.allowedContentTypes = [.commaSeparatedText]
            panel.nameFieldStringValue = name
            panel.message = "Where to save the spreadsheet of your payments"
            panel.prompt = "Save"
            guard panel.runModal() == .OK, let url = panel.url else { return }
            do {
                try csv.write(to: url, atomically: true, encoding: .utf8)
                MekaHaptics.light()
                said = CoreModel.MoneySaid(line: "Saved the spreadsheet · open it in Numbers or Excel", refused: false, undo: nil)
            } catch {
                MekaHaptics.tick()
                said = CoreModel.MoneySaid(line: "Couldn't save the spreadsheet there · try another place", refused: true, undo: nil)
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

    /// Money review (Meka 2026-10-26 22:12), Accounts: one clean list — two letters, the short name over what it is,
    /// the balance (a card's owed in amber, the brass accent) or a quiet Add balance — each row opening the account's popover; a
    /// trading row goes to the Trading tab.
    private func accounts(_ v: MoneyView) -> some View {
        let items = MoneyAccountList.shared.items(v: v)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            MoneyLabel(MoneyAccountList.shared.HEAD, palette: palette).padding(.top, MekaSpace.xs)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(items, id: \.id) { r in accountRow(r) }
            }
            .padding(MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous))
        }
    }

    private func accountRow(_ r: MoneyAccountItem) -> some View {
        let id = r.id
        let opens = r.opens
        return Button {
            MekaHaptics.tick()
            if opens {
                said = nil
                askingRemove = false
                accountBalance = ""
                accountName = model.money.flatMap { MoneyAccountList.shared.sheet(v: $0, id: id) }.map { $0.nick ?? $0.title } ?? ""
                accountSheet = id
            } else {
                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { tab = MoneyTab.trading }
            }
        } label: {
            HStack(spacing: MekaSpace.m) {
                Text(r.initials).font(MekaType.caption).foregroundStyle(palette.accent)
                    .frame(width: 40, height: 40)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous))
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    // Mockup check item 7: no cut words; a long name or line wraps instead.
                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(r.sub).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textSecondary)
                        .lineLimit(3).fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
                }
                Spacer()
                Text(r.amount).font(r.addBalance ? MekaType.caption : MekaType.metaStrong)
                    .foregroundStyle(r.addBalance ? palette.textSecondary : (r.owes ? palette.accent : palette.textPrimary))
                    .contentTransition(.opacity)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.spoken)
        .popover(isPresented: Binding(get: { accountSheet == id }, set: { if !$0 && accountSheet == id { accountSheet = nil } }), arrowEdge: .trailing) {
            accountPopover(id)
        }
    }

    /// An account's popover: its balance (a field where typing makes sense), It's a card, Side hustle account, Import a
    /// statement into it, and Remove account, which asks first. Found again in each view, so it closes once it's gone.
    @ViewBuilder
    private func accountPopover(_ id: String) -> some View {
        if let v = model.money, let a = MoneyAccountList.shared.sheet(v: v, id: id) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(a.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                if let f = a.fullName { Text(f).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1) }
                Text(a.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                // Accounts' rest: Name — type what to call it and press Return; clear it for its own name.
                Text(MoneyAccountNames.shared.RENAME.uppercased()).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                TextField(MoneyAccountNames.shared.RENAME_HINT, text: $accountName)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        let text = accountName
                        MekaHaptics.tick()
                        Task { said = await model.renameMoneyAccount(id: id, text: text) }
                    }
                Text(MoneyAccountList.shared.BALANCE).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
                Text(a.amount ?? MoneyAccountList.shared.ADD_BALANCE)
                    .font(a.amount != nil ? MekaType.nowTitle : MekaType.body)
                    .foregroundStyle(a.amount != nil ? palette.textPrimary : palette.textSecondary)
                    .contentTransition(.opacity)
                if let l = a.balanceLine { Text(l).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                if a.canType {
                    TextField(MoneyWorth.shared.BALANCE_HINT, text: $accountBalance)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit {
                            let text = accountBalance
                            accountBalance = ""
                            Task { said = await model.moneyBalance(accountId: id, text: text) }
                        }
                }
                Toggle(isOn: Binding(get: { a.card }, set: { on in
                    MekaHaptics.tick()
                    Task { said = await model.moneyCardKind(accountId: id, card: on, charge: false) }
                })) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(MoneyAccountList.shared.CARD_SWITCH).font(MekaType.body)
                        Text(MoneyAccountList.shared.CARD_SWITCH_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                }
                .toggleStyle(.switch)
                .padding(.top, MekaSpace.xs)
                if let side = a.sideHustle?.boolValue {
                    Toggle(isOn: Binding(get: { side }, set: { on in
                        MekaHaptics.tick()
                        Task { said = await model.moneyBusinessAccount(accountId: id, on: on) }
                    })) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(MoneyBusiness.shared.SWITCH).font(MekaType.body)
                            Text(MoneyAccountList.shared.SIDE_HUSTLE_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                    }
                    .toggleStyle(.switch)
                }
                Button(busy ? "Reading the file…" : MoneyAccountList.shared.IMPORT) {
                    account = v.accounts.first(where: { $0.id == id })?.name ?? a.title
                    chooseFile()
                }
                .disabled(busy)
                .padding(.top, MekaSpace.xs)
                if askingRemove {
                    Text(a.removeAsk).font(MekaType.body).foregroundStyle(palette.textPrimary).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Button(MoneyAccountList.shared.REMOVE_YES) {
                            askingRemove = false
                            accountSheet = nil
                            Task { said = await model.removeMoneyAccount(id: id) }
                        }
                        Button(MoneyAccountList.shared.CANCEL) { askingRemove = false }
                    }
                } else {
                    Button(MoneyAccountList.shared.REMOVE) { MekaHaptics.tick(); askingRemove = true }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                if let s = said {
                    Text(s.line).font(MekaType.caption).foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                }
                Button(MoneyAccountList.shared.DONE) { accountSheet = nil }.keyboardShortcut(.defaultAction)
            }
            .padding(MekaSpace.m)
            .frame(width: 380)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: askingRemove)
        }
    }

    /// Layout rule (c): Accounts says "read-only · not financial advice" once, at its foot; where the accounts, banks,
    /// net worth and the year come from behind ⓘ.
    @ViewBuilder
    private func foot(_ v: MoneyView) -> some View {
        if let lines = MoneyTabs.shared.info(tab: MoneyTab.accounts, v: v) {
            HStack(spacing: MekaSpace.xs) {
                Text(MoneyTabs.shared.FOOT_SHORT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Spacer()
                infoButton("foot-accounts", title: MoneyTabs.shared.INFO_TITLE, lines: lines)
            }
            .padding(.top, MekaSpace.m)
        }
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

/// Money on the Mac as the mockup draws it at 1280 px (Mac-*.dc.html): once open, the sheet widens to hold the tabs down
/// the side and two columns 32 pt apart; locked, it stays the narrow sheet with Unlock.
enum MoneyMacLayout {
    static let narrowWidth: CGFloat = 520
    static let wideWidth: CGFloat = 1040
    static let sideWidth: CGFloat = 180
    static let gap: CGFloat = 32
    static let askWidth: CGFloat = 640
    static let scrollHeight: CGFloat = 620
}

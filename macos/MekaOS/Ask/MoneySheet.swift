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
            if !v.accounts.isEmpty {
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

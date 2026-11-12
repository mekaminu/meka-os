@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Money → Plan → Spending plans on the Mac (slice 1), like the Fold's SpendPlansPage and the approved design
/// (`docs/design/money/SpendPlan.dc.html`): plans as chips, THIS PLAN with its total, needed-by day and bought/sent bar,
/// Can I afford it? (This month · Over 2 months · Over 3 months) from Money's average month, three month tiles, Same for
/// everyone with − / + for a plan in another currency, and the people or things five a page (the circle ticks one as
/// bought or sent; a click changes it). A calculator only: nothing here sends or moves money. Only Strings, Bools and
/// Int32s cross to the core.
///
/// Motion: the sheet scale-fades (system) over Money; the cards stagger in; the bar grows on the replan spring and the
/// verdict cross-fades (its colour blends); the popovers fade. Reduce Motion: cross-fades, the bar shown at its value.
struct SpendPlansSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var chosen: String?
    @State private var months: Int32 = 1
    @State private var page = 0
    @State private var said: CoreModel.SpendPlanSaid?
    @State private var newOpen = false
    @State private var newText = ""
    @State private var newCurrency = SpendPlanRules.shared.GBP
    @State private var addOpen = false
    @State private var editing: SpendRow?
    @State private var name = ""
    @State private var note = ""
    @State private var amount = ""
    @State private var month: Int32 = 0
    @State private var link = ""
    @State private var rateOpen = false
    @State private var rateText = ""
    @State private var removing: String?

    var body: some View {
        let v = model.spendPlans
        let plan = v?.plan(id: chosen)
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            HStack {
                Text(SpendPlanRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
                Spacer()
                if v?.canAdd ?? true {
                    Button(SpendPlanRules.shared.NEW_PLAN) { said = nil; newOpen = true }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.metaStrong)
                        .foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(palette.accent, in: Capsule())
                        .popover(isPresented: $newOpen, arrowEdge: .bottom) { newPlanPopover }
                }
            }
            if let chips = v?.chips, !chips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(chips, id: \.id) { c in
                            pill(c.name, on: c.id == plan?.id) { chosen = c.id; page = 0; months = 1 }
                        }
                    }
                }
                .staggeredAppear(1)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    if let plan {
                        totalCard(plan).staggeredAppear(2)
                        affordCard(plan).staggeredAppear(3)
                        countCard(plan).staggeredAppear(4)
                        if let f = plan.foreign { sameCard(plan, f).staggeredAppear(4) }
                        listCard(plan).staggeredAppear(5)
                        let asking = removing == plan.id
                        Button(asking ? "Click again to remove \(plan.name) and everything on it" : SpendPlanRules.shared.REMOVE_PLAN) {
                            if asking { removing = nil; chosen = nil; model.removeSpendPlan(plan.id) } else { MekaHaptics.tick(); removing = plan.id }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(asking ? palette.accent : palette.textSecondary)
                    } else {
                        Text("Plan for Christmas gifts, a holiday or money sent home: add the people or things, and see whether it fits your month.")
                            .font(MekaType.body).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .animation(MekaMotion.appear(reduced: reduceMotion), value: plan?.rows.map { $0.id + ($0.done ? "1" : "0") + $0.gbp } ?? [])
            }
            .frame(maxHeight: 560)
            Text(SpendPlanRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack {
                Spacer()
                Button(SpendPlanRules.shared.DONE) { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 560)
        .background(palette.surface)
    }

    private func pill(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button { MekaHaptics.tick(); action() } label: {
            Text(label).font(on ? MekaType.captionStrong : MekaType.caption)
                .foregroundStyle(on ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(on ? palette.accent : palette.surfaceRaised, in: Capsule())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(on ? .isSelected : [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
    }

    private func saidLine() -> some View {
        Group {
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var newPlanPopover: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(SpendPlanRules.shared.NEW_SHEET).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            TextField(SpendPlanRules.shared.NEW_HINT, text: $newText)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(SpendPlanRules.shared.NEW_DESCRIPTION)
            Text("In").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(SpendPlanRules.shared.CURRENCIES, id: \.code) { c in
                        pill("\(c.symbol) \(c.name)", on: c.code == newCurrency) { newCurrency = c.code }
                    }
                }
            }
            saidLine()
            HStack {
                Button(SpendPlanRules.shared.DONE) { newOpen = false }
                Spacer()
                Button("Add plan") {
                    let text = newText
                    let currency = newCurrency
                    Task {
                        said = await model.addSpendPlan(text, currency: currency)
                        if let s = said, !s.refused {
                            chosen = s.id; page = 0; months = 1; newText = ""; newOpen = false
                        }
                    }
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
    }

    private func totalCard(_ plan: SpendPlanDetail) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 0) {
                    MoneyLabel("THIS PLAN", palette: palette)
                    Text(plan.total).font(MekaType.greeting).foregroundStyle(palette.textPrimary).contentTransition(.numericText())
                    Text(plan.totalSub).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                Spacer()
                if let by = plan.by { Text(by).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            }
            SpendBar(fill: Double(plan.bar), palette: palette)
            Text(plan.spentLine).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
        }
        .moneyCard(palette)
    }

    private func affordCard(_ plan: SpendPlanDetail) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(SpendPlanRules.shared.AFFORD_HEAD).font(MekaType.body).foregroundStyle(palette.textPrimary)
            if let a = plan.afford.first(where: { $0.months == months }) ?? plan.afford.first {
                ForEach(a.rows, id: \.label) { r in
                    HStack {
                        Text(r.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        Spacer()
                        Text(r.value).font(r.strong ? MekaType.captionStrong : MekaType.caption).foregroundStyle(palette.textPrimary)
                            .contentTransition(.numericText())
                    }
                    .padding(.top, r.ruled ? MekaSpace.xxs : 0)
                    .accessibilityElement(children: .combine)
                }
                let tone = a.fits ? palette.success : palette.accent
                Text(a.verdict).font(MekaType.metaStrong).foregroundStyle(tone)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(tone.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: a.verdict)
                HStack(spacing: MekaSpace.xs) {
                    ForEach(plan.afford, id: \.months) { s in pill(s.label, on: s.months == a.months) { months = s.months } }
                }
            } else {
                Text(plan.affordNote ?? SpendPlanRules.shared.NO_RATE + ".").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if plan.foreign == nil {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(plan.months, id: \.label) { m in
                        VStack(alignment: .leading, spacing: 0) {
                            Text(m.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            Text(m.total).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary)
                            if !m.verdict.isEmpty {
                                Text(m.verdict).font(MekaType.caption)
                                    .foregroundStyle(m.tone.name == "FITS" ? palette.success : (m.tone.name == "SHORT" ? palette.accent : palette.textTertiary))
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(MekaSpace.xs)
                        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.top, MekaSpace.xs)
            }
        }
        .moneyCard(palette)
    }

    /// Count in my forecast: off until he turns it on; Money's forecast then takes off what's still to buy in its month.
    private func countCard(_ plan: SpendPlanDetail) -> some View {
        let id = plan.id
        return HStack(spacing: MekaSpace.m) {
            VStack(alignment: .leading, spacing: 0) {
                Text(SpendPlanRules.shared.COUNT_LABEL).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(plan.countLine).font(MekaType.caption)
                    .foregroundStyle(plan.counted ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: plan.countLine)
            }
            Spacer(minLength: MekaSpace.xs)
            Toggle(SpendPlanRules.shared.COUNT_LABEL, isOn: Binding(get: { plan.counted }, set: { model.setSpendPlanCounted(id, on: $0) }))
                .labelsHidden().toggleStyle(.switch).tint(palette.accent)
        }
        .moneyCard(palette)
    }

    private func sameCard(_ plan: SpendPlanDetail, _ f: SpendForeign) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(SpendPlanRules.shared.SAME_HEAD).font(MekaType.body).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.m) {
                stepButton("−", "Less each") { model.stepSpendPlanEach(plan.id, up: false) }
                Text(f.each).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).contentTransition(.numericText())
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: f.each)
                stepButton("+", "More each") { model.stepSpendPlanEach(plan.id, up: true) }
            }
            Text(f.rateLine).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            Button(SpendPlanRules.shared.CHANGE_RATE) { said = nil; rateText = f.rateText; rateOpen = true }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                .popover(isPresented: $rateOpen, arrowEdge: .trailing) {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text(SpendPlanRules.shared.RATE_SHEET).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        // Slice 3: today's looked-up rate first, his own below it (his wins while it's typed).
                        Text(f.todayLine).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(SpendPlanRules.shared.RATE_HELP).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        TextField("2,000", text: $rateText).textFieldStyle(.roundedBorder)
                            .accessibilityLabel("Your own rate, how many to one pound")
                        Text(SpendPlanRules.shared.RATE_SOURCE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        saidLine()
                        HStack {
                            Button(SpendPlanRules.shared.DONE) { rateOpen = false }
                            Spacer()
                            if f.canUseToday {
                                Button(SpendPlanRules.shared.USE_TODAY) {
                                    let id = plan.id
                                    Task {
                                        said = await model.useSpendPlanTodaysRate(id)
                                        if let s = said, !s.refused { rateOpen = false }
                                    }
                                }
                            }
                            Button("Save rate") {
                                let text = rateText
                                let id = plan.id
                                Task {
                                    said = await model.setSpendPlanRate(id, text)
                                    if let s = said, !s.refused { rateOpen = false }
                                }
                            }
                            .keyboardShortcut(.defaultAction)
                        }
                    }
                    .padding(MekaSpace.m)
                    .frame(width: 320)
                }
        }
        .moneyCard(palette)
    }

    private func stepButton(_ label: String, _ spoken: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.body).foregroundStyle(palette.accent)
                .frame(width: 32, height: 32)
                .background(palette.surfaceRaised, in: Circle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(spoken)
    }

    private func listCard(_ plan: SpendPlanDetail) -> some View {
        let size = Int(SpendPlanRules.shared.PAGE)
        let pages = max(1, (plan.rows.count + size - 1) / size)
        let p = min(max(page, 0), pages - 1)
        let shown = Array(plan.rows.dropFirst(p * size).prefix(size))
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Text("\(plan.listTitle) · \(plan.rows.count)").font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                if plan.canAdd {
                    Button(SpendPlanRules.shared.ADD) { openThing(nil) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .popover(isPresented: $addOpen, arrowEdge: .trailing) { thingPopover(plan) }
                }
            }
            if plan.rows.isEmpty {
                Text(plan.foreign != nil ? "Add the people you're sending to." : "Add what you'll buy, with how much and the month.")
                    .font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            ForEach(shown, id: \.id) { r in thingRow(r).transition(.opacity) }
            if pages > 1 {
                HStack {
                    let from = p * size + 1
                    Text("\(from)–\(min(plan.rows.count, from + size - 1)) of \(plan.rows.count)").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    stepButton("‹", "Previous five") { if p > 0 { MekaHaptics.tick(); page = p - 1 } }
                    stepButton("›", "Next five") { if p < pages - 1 { MekaHaptics.tick(); page = p + 1 } }
                }
            }
        }
        .moneyCard(palette)
    }

    private func openThing(_ r: SpendRow?) {
        said = nil
        name = r?.name ?? ""
        note = r?.noteText ?? ""
        amount = r?.amountText ?? ""
        month = r?.month ?? 0
        link = r?.link ?? ""
        if let r { editing = r } else { addOpen = true }
    }

    private func thingRow(_ r: SpendRow) -> some View {
        HStack(spacing: MekaSpace.m) {
            Button { model.setSpendThingDone(r.id, !r.done) } label: {
                ZStack {
                    Circle().strokeBorder(r.done ? palette.success : palette.textTertiary, lineWidth: 1.5)
                        .background(Circle().fill(r.done ? palette.success : Color.clear))
                    if r.done { Text("✓").font(MekaType.caption).foregroundStyle(palette.onAccent) }
                }
                .frame(width: 24, height: 24)
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(r.done ? "Done, click to untick" : "Tick when done")
            .animation(MekaMotion.appear(reduced: reduceMotion), value: r.done)
            Button { MekaHaptics.tick(); openThing(r) } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                        if !r.note.isEmpty { Text(r.note).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1) }
                        // Slice 4: the shop it's from; a click opens the page (only links the core checked reach here).
                        if !r.linkLabel.isEmpty {
                            Button { openLink(r.link) } label: {
                                Text("↗ \(r.linkLabel)").font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(1)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(SpendPlanRules.shared.OPEN_LINK), \(r.linkLabel)")
                        }
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(r.gbp).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        if !r.local.isEmpty { Text(r.local).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .popover(isPresented: Binding(get: { editing?.id == r.id }, set: { if !$0 { editing = nil } }), arrowEdge: .trailing) {
                if let plan = model.spendPlans?.plan(id: chosen) { thingPopover(plan) }
            }
        }
    }

    private func openLink(_ link: String) {
        guard !link.isEmpty, let url = URL(string: link), url.scheme == "https" else { return }
        MekaHaptics.light()
        NSWorkspace.shared.open(url)
    }

    private func thingPopover(_ plan: SpendPlanDetail) -> some View {
        let foreign = plan.foreign != nil
        let row = editing
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(row == nil ? SpendPlanRules.shared.ADD_SHEET : SpendPlanRules.shared.EDIT_SHEET).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            TextField(foreign ? "Mum" : "Football boots", text: $name).textFieldStyle(.roundedBorder)
            TextField(foreign ? "Note (optional)" : "For Sam (optional)", text: $note).textFieldStyle(.roundedBorder)
            TextField(foreign ? "Amount (blank = same for everyone)" : "How much, like 45", text: $amount).textFieldStyle(.roundedBorder)
            TextField(SpendPlanRules.shared.LINK_HINT, text: $link).textFieldStyle(.roundedBorder)
            if let row, !row.link.isEmpty {
                Button("\(SpendPlanRules.shared.OPEN_LINK) · \(row.linkLabel)") { openLink(row.link) }
                    .buttonStyle(.plain).font(MekaType.caption).foregroundStyle(palette.accent)
            }
            if !foreign, let choices = model.spendPlans?.monthChoices {
                Text("Buy in").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(choices, id: \.key) { m in pill(m.label, on: m.key == month) { month = m.key } }
                    }
                }
            }
            saidLine()
            HStack {
                if let row {
                    Button("Remove") { editing = nil; model.removeSpendThing(row.id) }
                }
                Button(SpendPlanRules.shared.DONE) { addOpen = false; editing = nil }
                Spacer()
                Button(row == nil ? "Add" : "Save") {
                    let n = name, t = note, a = amount, m = month, planId = plan.id, l = link
                    // Slice 4: a link Navilife wouldn't open is refused before anything is saved.
                    if SpendPlanRules.shared.link(text: l) == nil {
                        MekaHaptics.tick()
                        said = CoreModel.SpendPlanSaid(line: SpendPlanRules.shared.BAD_LINK, refused: true, id: nil)
                        return
                    }
                    let oldLink = row?.link
                    Task {
                        if let row {
                            said = await model.editSpendThing(row.id, name: n, note: t, amount: a, month: m)
                            if let s = said, !s.refused {
                                if l.trimmingCharacters(in: .whitespaces) != oldLink { _ = await model.setSpendThingLink(row.id, l) }
                                editing = nil
                            }
                        } else {
                            said = await model.addSpendThing(planId, name: n, note: t, amount: a, month: m)
                            if let s = said, !s.refused {
                                if let id = s.id, !l.trimmingCharacters(in: .whitespaces).isEmpty { _ = await model.setSpendThingLink(id, l) }
                                name = ""; note = ""; amount = ""; link = ""
                            }
                        }
                    }
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
    }
}

/// The bought/sent bar: grows to `fill` on the replan spring (at once with Reduce Motion).
private struct SpendBar: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let fill: Double
    let palette: MekaPalette
    @State private var shown = 0.0

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(palette.hairline)
                Capsule().fill(palette.accent).frame(width: g.size.width * shown)
            }
        }
        .frame(height: 6)
        .onAppear { if reduceMotion { shown = fill } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = fill } } }
        .onChange(of: fill) { _, f in withAnimation(MekaMotion.replan(reduced: reduceMotion)) { shown = f } }
        .accessibilityHidden(true)
    }
}

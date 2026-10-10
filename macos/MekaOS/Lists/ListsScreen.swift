@preconcurrency import MekaKit
import SwiftUI

/// The Lists tabs, in order (same as the Fold).
enum ListTab: Int, CaseIterable, Identifiable {
    case waiting, someday, decisions, renewals, shopping
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .waiting: "Waiting for"
        case .someday: "Someday"
        case .decisions: "Decisions"
        case .renewals: "Renewals"
        case .shopping: "Shopping"
        }
    }
}

/// LISTS on the Mac (build plan M1): Waiting for (chase dates), Someday (kinds), Decisions (review dates), Renewals
/// (the renewals and bills radar, `RenewalsSection.swift`) and Shopping (the shared list, `ShoppingSection.swift`), synced
/// with the Fold. A segmented control switches lists; clicking a row unfolds its actions (date presets are menus, as
/// rule 7 allows). "Got it" and "Do it now" make the row leave like a completion. Due chases and reviews are lit in the
/// accent colour. Nothing is chased, decided or promoted for you. Reduce Motion: cross-fades only.
struct ListsScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    @State private var tab: ListTab = .waiting
    @State private var open: String?

    static let kinds: [SomedayKind] = [.idea, .purchase, .project, .trip, .book, .research, .application, .homeImprovement, .other]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text("Lists")
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .staggeredAppear(0)
                Text(model.lists?.dueLine ?? "Nothing to chase, review or renew today.")
                    .font(MekaType.itemMeta)
                    .foregroundStyle(model.lists?.dueLine != nil ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(1)
                Picker("List", selection: Binding(get: { tab }, set: { new in
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.replan(reduced: reduceMotion)) { tab = new; open = nil }
                })) {
                    ForEach(ListTab.allCases) { t in Text(tabLabel(t)).tag(t) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 640)
                .padding(.bottom, MekaSpace.m)
                .staggeredAppear(2)

                Group {
                    switch tab {
                    case .waiting: waiting
                    case .someday: someday
                    case .decisions: decisions
                    case .renewals: RenewalsSection(palette: palette, open: $open)
                    case .shopping: ShoppingSection(palette: palette)
                    }
                }
                .id(tab)
                .transition(.opacity)

                Group {
                    switch tab {
                    case .waiting: AddWaitingRow(palette: palette)
                    case .someday: AddSomedayRow(palette: palette)
                    case .decisions: AddDecisionRow(palette: palette)
                    case .renewals: AddRenewalRow(palette: palette)
                    case .shopping: AddShoppingRow(palette: palette)
                    }
                }
                .padding(.top, MekaSpace.l)
            }
            .frame(maxWidth: 720, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutter)
            .padding(.vertical, MekaSpace.xl)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: rowIDs)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.background)
        .onAppear { takeOpenItem() }
        .onChange(of: model.openItem) { takeOpenItem() }
    }

    /// A search result opened here: switch to its tab and unfold its row.
    private func takeOpenItem() {
        guard let item = model.openItem, let t = SearchNav.listTab(item.target) else { return }
        tab = t
        open = item.id
        model.openItem = nil
    }

    private var rowIDs: [String] {
        guard let l = model.lists else { return [] }
        let renewalIDs: [String] = l.renewals.all.map { "\($0.id)|\($0.dueDay)" }
        let shopping: [ShoppingItem] = l.shopping.toBuy + l.shopping.got
        let shoppingIDs: [String] = shopping.map { "\($0.id)|\($0.got)" }
        return l.waiting.map(\.id) + l.someday.flatMap { $0.items.map(\.id) } + l.decisions.map(\.id) + renewalIDs + shoppingIDs
    }

    private func tabLabel(_ t: ListTab) -> String {
        guard let l = model.lists else { return t.label }
        let n: Int32 = switch t {
        case .waiting: Int32(l.waiting.count)
        case .someday: l.somedayCount
        case .decisions: Int32(l.decisions.count)
        case .renewals: l.renewals.count
        case .shopping: l.shopping.count
        }
        return n > 0 ? "\(t.label) \(n)" : t.label
    }

    private func toggle(_ id: String) { open = open == id ? nil : id }

    // MARK: Waiting for

    @ViewBuilder private var waiting: some View {
        let items = model.lists?.waiting ?? []
        if items.isEmpty {
            EmptyLine(text: "Nothing you're waiting on. Add a reply, a parcel or a refund below and MEKA tells you when to chase it.", palette: palette)
        }
        ForEach(items, id: \.id) { w in
            ListRowView(title: w.title, meta: w.meta, due: w.state == .due, expanded: open == w.id, palette: palette, toggle: { toggle(w.id) }) {
                if let notes = w.notes { Text(notes).font(MekaType.body).foregroundStyle(palette.textSecondary) }
                HStack(spacing: MekaSpace.l) {
                    Button("Chased") { model.chased(w.id) }
                    Button("Got it") { open = nil; model.received(w.id) }
                    DayMenu(title: "Chase", choices: ListRules.shared.CHASE_CHOICES) { model.setChase(w.id, days: $0) }
                    Button("Delete", role: .destructive) { open = nil; model.deleteWaiting(w.id) }
                }
                .rowActions(palette)
            }
        }
    }

    // MARK: Someday

    @ViewBuilder private var someday: some View {
        let groups = model.lists?.someday ?? []
        if groups.isEmpty {
            EmptyLine(text: "Ideas, trips, things to buy or read. They stay out of Today and the planner until you say \"Do it now\".", palette: palette)
        }
        ForEach(groups, id: \.label) { g in
            Text(g.label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.s)
            ForEach(g.items, id: \.id) { t in
                ListRowView(title: t.title, meta: nil, due: false, expanded: open == t.id, palette: palette, toggle: { toggle(t.id) }) {
                    if let notes = t.notes { Text(notes).font(MekaType.body).foregroundStyle(palette.textSecondary) }
                    HStack(spacing: MekaSpace.l) {
                        Button("Do it now") { open = nil; model.promote(t.id) }
                        Menu("Kind") {
                            ForEach(Self.kinds, id: \.self) { k in
                                Button(ListRules.shared.kindLabel(k: k)) { model.setSomedayKind(t.id, k) }
                            }
                        }
                        .menuStyle(.button).fixedSize()
                        Button("Delete", role: .destructive) { open = nil; model.deleteSomeday(t.id) }
                    }
                    .rowActions(palette)
                }
            }
        }
    }

    // MARK: Decisions

    @ViewBuilder private var decisions: some View {
        let items = model.lists?.decisions ?? []
        if items.isEmpty {
            EmptyLine(text: "Write down what you decided and why, so it isn't re-made. Add a review date if it should be looked at again.", palette: palette)
        }
        ForEach(items, id: \.id) { d in
            ListRowView(title: d.statement, meta: d.meta, due: d.state == .due, expanded: open == d.id, palette: palette, toggle: { toggle(d.id) }) {
                if let why = d.rationale { Text("Why: \(why)").font(MekaType.body).foregroundStyle(palette.textSecondary) }
                HStack(spacing: MekaSpace.l) {
                    DayMenu(title: d.state == .due ? "Still right…" : "Review", choices: ListRules.shared.REVIEW_CHOICES) {
                        model.keepDecision(d.id, againInDays: $0)
                    }
                    if d.status != .revisiting { Button("Revisit") { model.revisit(d.id) } }
                    Button("Delete", role: .destructive) { open = nil; model.deleteDecision(d.id) }
                }
                .rowActions(palette)
                ReplaceField(palette: palette) { s in open = nil; model.replaceDecision(d.id, with: s) }
            }
        }
    }
}

/// A list row: title and meta; clicking unfolds the actions in place.
struct ListRowView<Details: View>: View {
    @Environment(\.mekaReduceMotion) var reduceMotion
    let title: String
    let meta: String?
    let due: Bool
    let expanded: Bool
    let palette: MekaPalette
    let toggle: () -> Void
    @ViewBuilder let details: () -> Details

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Button(action: toggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    if let meta {
                        // A new date (a chase moved, a renewal rolled on) slides up into place.
                        Text(meta).font(MekaType.itemMeta).foregroundStyle(due ? palette.accent : palette.textSecondary)
                            .id(meta)
                            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .bottom).combined(with: .opacity))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityHint(expanded ? "Hides actions" : "Shows actions")
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.s) { details() }
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.s)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(expanded ? palette.surface : .clear))
        .transition(.opacity)
    }
}

struct EmptyLine: View {
    let text: String
    let palette: MekaPalette
    var body: some View {
        Text(text).font(MekaType.body).foregroundStyle(palette.textSecondary).padding(.vertical, MekaSpace.s)
    }
}

/// A menu of date presets ("Tomorrow", "In 3 days", …, "No date").
struct DayMenu: View {
    let title: String
    let choices: [DayChoice]
    let choose: (Int?) -> Void
    var body: some View {
        Menu(title) {
            ForEach(choices, id: \.label) { c in
                Button(c.label) { choose(c.days?.intValue) }
            }
        }
        .menuStyle(.button)
        .fixedSize()
    }
}

private struct ReplaceField: View {
    let palette: MekaPalette
    let submit: (String) -> Void
    @State private var text = ""
    var body: some View {
        TextField("Replace with… (what did you decide instead?)", text: $text)
            .textFieldStyle(.roundedBorder)
            .onSubmit { if !text.isEmpty { submit(text); text = "" } }
    }
}

private struct AddWaitingRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var title = ""
    @State private var who = ""
    @State private var chase: Int? = Int(ListRules.shared.DEFAULT_CHASE_DAYS)

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            TextField("Waiting for…", text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
            if !title.isEmpty {
                HStack(spacing: MekaSpace.m) {
                    TextField("From whom (optional)", text: $who).textFieldStyle(.roundedBorder).onSubmit(add)
                    Picker("Chase", selection: $chase) {
                        ForEach(ListRules.shared.CHASE_CHOICES, id: \.label) { c in Text(c.label).tag(c.days?.intValue) }
                    }
                    .fixedSize()
                    Button("Add", action: add).keyboardShortcut(.defaultAction)
                }
                .transition(.opacity)
            }
        }
    }

    private func add() {
        model.addWaiting(title, who: who, chaseInDays: chase)
        title = ""; who = ""; chase = Int(ListRules.shared.DEFAULT_CHASE_DAYS)
    }
}

private struct AddSomedayRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var title = ""
    @State private var kind: SomedayKind = .idea

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            TextField("Someday…", text: $title).textFieldStyle(.roundedBorder).onSubmit(add)
            Picker("Kind", selection: $kind) {
                ForEach(ListsScreen.kinds, id: \.self) { k in Text(ListRules.shared.kindLabel(k: k)).tag(k) }
            }
            .fixedSize()
        }
    }

    private func add() { model.addSomeday(title, kind: kind); title = "" }
}

private struct AddDecisionRow: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var statement = ""
    @State private var why = ""
    @State private var review: Int? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            TextField("I decided…", text: $statement).textFieldStyle(.roundedBorder).onSubmit(add)
            if !statement.isEmpty {
                HStack(spacing: MekaSpace.m) {
                    TextField("Why (optional)", text: $why).textFieldStyle(.roundedBorder).onSubmit(add)
                    Picker("Review", selection: $review) {
                        ForEach(ListRules.shared.REVIEW_CHOICES, id: \.label) { c in Text(c.label).tag(c.days?.intValue) }
                    }
                    .fixedSize()
                    Button("Add", action: add).keyboardShortcut(.defaultAction)
                }
                .transition(.opacity)
            }
        }
    }

    private func add() {
        model.recordDecision(statement, why: why, reviewInDays: review)
        statement = ""; why = ""; review = nil
    }
}

extension View {
    func rowActions(_ palette: MekaPalette) -> some View {
        buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
    }
}

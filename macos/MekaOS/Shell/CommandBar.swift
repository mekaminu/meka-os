@preconcurrency import MekaKit
import SwiftUI

/// The command bar (build plan M1, Outside the app): ⌘K from anywhere in MEKA's window. Type a few letters to go to a
/// place or do an everyday thing ("rev" → Review, "fast" → Start a fast); whatever is typed can also be searched for or
/// added as a task. The catalogue and its matching are the core's (`CommandBarRules`, non-AI); this maps each action
/// to what the same button elsewhere on the Mac already does, and nothing more.
/// Motion: the bar scale-fades in from 0.96 over a dimmed window; rows glide as results narrow; the lit row's
/// highlight glides between rows. Reduce Motion: cross-fades.
struct CommandBarOverlay: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(MekaAppearance.key) private var appearance = MekaAppearance.dark.rawValue
    let palette: MekaPalette
    @State private var query = ""
    @State private var lit = 0
    @FocusState private var fieldFocused: Bool
    @Namespace private var highlight

    private var results: CommandBarResults {
        CommandBarRules.shared.run(query: query, context: model.commandBarContext(appearance: appearance))
    }

    var body: some View {
        let results = results
        let rows = results.rows
        ZStack(alignment: .top) {
            Color.black.opacity(0.32)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { close() }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: MekaSpace.s) {
                    Image(systemName: "command").foregroundStyle(palette.accent)
                    TextField("Go to, do, find or add…", text: $query)
                        .textFieldStyle(.plain)
                        .font(MekaType.itemTitle)
                        .foregroundStyle(palette.textPrimary)
                        .focused($fieldFocused)
                        .onSubmit { if lit < rows.count { perform(rows[lit]) } }
                        .onKeyPress(.downArrow) { move(1, count: rows.count); return .handled }
                        .onKeyPress(.upArrow) { move(-1, count: rows.count); return .handled }
                        .onKeyPress(.escape) { close(); return .handled }
                        .onExitCommand { close() }
                        .accessibilityLabel("Command bar")
                }
                .padding(MekaSpace.m)
                Rectangle().fill(palette.hairline).frame(height: 1)
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 2) {
                            ForEach(sections(results)) { section in
                                if let label = section.label {
                                    Text(label.uppercased())
                                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                        .foregroundStyle(palette.textTertiary)
                                        .padding(.horizontal, MekaSpace.s)
                                        .padding(.top, MekaSpace.s)
                                }
                                ForEach(section.items) { item in
                                    CommandBarRowView(
                                        row: item.row, lit: item.index == lit, shortcut: CommandBarNav.shortcut(item.row.action),
                                        palette: palette, highlight: highlight
                                    )
                                    .id(item.index)
                                    .onHover { if $0 { lit = item.index } }
                                    .onTapGesture { perform(item.row) }
                                }
                            }
                        }
                        .padding(MekaSpace.xs)
                        .animation(MekaMotion.replan(reduced: reduceMotion), value: rows.map(\.title))
                    }
                    .frame(maxHeight: 380)
                    .onChange(of: lit) { _, new in proxy.scrollTo(new) }
                }
                Rectangle().fill(palette.hairline).frame(height: 1)
                Text("↑ ↓ to move · ↩ to run · esc to close")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            }
            .frame(width: 560)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).stroke(palette.hairline, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: MekaRadius.m))
            .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
            .padding(.top, 72)
        }
        .onAppear { fieldFocused = true }
        .onChange(of: query) { lit = 0 }
    }

    /// The groups with each row's place in the whole list (↑ ↓ and Return work across the headings).
    private func sections(_ results: CommandBarResults) -> [CommandBarSectionItems] {
        var index = 0
        var out: [CommandBarSectionItems] = []
        for (i, g) in results.groups.enumerated() {
            var items: [CommandBarItem] = []
            for row in g.rows {
                items.append(CommandBarItem(index: index, row: row))
                index += 1
            }
            out.append(CommandBarSectionItems(id: i, label: g.label, items: items))
        }
        return out
    }

    private func move(_ by: Int, count: Int) {
        guard count > 0 else { return }
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { lit = (lit + by + count) % count }
    }

    private func perform(_ row: CommandBarRow) {
        MekaHaptics.tick()
        close()
        if let a = CommandBarNav.appearance(row.action) {
            appearance = a.rawValue
            return
        }
        model.perform(row, reduced: reduceMotion)
    }

    private func close() {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { model.showCommandBar = false }
    }
}

private struct CommandBarItem: Identifiable {
    let index: Int
    let row: CommandBarRow
    var id: Int { index }
}

private struct CommandBarSectionItems: Identifiable {
    let id: Int
    let label: String?
    let items: [CommandBarItem]
}

/// A row: the title, its line when it has one, and the Mac shortcut for the same thing on the right.
private struct CommandBarRowView: View {
    let row: CommandBarRow
    let lit: Bool
    let shortcut: String?
    let palette: MekaPalette
    let highlight: Namespace.ID

    var body: some View {
        HStack(spacing: MekaSpace.s) {
            Image(systemName: CommandBarNav.symbol(row.action))
                .frame(width: 20)
                .foregroundStyle(lit ? palette.accent : palette.textSecondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                if let detail = row.detail {
                    Text(detail).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                }
            }
            Spacer(minLength: MekaSpace.s)
            if let shortcut {
                Text(shortcut).font(MekaType.caption).monospaced().foregroundStyle(palette.textTertiary)
            }
        }
        .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.s)
        .background {
            if lit {
                RoundedRectangle(cornerRadius: MekaRadius.s)
                    .fill(palette.surface)
                    .matchedGeometryEffect(id: "lit", in: highlight)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAddTraits(lit ? .isSelected : AccessibilityTraits())
    }
}

/// What the Mac shows beside each command: its symbol and the shortcut that does the same (Go menu ⌘1–⌘8 etc.).
enum CommandBarNav {
    static func shortcut(_ a: CommandBarAction) -> String? {
        switch a {
        case .goToday: "⌘1"
        case .goNeedsYou: "⌘2"
        case .goCalendar: "⌘3"
        case .goAsk: "⌘4"
        case .goLists: "⌘5"
        case .goGoals: "⌘6"
        case .goReview: "⌘7"
        case .goVault: "⌘8"
        case .syncNow: "⌘R"
        case .activity: "⇧⌘A"
        case .yourData: "⇧⌘E"
        case .searchFor: "⌘F"
        default: nil
        }
    }

    static func symbol(_ a: CommandBarAction) -> String {
        switch a {
        case .goToday: ShellDestination.today.symbol
        case .goNeedsYou: ShellDestination.needsYou.symbol
        case .goCalendar: ShellDestination.calendar.symbol
        case .goAsk: ShellDestination.ask.symbol
        case .goLists: ShellDestination.lists.symbol
        case .goGoals: ShellDestination.goals.symbol
        case .goReview: ShellDestination.review.symbol
        case .goVault: ShellDestination.vault.symbol
        case .planDay: "calendar.day.timeline.left"
        case .morningBrief: "sun.horizon"
        case .shutDown: "moon"
        case .syncNow: "arrow.triangle.2.circlepath"
        case .workStart, .workFinish, .workMode: "briefcase"
        case .fastStart, .fastEnd: "circle.lefthalf.filled"
        case .notifications: "bell"
        case .activity: "clock.arrow.circlepath"
        case .yourData: "square.and.arrow.down"
        case .calendars: "person.crop.circle.badge.plus"
        case .appearanceDark, .appearanceLight, .appearanceAuto: "circle.righthalf.filled"
        case .searchFor: "magnifyingglass"
        case .addTask: "plus.circle"
        default: "circle"
        }
    }

    /// The appearance a command switches to (kept in the app's own setting, not synced); nil for other commands.
    static func appearance(_ a: CommandBarAction) -> MekaAppearance? {
        switch a {
        case .appearanceDark: .dark
        case .appearanceLight: .light
        case .appearanceAuto: .system
        default: nil
        }
    }

    /// The shell destination a Go command opens; nil for other commands.
    static func destination(_ a: CommandBarAction) -> ShellDestination? {
        switch a {
        case .goToday: .today
        case .goNeedsYou: .needsYou
        case .goCalendar: .calendar
        case .goAsk: .ask
        case .goLists: .lists
        case .goGoals: .goals
        case .goReview: .review
        case .goVault: .vault
        default: nil
        }
    }
}

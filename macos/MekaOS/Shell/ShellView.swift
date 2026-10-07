@preconcurrency import MekaKit
import SwiftUI

/// The Mac shell (build plan M1; Four tabs, one front door): a sidebar with the four tabs (Today · Needs you · Calendar ·
/// Ask) and, below them, a More section with the places that sit behind Ask (Lists · Goals · Review · Vault; the Mac
/// has the room, as rule 7 allows), beside the destination. Switching pushes the content the way you moved down the
/// sidebar; Reduce Motion cross-fades.
struct ShellView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        NavigationSplitView {
            List(selection: selection) {
                ForEach(ShellNav.destinations(.rail)) { d in
                    Label(d.label, systemImage: d.symbol)
                        .badge(d == .needsYou ? ShellNav.badge(needsYouCount).map { Text($0) } : nil)
                        .accessibilityLabel(ShellNav.accessibilityLabel(d, needsYouCount: needsYouCount))
                        .tag(d as ShellDestination?)
                }
                Section("More") {
                    ForEach(ShellDestination.allCases.filter { ShellNav.parent($0) != nil }) { d in
                        Label(d.label, systemImage: d.symbol).tag(d as ShellDestination?)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 200, max: 260)
        } detail: {
            ZStack {
                destination(model.destination)
                    .id(model.destination)
                    .transition(transition)
            }
            .clipped()
            // Calendar actions: "Hidden from your day · Undo" rises over whichever screen you did it on.
            .overlay(alignment: .bottom) { EventUndoBar(palette: palette).padding(.bottom, MekaSpace.xxl) }
        }
        // The ⌘K command bar, over the whole window (sidebar included).
        .overlay {
            if model.showCommandBar {
                CommandBarOverlay(palette: palette)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .scale(scale: 0.96, anchor: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.showCommandBar)
        .background(palette.background)
        // Search everything (⌘F from anywhere): results, with an open task's detail beside them.
        .sheet(isPresented: Binding(get: { model.showSearch }, set: { model.showSearch = $0 })) { SearchSheet(palette: palette) }
        // Event detail (calendar redesign, slice 3), opened from Today or the Calendar section.
        .sheet(isPresented: Binding(get: { model.openEvent != nil }, set: { if !$0 { model.openEvent = nil } })) {
            EventDetailSheet(palette: palette)
        }
        // Self-updating phone app: publish the APK this Mac built for the Fold to offer.
        .sheet(isPresented: Binding(get: { model.showFoldUpdate }, set: { model.showFoldUpdate = $0 })) { FoldUpdateSheet(palette: palette) }
        // Export everything (your data), from Today's header or File → Export All Data….
        .sheet(isPresented: Binding(get: { model.showYourData }, set: { model.showYourData = $0 })) { YourDataSheet(palette: palette) }
        .sheet(isPresented: Binding(get: { model.showActivity }, set: { model.showActivity = $0 })) { ActivitySheet(palette: palette) }
        // Opened from Today's header or Ask's More list, so they live on the shell.
        .sheet(isPresented: Binding(get: { model.showCalendars }, set: { model.showCalendars = $0 })) { CalendarsSheet(palette: palette) }
        .sheet(isPresented: Binding(get: { model.showWork }, set: { model.showWork = $0 })) { WorkSheet(palette: palette) }
        .sheet(isPresented: Binding(get: { model.showNotifications }, set: { model.showNotifications = $0 })) {
            NotificationsSheet(palette: palette)
        }
    }

    /// Due chases and decision reviews wait on you too, so they count in the badge (as on the Fold).
    private var needsYouCount: Int { (model.today?.needsYou.count ?? 0) + model.listsDue }

    /// Sidebar selection that animates the switch (the List only ever sets a value; nil is ignored).
    private var selection: Binding<ShellDestination?> {
        Binding(
            get: { model.destination },
            set: { new in if let new { model.go(to: new, reduced: reduceMotion) } }
        )
    }

    private var transition: AnyTransition {
        if reduceMotion || model.lastDirection == 0 { return .opacity }
        // Moving down the sidebar, the new screen arrives from below and the old one leaves upwards.
        return .push(from: model.lastDirection > 0 ? .bottom : .top).combined(with: .opacity)
    }

    @ViewBuilder
    private func destination(_ d: ShellDestination) -> some View {
        switch d {
        case .today: TodayView()
        case .needsYou: NeedsYouView()
        case .calendar: CalendarScreen()
        case .ask: AskScreen(palette: palette)
        case .lists: ListsScreen()
        case .goals: GoalsScreen()
        case .review: ReviewScreen()
        case .vault: VaultScreen(palette: palette)
        }
    }
}

/// NEEDS YOU on the Mac: a stack of decisions beside the detail (four tabs, slice 2): conflicts, overdue, due today
/// but unscheduled, then "From your lists". → yes/do, ← later, ↑ open (keys, buttons or a drag). Approvals join in V1.
struct NeedsYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        HSplitView {
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("Needs you")
                        .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                        .foregroundStyle(palette.textPrimary)
                        .padding(.bottom, MekaSpace.l)
                        .staggeredAppear(0)
                    if model.needsYouCards.isEmpty {
                        Text("Nothing is waiting on you.")
                            .font(MekaType.upNextTitle).foregroundStyle(palette.textSecondary)
                            .staggeredAppear(1)
                    } else {
                        NeedsYouStackView(palette: palette).staggeredAppear(1)
                    }
                }
                .padding(.horizontal, MekaSpace.gutter)
                .padding(.vertical, MekaSpace.xl)
            }
            .frame(minWidth: 380, idealWidth: 520)
            DetailView(task: model.selected, palette: palette)
                .frame(minWidth: 280, idealWidth: 360)
        }
        .background(palette.background)
    }
}

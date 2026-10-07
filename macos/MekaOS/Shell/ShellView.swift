@preconcurrency import MekaKit
import SwiftUI

/// The Mac shell (build plan M1): a sidebar (Today · Calendar · Needs you · Lists · Goals · Review · Vault) beside the
/// destination. Switching pushes the content the way you moved down the sidebar; Reduce Motion cross-fades.
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
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 200, max: 260)
        } detail: {
            ZStack {
                destination(model.destination)
                    .id(model.destination)
                    .transition(transition)
            }
            .clipped()
        }
        .background(palette.background)
        // Search everything (⌘F from anywhere): results, with an open task's detail beside them.
        .sheet(isPresented: Binding(get: { model.showSearch }, set: { model.showSearch = $0 })) { SearchSheet(palette: palette) }
        // Event detail (calendar redesign, slice 3), opened from Today or the Calendar section.
        .sheet(isPresented: Binding(get: { model.openEvent != nil }, set: { if !$0 { model.openEvent = nil } })) {
            EventDetailSheet(palette: palette)
        }
        // Self-updating phone app: publish the APK this Mac built for the Fold to offer.
        .sheet(isPresented: Binding(get: { model.showFoldUpdate }, set: { model.showFoldUpdate = $0 })) { FoldUpdateSheet(palette: palette) }
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
        case .calendar: CalendarScreen()
        case .needsYou: NeedsYouView()
        case .lists: ListsScreen()
        case .goals: GoalsScreen()
        case .review: ReviewScreen()
        default: UpcomingView(destination: d, palette: palette)
        }
    }
}

/// A calm placeholder for destinations whose feature hasn't landed yet: says what's coming, nothing to click.
private struct UpcomingView: View {
    let destination: ShellDestination
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text(destination.label)
                .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                .foregroundStyle(palette.textPrimary)
                .staggeredAppear(0)
            Text(ShellNav.upcomingLine(destination) ?? "")
                .font(MekaType.body).foregroundStyle(palette.textSecondary)
                .frame(maxWidth: 480, alignment: .leading)
                .staggeredAppear(1)
            Spacer()
        }
        .padding(.horizontal, MekaSpace.gutter)
        .padding(.vertical, MekaSpace.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.background)
    }
}

/// NEEDS YOU on the Mac: everything waiting on a decision, beside the detail. Approvals join this list in V1.
struct NeedsYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }
    @Namespace private var selection

    var body: some View {
        HSplitView {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("Needs you")
                        .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                        .foregroundStyle(palette.textPrimary)
                        .padding(.bottom, MekaSpace.l)
                        .staggeredAppear(0)
                    let items = model.today?.needsYou ?? []
                    if let line = model.lists?.dueLine {
                        Button { model.go(to: .lists, reduced: reduceMotion) } label: {
                            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                Text("From your lists").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                                Text(line).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(MekaSpace.m)
                            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens Lists")
                        .padding(.bottom, MekaSpace.s)
                        .staggeredAppear(1)
                    }
                    if items.isEmpty && model.lists?.dueLine == nil {
                        Text("Nothing is waiting on you.")
                            .font(MekaType.upNextTitle).foregroundStyle(palette.textSecondary)
                            .staggeredAppear(1)
                    }
                    ForEach(items, id: \.task.id) { item in
                        TaskRow(task: item.task, reason: item.reason, palette: palette).staggeredAppear(1)
                    }
                }
                .padding(.horizontal, MekaSpace.gutter)
                .padding(.vertical, MekaSpace.xl)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.needsYou.map(\.task.id))
            }
            .environment(\.selectionNamespace, selection)
            .frame(minWidth: 380, idealWidth: 520)
            DetailView(task: model.selected, palette: palette)
                .frame(minWidth: 280, idealWidth: 360)
        }
        .background(palette.background)
    }
}

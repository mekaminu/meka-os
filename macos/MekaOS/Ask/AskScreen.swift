import SwiftUI

/// ASK on the Mac (build plan M1, Four tabs, one front door): the front door to everything that isn't a tab. Until the
/// AI layer lands, asking is searching (the field opens Search Everything, as ⌘F does). More lists the places behind
/// Ask (they open with the shell's push and keep Ask lit in spirit: the sidebar shows them under More) and the sheets.
/// Motion: title, field and rows stagger in; rows lift 2 pt on hover; a lit Lists line blends its colour.
struct AskScreen: View {
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(ShellDestination.ask.label)
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(0)
                Button { model.showSearch = true } label: {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(palette.textTertiary)
                        Text("Search everything").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        Spacer()
                        Text("⌘F").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    .padding(.horizontal, MekaSpace.l)
                    .frame(minHeight: 44)
                    .background(palette.surfaceRaised, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Search everything")
                .staggeredAppear(1)
                Text("Tasks, events, lists, goals and habits. Asking in your own words comes with the AI layer.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.leading, MekaSpace.xxs)
                    .padding(.bottom, MekaSpace.l)
                    .staggeredAppear(1)
                Text("MORE")
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                    .foregroundStyle(palette.textTertiary)
                    .staggeredAppear(2)
                ForEach(Array(ShellNav.more(connected: model.isConnected && !model.signedOut).enumerated()), id: \.element) { i, item in
                    MoreRow(item: item, listsDue: model.listsDue, palette: palette) { open(item) }
                        .staggeredAppear(3 + i)
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutter)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(palette.background)
    }

    private func open(_ item: MoreItem) {
        if let d = item.destination { model.go(to: d, reduced: reduceMotion); return }
        switch item {
        case .work: model.showWork = true
        case .notifications: model.showNotifications = true
        case .activity: model.showActivity = true
        case .yourData: model.showYourData = true
        case .calendars: model.showCalendars = true
        case .lists, .goals, .review, .vault: break
        }
    }
}

private struct MoreRow: View {
    let item: MoreItem
    let listsDue: Int
    let palette: MekaPalette
    let action: () -> Void
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    // Places and settings you open, not things you act on: the regular weight (type weight, 2026-10-06).
                    Text(item.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    Text(ShellNav.moreLine(item, listsDue: listsDue))
                        .font(MekaType.caption)
                        .foregroundStyle(ShellNav.moreLit(item, listsDue: listsDue) ? palette.accent : palette.textSecondary)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: listsDue)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(palette.textTertiary)
            }
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.s)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
            .offset(y: hovering && !reduceMotion ? -2 : 0)
        }
        .buttonStyle(MekaPressStyle())
        .onHover { h in withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h } }
        .accessibilityHint(item.destination == nil ? "Opens a sheet" : "Opens \(item.label)")
    }
}

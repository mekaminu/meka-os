@preconcurrency import MekaKit
import SwiftUI

/// The command centre beside Today (Fold modes, slice 2), matching android/.../today/CommandCentre.kt: the open task's
/// detail, or Needs you (the decision stack, with its count) over Coming up. Opening a task cross-fades its detail into
/// Needs you's place; Close brings Needs you back. Reduce Motion: cross-fades (the default here anyway).
struct CommandSideView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let column: CommandColumn
    let palette: MekaPalette

    var body: some View {
        ZStack {
            if column == CommandColumn.detail {
                VStack(alignment: .leading, spacing: 0) {
                    if model.selected != nil {
                        Button("Close") { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { model.selectedID = nil } }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemMeta)
                            .foregroundStyle(palette.accent)
                            .keyboardShortcut(.cancelAction)
                            .padding(.horizontal, MekaSpace.gutter)
                            .padding(.top, MekaSpace.s)
                    }
                    DetailView(task: model.selected, palette: palette)
                }
                .transition(.opacity)
            } else if column == CommandColumn.needsYou {
                CommandNeedsYouView(palette: palette)
                    .transition(.opacity)
            } else {
                VSplitView {
                    CommandNeedsYouView(palette: palette)
                        .frame(minHeight: 220, idealHeight: 380)
                    ComingUpColumnView(shared: true, palette: palette)
                        .frame(minHeight: 160, idealHeight: 280)
                }
                .transition(.opacity)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: column.name)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.background)
    }
}

/// Needs you, compact: "NEEDS YOU · 3", the after-work line if any, then the stack (it doesn't take the keyboard
/// here, so Today's own keys keep working; click a card to use ← → ↑).
struct CommandNeedsYouView: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                SectionLabel(CommandCentreRules.shared.needsYouHeading(count: Int32(model.needsYouCards.count)), palette)
                    .staggeredAppear(0)
                if model.needsYouCards.isEmpty {
                    Text(NeedsYouStackRules.shared.EMPTY_LINE)
                        .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        .staggeredAppear(1)
                } else {
                    NeedsYouStackView(palette: palette, autofocus: false).staggeredAppear(1)
                }
            }
            .padding(.horizontal, MekaSpace.gutter)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Coming up: the days after today with something on them, a few lines each ("+2 more"), then "Open Calendar ›".
/// Clicking an event opens its detail sheet; a day's heading opens the Calendar section. Sections stagger in.
/// Below it, News (news ticker, slice 2): today's match in Barça's colour (click for its detail), the top story of
/// each lane (click opens the News sheet on that story), "Open News ›". Left out when there's nothing to show.
struct ComingUpColumnView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let shared: Bool
    let palette: MekaPalette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                SectionLabel("Coming up", palette).staggeredAppear(0)
                if let v = model.calendar {
                    let c = CommandCentreRules.shared.comingUp(
                        view: v,
                        maxDays: shared ? CommandCentreRules.shared.DAYS_SHARED : CommandCentreRules.shared.DAYS_ALONE,
                        maxLines: shared ? CommandCentreRules.shared.LINES_SHARED : CommandCentreRules.shared.LINES_ALONE
                    )
                    if let empty = c.emptyLine {
                        Text(empty).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).staggeredAppear(1)
                    }
                    ForEach(Array(c.days.enumerated()), id: \.element.id) { i, day in
                        dayView(day).staggeredAppear(1 + i)
                    }
                    Button("Open Calendar ›") { model.go(to: .calendar, reduced: reduceMotion) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                        .padding(.top, MekaSpace.s)
                        .staggeredAppear(1 + c.days.count)
                }
                if let place = model.newsPlace,
                   let g = CommandCentreRules.shared.newsGlance(
                       place: place,
                       maxItems: shared ? CommandCentreRules.shared.NEWS_SHARED : CommandCentreRules.shared.NEWS_ALONE
                   ) {
                    newsView(g).padding(.top, MekaSpace.xl).staggeredAppear(2)
                }
            }
            .padding(.horizontal, MekaSpace.gutter)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func dayView(_ day: ComingUpDay) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button { model.go(to: .calendar, reduced: reduceMotion) } label: {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(day.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    if let sub = day.subtitle {
                        Text(sub).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
            }
            .buttonStyle(MekaPressStyle())
            ForEach(day.lines, id: \.id) { line in
                lineView(line)
            }
            if let more = day.moreLine {
                Text(more).font(MekaType.caption).foregroundStyle(palette.textTertiary).padding(.leading, 84)
            }
        }
        .padding(.bottom, MekaSpace.s)
    }

    private func newsView(_ g: NewsGlance) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            SectionLabel("News", palette)
            if let md = g.matchday {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.s) {
                    Circle().fill(palette.barca).frame(width: 6, height: 6)
                    Text(md.line).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(2)
                        .id(md.line).transition(.opacity)
                    Spacer(minLength: 0)
                }
                .animation(MekaMotion.appear(reduced: reduceMotion), value: md.line)
                .contentShape(Rectangle())
                .onTapGesture { model.openEvent = md.event }
                .accessibilityAddTraits(.isButton)
            }
            ForEach(g.items, id: \.id) { n in
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.s) {
                    Circle().fill(n.topic == "barca" ? palette.barca : palette.textTertiary).frame(width: 6, height: 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(n.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2)
                        Text(n.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 2)
                .contentShape(Rectangle())
                .onTapGesture {
                    MekaHaptics.tick()
                    model.newsStoryId = n.id
                    model.showNews = true
                }
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Opens the story")
            }
            Button("Open News ›") { model.newsStoryId = nil; model.showNews = true }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.top, MekaSpace.s)
        }
    }

    private func lineView(_ line: ComingUpLine) -> some View {
        let fixture = line.event?.isFixture == true
        return HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(line.time)
                .font(MekaType.caption)
                .foregroundStyle(fixture ? palette.accent : palette.textTertiary)
                .lineLimit(1)
                .frame(width: 84, alignment: .leading)
            Text(line.title)
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.textPrimary)
                .lineLimit(2)
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .onTapGesture { if let e = line.event { model.openEvent = e } }
    }
}

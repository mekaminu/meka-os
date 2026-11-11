import SwiftUI
@preconcurrency import MekaKit

// Needs you grouped and folding on the Mac (APPROVED 2026-11-10 21:51, docs/design/needs-you/needs-you-grouped.dc.html),
// slice 4: every section one box with its icon, title, count and a one-line peek; a click opens or closes it (the chevron
// turns half a turn on the expand spring, the rows fade in); To decide and Messages open at first, and this Mac remembers
// what he leaves open. Every line and count is the core's (NeedsYouGroupRules), as on the Fold.

/// What he left open, kept on this Mac (display state only, never synced): the core's comma list of group ids.
enum NeedsYouOpenGroups {
    static let key = "needsYou.openGroups"

    /// To decide and Messages before he has opened or closed anything (nil).
    static func decode(_ saved: String?) -> Set<NeedsYouGroup> { NeedsYouGroupRules.shared.decode(saved: saved) }
    static func encode(_ open: Set<NeedsYouGroup>) -> String { NeedsYouGroupRules.shared.encode(open: open) }
}

extension NeedsYouGroup {
    /// The mockup's icon as the nearest SF Symbol (the Fold draws the core's stroke path).
    var symbol: String {
        switch self {
        case .decide: "exclamationmark.triangle"
        case .messages: "bubble.left"
        case .fyi: "info.circle"
        case .chats: "person.2"
        case .mail: "envelope"
        case .renewals: "arrow.clockwise"
        default: "trash"
        }
    }

    /// The group's ink (icon and badge), as the mockup's.
    func ink(_ palette: MekaPalette) -> Color {
        switch self {
        case .decide, .renewals: palette.approval
        case .messages: palette.calendar1
        case .fyi, .chats: palette.textSecondary
        case .mail: palette.accent
        default: palette.success
        }
    }
}

/// A box's head: the icon tile, the title with its count, the peek under it and the chevron. A click (light haptic)
/// opens or closes it; the bottom corners square off while open, so the box reads as one with what it holds. VoiceOver
/// reads the core's spoken line and "Open" / "Closed".
struct NeedsYouGroupHeadView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let head: NeedsYouGroupHead
    let palette: MekaPalette
    let onToggle: () -> Void

    var body: some View {
        let ink = head.group.ink(palette)
        let foot: CGFloat = head.open ? 0 : MekaRadius.m
        Button {
            MekaHaptics.light()
            onToggle()
        } label: {
            HStack(spacing: MekaSpace.xs) {
                Image(systemName: head.group.symbol)
                    .font(MekaType.metaStrong).foregroundStyle(ink)
                    .frame(width: 30, height: 30)
                    .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s, style: .continuous))
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack(spacing: MekaSpace.xs) {
                        Text(head.title).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).lineLimit(1)
                        if !head.count.isEmpty {
                            Text(head.count)
                                .font(MekaType.captionStrong).foregroundStyle(ink)
                                .padding(.horizontal, MekaSpace.xs)
                                .background(palette.surfaceRaised, in: Capsule())
                                .contentTransition(.numericText())
                        }
                    }
                    if !head.peek.isEmpty {
                        Text(head.peek).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                            .lineLimit(1).truncationMode(.tail)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.down")
                    .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                    .rotationEffect(.degrees(head.open ? 180 : 0))
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .frame(minHeight: 48)
            .background(palette.surface, in: UnevenRoundedRectangle(
                topLeadingRadius: MekaRadius.m, bottomLeadingRadius: foot, bottomTrailingRadius: foot,
                topTrailingRadius: MekaRadius.m, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .animation(MekaMotion.expand(reduced: reduceMotion), value: head.open)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(head.spoken)
        .accessibilityValue(head.open ? "Open" : "Closed")
        .accessibilityAddTraits([.isButton, .isHeader])
        .accessibilityIdentifier("needs-you-group-\(head.group.id)")
    }
}

/// The quiet line at the top of a group with its gold button: "Nothing here needs a reply" · Clear all, or
/// "2 to chase · from your lists" · Go through.
struct NeedsYouGroupLeadView: View {
    let line: String
    let button: String
    let palette: MekaPalette
    let action: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
            Button {
                MekaHaptics.light()
                action()
            } label: {
                Text(button).font(MekaType.captionStrong).foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).frame(minHeight: 28)
                    .background(palette.accent, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(MekaPressStyle())
        }
        .padding(.leading, MekaSpace.m).padding(.trailing, MekaSpace.xxs).padding(.vertical, MekaSpace.xxs)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s, style: .continuous))
    }
}

/// To decide's gold foot line, where the "While you were at work" card was: a click opens the after-work summary;
/// nothing is cleared or marked read.
struct NeedsYouGroupFootView: View {
    let line: String
    let palette: MekaPalette
    let action: () -> Void

    var body: some View {
        Button {
            MekaHaptics.light()
            action()
        } label: {
            Text(line).font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .padding(.horizontal, MekaSpace.xxs)
        .accessibilityIdentifier("needs-you-foot")
    }
}

/// Under a long group: "1–5 of 14", a dot per page (the current one a wider gold bar, gliding on the expand spring)
/// and ‹ › (a tick; dimmed at either end).
struct NeedsYouPagerView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let page: NeedsYouPage
    let palette: MekaPalette
    let onPage: (Int) -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Text(page.label).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: MekaSpace.xxs) {
                ForEach(0..<Int(page.pages), id: \.self) { i in
                    Capsule().fill(i == Int(page.page) ? palette.accent : palette.textTertiary.opacity(0.5))
                        .frame(width: i == Int(page.page) ? 14 : 5, height: 5)
                }
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: page.page)
            .accessibilityHidden(true)
            arrow("chevron.left", enabled: page.hasPrev, label: "Previous page") { onPage(Int(page.page) - 1) }
            arrow("chevron.right", enabled: page.hasNext, label: "Next page") { onPage(Int(page.page) + 1) }
        }
        .padding(.leading, MekaSpace.xxs)
        .accessibilityIdentifier("needs-you-pager")
    }

    private func arrow(_ symbol: String, enabled: Bool, label: String, _ go: @escaping () -> Void) -> some View {
        Button {
            MekaHaptics.tick()
            go()
        } label: {
            Image(systemName: symbol).font(MekaType.captionStrong)
                .foregroundStyle(enabled ? palette.textPrimary : palette.textTertiary.opacity(0.5))
                .frame(width: 28, height: 28).contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// The rows of [rows] on [page] (the core's `of` is generic, which Swift can't call with its type).
func needsYouPageRows<T>(_ rows: [T], _ page: NeedsYouPage) -> [T] {
    let from = min(Int(page.from), rows.count)
    let to = min(Int(page.to), rows.count)
    return from < to ? Array(rows[from..<to]) : []
}

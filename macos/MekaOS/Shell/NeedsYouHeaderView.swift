import SwiftUI
@preconcurrency import MekaKit

/// The counts Needs you's header and chips show on the Mac (the approved look, `docs/design/needs-you/needs-you.dc.html`,
/// as the Fold's slice 1): read from the core's NeedsYouLayoutRules so both apps say the same thing. To decide counts
/// the approvals, the requests outside a chat, the school / date-night / appointment cards and the stack; to reply the
/// people or groups waiting on him; FYI the chats needing nothing; Mail the mail card's own figure.
struct NeedsYouHeaderCounts {
    let decide: Int
    let reply: Int
    let fyi: Int
    let mail: Int

    init(decide: Int, chats: [TriageChat], mail: Int) {
        self.decide = decide
        reply = Int(NeedsYouLayoutRules.shared.replyCount(chats: chats))
        fyi = NeedsYouLayoutRules.shared.fyi(chats: chats).count
        self.mail = mail
    }

    /// "2 to decide · 4 to reply · 4 FYI", or "All clear".
    var subtitle: String {
        NeedsYouLayoutRules.shared.subtitle(decide: Int32(decide), reply: Int32(reply), fyi: Int32(fyi))
    }

    /// All · Decide 2 · Reply 4 · FYI 4 · Mail 14.
    var chips: [NeedsYouChip] {
        NeedsYouLayoutRules.shared.chips(decide: Int32(decide), reply: Int32(reply), fyi: Int32(fyi), mail: Int32(mail))
    }

    /// Clear FYI shows only while there is FYI to clear.
    var showsClear: Bool { fyi > 0 }

    /// Whether [section] shows under the chosen chip (All shows everything).
    static func shows(_ filter: NeedsYouFilter, _ section: NeedsYouSection) -> Bool {
        NeedsYouLayoutRules.shared.shows(filter: filter, section: section)
    }
}

/// "Needs you" over its counts, with Clear FYI at the right while there is FYI (a quiet text button; the undo bar
/// then says what it cleared).
struct NeedsYouHeaderView: View {
    let palette: MekaPalette
    let counts: NeedsYouHeaderCounts
    let onClearFyi: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: MekaSpace.m) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("Needs you")
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text(counts.subtitle)
                    .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.numericText())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if counts.showsClear {
                Button(NeedsYouLayoutRules.shared.CLEAR_FYI, action: onClearFyi)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                    .contentShape(Rectangle())
                    .help("Mark every chat that needs no reply as done")
                    .transition(.opacity)
            }
        }
    }
}

/// All · Decide 2 · Reply 4 · FYI 4 · Mail: one line of chips, the chosen one gold (the colour blends on the theme
/// spring; instant with reduced motion). Each shows only its section; All everything. ⌥⌘1–5 pick them in order.
struct NeedsYouChipsView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let chips: [NeedsYouChip]
    @Binding var chosen: NeedsYouFilter

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(chips.enumerated()), id: \.offset) { i, chip in
                    let on = chip.filter == chosen
                    Button {
                        guard !on else { return }
                        MekaHaptics.light()
                        withAnimation(MekaMotion.themeBlend(reduced: reduceMotion)) { chosen = chip.filter }
                    } label: {
                        Text(chip.label)
                            .font(on ? MekaType.captionStrong : MekaType.caption).lineLimit(1)
                            .foregroundStyle(on ? palette.onAccent : palette.textPrimary)
                            .padding(.horizontal, MekaSpace.m).frame(minHeight: 28)
                            .background(on ? palette.accent : palette.surface, in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .keyboardShortcut(KeyEquivalent(Character(String(i + 1))), modifiers: [.option, .command])
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
        }
    }
}

/// A section's quiet label: "TO DECIDE · 2", "FYI · NO REPLY NEEDED · 4".
struct NeedsYouSectionHead: View {
    let palette: MekaPalette
    let text: String

    var body: some View {
        Text(text)
            .font(MekaType.captionStrong).tracking(MekaType.captionStrongTracking)
            .foregroundStyle(palette.textTertiary)
            .accessibilityAddTraits(.isHeader)
            .padding(.top, MekaSpace.xs)
    }
}

/// FYI · NO REPLY NEEDED: each chat that needs nothing from Meka as one thin row (who, what, a ✓ that is its Done; the
/// row folds away on the expand spring, a cross-fade with reduced motion). Clear FYI in the header does them all.
struct NeedsYouFyiRowsView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let chats: [TriageChat]
    var firstIndex: Int = 4

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            if !chats.isEmpty {
                NeedsYouSectionHead(palette: palette, text: NeedsYouLayoutRules.shared.head(
                    title: NeedsYouLayoutRules.shared.FYI_HEAD, count: Int32(chats.count)))
                    .staggeredAppear(firstIndex)
            }
            ForEach(Array(chats.enumerated()), id: \.element.key) { i, chat in
                NeedsYouFyiRow(chat: chat, palette: palette)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + 1 + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: chats.map(\.key))
    }
}

struct NeedsYouFyiRow: View {
    @Environment(CoreModel.self) private var model
    let chat: TriageChat
    let palette: MekaPalette

    var body: some View {
        let row = NeedsYouLayoutRules.shared.fyiRow(chat: chat)
        HStack(spacing: MekaSpace.xs) {
            Text(row.who).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary).lineLimit(1)
            Text(row.what).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button { model.doneTriageChat(chat) } label: {
                Image(systemName: "checkmark").font(MekaType.captionStrong).foregroundStyle(palette.textTertiary)
                    .frame(width: 28, height: 28).contentShape(Circle())
            }
            .buttonStyle(MekaPressStyle())
            .help(NeedsYouLayoutRules.shared.SEEN)
            .accessibilityHidden(true)
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.spoken)
        .accessibilityAction(named: NeedsYouLayoutRules.shared.SEEN) { model.doneTriageChat(chat) }
        .contextMenu { Button(NeedsYouLayoutRules.shared.SEEN) { model.doneTriageChat(chat) } }
    }
}

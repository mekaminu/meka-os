import SwiftUI
@preconcurrency import MekaKit

/// "Archived for you" on the Mac (Inbox autopilot, slice 4), in Needs you under the group digest: from the 12:30 and
/// 18:30 digests, what Autopilot tidied since Meka last looked ("Tidied 14 · 9 newsletters · 3 receipts and orders · …"),
/// each tidy with its emails by sender (2026-10-16: "Deliveroo · 4 promos", each subject with a reason chip and its time) and Put back (the server returns those emails to the inbox at its next check). Got it folds it away here
/// and on the Fold (synced), with Undo on the bar. It staggers in after the cards above; Show/Hide unfolds the tidies on
/// the expand spring; the headline cross-fades. Reduced motion: cross-fades.
struct ArchivedForYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true

    var body: some View {
        Group {
            if let card = model.archivedForYou {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text("\(card.title) · \(card.slot)".uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: card.headline)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(card.spoken)
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.xs) {
                            ForEach(card.lines, id: \.id) { line in
                                ArchivedLineRow(line: line, palette: palette) { model.putBackArchived(line.id) }
                            }
                            if let more = card.moreLine {
                                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                            Text(card.note).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                    HStack(spacing: MekaSpace.xs) {
                        ArchivedPill(label: ArchivedForYouRules.shared.GOT_IT, palette: palette,
                                     spoken: "Got it, fold Archived for you away") { model.seenArchivedForYou() }
                        ArchivedPill(label: open ? ArchivedForYouRules.shared.HIDE : ArchivedForYouRules.shared.SHOW,
                                     palette: palette, quiet: true, spoken: open ? "Hide what was archived" : "Show what was archived") {
                            MekaHaptics.tick()
                            open.toggle()
                        }
                    }
                    .padding(.top, MekaSpace.m)
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.archivedForYou == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }
}

private struct ArchivedLineRow: View {
    let line: ArchivedForYouLine
    let palette: MekaPalette
    let putBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .center, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("\(line.time) · \(line.account)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    Text(line.detail).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let state = line.state {
                        Text(state).font(MekaType.itemMeta).foregroundStyle(palette.accent).transition(.opacity)
                    }
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: MekaSpace.xs)
                if line.canPutBack {
                    ArchivedPill(label: ArchivedForYouRules.shared.PUT_BACK, palette: palette,
                                 spoken: "Put back \(line.detail) in the inbox", action: putBack)
                }
            }
            // "Archived for you" shows which emails: the tidy's emails by sender, each subject with its reason and time
            // (the sender's words, shown as plain text only).
            if !line.emails.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.s) {
                    ForEach(line.emails, id: \.id) { group in
                        ArchivedEmailGroupRow(group: group, palette: palette)
                    }
                    if let more = line.emailsMore {
                        Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    }
                }
                .padding(.leading, MekaSpace.s)
            }
        }
    }
}

/// One sender in a tidy: "Deliveroo · 4 promos", its domain and account, then each subject with a muted reason chip.
private struct ArchivedEmailGroupRow: View {
    let group: ArchivedEmailGroup
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(group.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
            Text(group.meta).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            ForEach(group.rows, id: \.key) { row in
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(row.subject).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(row.chip).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                        .padding(.horizontal, MekaSpace.s).padding(.vertical, MekaSpace.xxs)
                        .background(palette.surface, in: Capsule())
                    Text(row.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                }
            }
            if let more = group.moreLine {
                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(group.spoken)
    }
}

private struct ArchivedPill: View {
    let label: String
    let palette: MekaPalette
    var quiet: Bool = false
    let spoken: String
    let action: () -> Void

    var body: some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(quiet ? palette.textSecondary : palette.accent)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: Capsule())
            .accessibilityLabel(spoken)
    }
}

import SwiftUI
@preconcurrency import MekaKit

/// "From your mail" on the Mac (Inbox autopilot, slice 6a), in Needs you above "Archived for you": the emails the server
/// sorted as needing Meka, still in the inbox, newest first — who, which account (the business one wears a Business
/// chip), when, the subject and the first words — each with Done (archived at the server's next check), Task and Waiting
/// for (slice 6b-1: made from the email, which is archived too) and Snooze (back tomorrow 08:00), each with Undo on the bar. It staggers in after the cards above; clicking the header folds the
/// emails in and out on the expand spring; the headline cross-fades. Reduced motion: cross-fades.
struct MailNeedsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true

    var body: some View {
        Group {
            if let card = model.mailNeeds {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(card.title.uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: card.headline)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(card.title). \(card.headline)")
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.m) {
                            ForEach(card.rows, id: \.id) { row in
                                MailNeedsRowView(row: row, palette: palette,
                                                 done: { model.doneMailItem(row.id) },
                                                 task: { model.taskFromMailItem(row.id) },
                                                 waiting: { model.waitingFromMailItem(row.id) },
                                                 snooze: { model.snoozeMailItem(row.id) })
                            }
                            if let more = card.moreLine {
                                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                            if let snoozed = card.snoozedLine {
                                Text(snoozed).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailNeeds == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailNeeds?.rows.map(\.id) ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }
}

private struct MailNeedsRowView: View {
    let row: MailNeedsRow
    let palette: MekaPalette
    let done: () -> Void
    let task: () -> Void
    let waiting: () -> Void
    let snooze: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(spacing: MekaSpace.xs) {
                    Text(row.from).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    if row.business {
                        Text(MailNeedsRules.shared.BUSINESS_CHIP)
                            .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(palette.surface, in: Capsule())
                    }
                }
                Text(row.subject).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(2)
                if let preview = row.preview {
                    Text(preview).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(2)
                }
                Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
            HStack(spacing: MekaSpace.xs) {
                MailNeedsPill(label: MailNeedsRules.shared.DONE, palette: palette,
                              spoken: "Done: archive \(row.subject)", action: done)
                MailNeedsPill(label: MailNeedsRules.shared.TASK, palette: palette,
                              spoken: "Make a task from \(row.subject) and archive the email", action: task)
                MailNeedsPill(label: MailNeedsRules.shared.WAITING, palette: palette, quiet: true,
                              spoken: "Waiting for \(row.from) on \(row.subject), chase in 3 days", action: waiting)
                MailNeedsPill(label: MailNeedsRules.shared.SNOOZE, palette: palette, quiet: true,
                              spoken: "Snooze \(row.subject) till tomorrow 8 o'clock", action: snooze)
            }
            .padding(.top, MekaSpace.xxs)
        }
    }
}

private struct MailNeedsPill: View {
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

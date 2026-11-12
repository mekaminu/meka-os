import SwiftUI
import AppKit
@preconcurrency import MekaKit

/// Requests from people Meka watches (V1, requests slice 4), in Needs you above the stack: "From Wife · 14:02", the
/// quote, what Add would do, what more it changes (work from home), then Add · Change · Not a task. The Fold read the
/// message; the cards sync, so answering here clears them there. Buttons press in (light haptic; Not a task a tick);
/// a card folds away with the expand spring and the undo bar rises. Cards stagger in after the after-work card.
/// Reduced motion: cross-fades. Nothing is sent to anyone: a shared email's reply card ("Handle this", slice 3) shows its
/// draft whole with Copy reply · Not needed, and Copy reply only puts it on the pasteboard.
struct RequestCardsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first card.
    var firstIndex: Int = 2

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.looseRequests.enumerated()), id: \.element.id) { i, card in
                Group {
                    // The approved look (TO DECIDE, the Fold's slice 3): a compact card; a reply keeps its whole draft.
                    if NeedsYouDecideRules.shared.compact(card: card) {
                        NeedsYouDecideCardView(card: NeedsYouDecideRules.shared.card(card: card), palette: palette,
                                               onYes: { model.answerRequest(card, .add) },
                                               onNo: { model.answerRequest(card, .decline) },
                                               onChange: { model.answerRequest(card, .change) })
                    } else {
                        RequestCardRow(card: card, palette: palette) { model.answerRequest(card, $0) }
                    }
                }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.looseRequests.map(\.id))
    }
}

struct RequestCardRow: View {
    let card: RequestCard
    let palette: MekaPalette
    let answer: (RequestAnswer) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(card.from).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Text(card.quote).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(3)
            Text(card.action).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).padding(.top, MekaSpace.xxs)
            if let detail = card.detail {
                Text(detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            // "Handle this", slice 3: a reply card shows its whole draft (selectable) before Copy reply.
            if let draft = card.draft {
                Text(draft).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(MekaSpace.xs)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .padding(.top, MekaSpace.xxs)
            }
            HStack(spacing: MekaSpace.xs) {
                pill(card.addLabel, filled: true, spoken: "\(card.addLabel): \(card.action)") { answer(.add) }
                if let change = card.changeLabel {
                    pill(change, filled: false, spoken: "\(change) \(card.action)") { answer(.change) }
                }
                pill(card.declineLabel, filled: false, quiet: true, spoken: card.declineLabel) { answer(.decline) }
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(card.spoken)
        .mekaHoverLift()
    }

    private func pill(_ label: String, filled: Bool, quiet: Bool = false, spoken: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(filled ? palette.onAccent : (quiet ? palette.textSecondary : palette.accent))
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(filled ? palette.accent : palette.surface, in: Capsule())
            .accessibilityLabel(spoken)
    }
}

/// "Handle her requests for me" (2026-11-11): the Done for you rows at the top of Needs you — the gold DONE FOR YOU
/// label, what the Fold did at once from his partner's request, who asked and when, then Undo · Change · Clear. Rows
/// fold away with the expand spring (reduced motion: a cross-fade). Nothing here sends anything.
struct DoneForYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 1

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.doneForYou.enumerated()), id: \.element.id) { i, row in
                DoneForYouRow(row: row, palette: palette)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
        }
        .padding(.bottom, model.doneForYou.isEmpty ? 0 : MekaSpace.m)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.doneForYou.map(\.id))
    }
}

struct DoneForYouRow: View {
    @Environment(CoreModel.self) private var model
    let row: DoneForYou
    let palette: MekaPalette
    /// Slice 3: shows "Copied · paste it and send it yourself" once a draft is on the pasteboard.
    @State private var copied = false
    /// Obi's suggested steps taken off in this row (hidden at once; the core deletes their tasks).
    @State private var gone: Set<String> = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let id = row.id
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(HandleForMeRules.shared.HEAD.uppercased()).font(MekaType.sectionLabel).foregroundStyle(palette.accent)
            Text(row.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
            Text(row.from).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
            HStack(spacing: MekaSpace.xs) {
                pill(row.undoLabel, quiet: false, spoken: "\(row.undoLabel): \(row.line)") { model.undoDoneForYou(id) }
                if let change = row.changeLabel, let task = row.changeTaskId {
                    let title = row.changeTitle
                    pill(change, quiet: false, spoken: "\(change): \(row.line)") { model.changeDoneForYou(taskId: task, title: title) }
                }
                pill(HandleForMeRules.shared.CLEAR, quiet: true, spoken: "\(HandleForMeRules.shared.CLEAR): \(row.line)") { model.clearDoneForYou(id) }
            }
            .padding(.top, MekaSpace.m)
            // Obi's suggested steps for a new kind of request: each is on his list; a click takes it off (hidden at once).
            let steps = row.suggestions.filter { !gone.contains($0.taskId) }
            if !steps.isEmpty {
                Text(HandleForMeRules.shared.SUGGESTED_HEAD).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .padding(.top, MekaSpace.xs)
                HStack(spacing: MekaSpace.xs) {
                    ForEach(steps, id: \.taskId) { step in
                        let taskId = step.taskId
                        pill("✓ \(step.title)", quiet: false, spoken: step.spoken) {
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = gone.insert(taskId) }
                            model.untickDoneForYouStep(id, taskId: taskId)
                        }
                    }
                }
            }
            // Slice 3: the drafted reply to her (and the note to his manager) go on the pasteboard; he sends them.
            if !row.drafts.isEmpty {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(Array(row.drafts.enumerated()), id: \.offset) { _, draft in
                        let words = draft.text
                        pill(draft.label, quiet: false, spoken: draft.spoken) { copyDraft(words) }
                    }
                }
                .padding(.top, MekaSpace.xs)
                if copied {
                    Text(HandleForMeRules.shared.COPIED).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(row.spoken)
        .mekaHoverLift()
    }

    private func copyDraft(_ words: String) {
        MekaHaptics.light()
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(words, forType: .string)
        copied = true
    }

    private func pill(_ label: String, quiet: Bool, spoken: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(quiet ? palette.textSecondary : palette.accent)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: Capsule())
            .accessibilityLabel(spoken)
    }
}

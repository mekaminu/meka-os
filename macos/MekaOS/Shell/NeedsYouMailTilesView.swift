import SwiftUI
@preconcurrency import MekaKit

/// MAIL NAVILIFE TIDIED TODAY on the Mac (the approved Needs you look, `docs/design/needs-you/needs-you.dc.html`; the
/// Fold's slice 2): four number tiles (tidied · newsletters · promos · junk & scams, over today's tidies that still
/// stand), the one unsubscribe question with Keep and Unsubscribe (the next sender cross-fades in; Undo on the bar), and
/// the quiet foot "Still in Gmail and Outlook · Put back from the list" with See all ›, which opens All tidied mail where
/// every tidy keeps its Put back. It takes the place of the long Archived for you log (while today has tidies) and the
/// weekly offer's card (while the question shows). All from core NeedsYouMailRules; nothing here moves mail itself.
struct NeedsYouMailTilesView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 3

    var body: some View {
        Group {
            if let summary = model.needsYouMail {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text(NeedsYouMailTilesView.heading)
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textSecondary)
                        .accessibilityAddTraits(.isHeader)
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        if !summary.tiles.isEmpty {
                            NeedsYouMailTilesRow(tiles: summary.tiles, palette: palette) { t in
                                MekaHaptics.tick()
                                model.archivedAllKind = t.kind
                                model.showArchivedAll = true
                            }
                            // Week 4, A7: what the three parts leave out, so the tiles add up ("Also 1 receipt or order").
                            if let rest = summary.rest {
                                Text(rest)
                                    .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                                    .lineLimit(2)
                                    .accessibilityHidden(true)
                            }
                        }
                        if let q = summary.question {
                            NeedsYouUnsubQuestionView(question: q, palette: palette)
                                .id(q.senderId)
                                .transition(.opacity)
                        }
                        HStack(spacing: MekaSpace.xs) {
                            Text(summary.foot)
                                .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                                .lineLimit(2).truncationMode(.tail)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Button(NeedsYouMailRules.shared.SEE_ALL) {
                                MekaHaptics.tick()
                                model.archivedAllKind = nil
                                model.showArchivedAll = true
                            }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.captionStrong).foregroundStyle(palette.accent)
                            .accessibilityLabel("See all tidied mail, with Put back")
                        }
                    }
                    .padding(MekaSpace.m)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous))
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: summary.question?.senderId)
                }
                .staggeredAppear(firstIndex)
                .transition(.opacity)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.needsYouMail == nil)
    }

    static let heading = "MAIL NAVILIFE TIDIED TODAY"
    static let chevron = "›"
}

/// Needs you's mail on the Mac: the tiles, then Archived for you only while nothing was tidied today (the tiles stand
/// for today) and the weekly "Not opened in 30 days" only while the tiles' one question isn't showing, as on the Fold.
struct NeedsYouMailGroup: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    let firstIndex: Int

    var body: some View {
        // Meka 2026-11-18 23:34 (b), the Mac's gaps: the parts sit a card's gap apart and the last one ends the box (no
        // padding left under it), so the Mail tidied today box is as tall as what it holds.
        let parts = NeedsYouMailGroup.parts(model.needsYouMail, archived: model.archivedForYou != nil,
                                            unopened: model.mailUnopened != nil)
        VStack(alignment: .leading, spacing: NeedsYouMailGroup.gap) {
            ForEach(parts, id: \.self) { part in
                switch part {
                case .tiles: NeedsYouMailTilesView(palette: palette, firstIndex: firstIndex)
                // What Autopilot archived (Inbox autopilot, slice 4), with the digests until Got it.
                case .log: ArchivedForYouView(palette: palette, firstIndex: firstIndex)
                // The weekly "Not opened in 30 days" (Inbox autopilot, slice 5c), from Saturday 09:00 until answered.
                case .offer: UnopenedOfferView(palette: palette, firstIndex: firstIndex)
                }
            }
        }
    }

    /// One part of the box, in the order drawn.
    enum Part: Hashable { case tiles, log, offer }

    /// The space between two parts; nothing is added after the last.
    static let gap: CGFloat = MekaSpace.m

    /// The parts that have something to show: the tiles while today has a summary, the log only while today has no
    /// tidies to count, the weekly offer only while the tiles' one question isn't showing.
    static func parts(_ summary: NeedsYouMailSummary?, archived: Bool, unopened: Bool) -> [Part] {
        var out: [Part] = []
        if summary != nil { out.append(.tiles) }
        if archived && showsLog(summary) { out.append(.log) }
        if unopened && showsOffer(summary) { out.append(.offer) }
        return out
    }

    /// The long log shows only while today has no tidies to count.
    static func showsLog(_ summary: NeedsYouMailSummary?) -> Bool { summary?.tiles.isEmpty ?? true }
    /// The weekly offer's card shows only while the one question doesn't.
    static func showsOffer(_ summary: NeedsYouMailSummary?) -> Bool { summary?.question == nil }
}

/// The four tiles side by side, each a button (Meka 2026-11-18 23:34, "Can't do anything with tidied emails"): a
/// chevron, the press style, and a click opens All tidied mail on today's emails of its kind; VoiceOver reads each tile.
private struct NeedsYouMailTilesRow: View {
    let tiles: [NeedsYouMailTile]
    let palette: MekaPalette
    let onOpen: (NeedsYouMailTile) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: MekaSpace.xs) {
            ForEach(Array(tiles.enumerated()), id: \.offset) { _, t in
                Button { onOpen(t) } label: {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        HStack(spacing: MekaSpace.xxs) {
                            Text(t.number)
                                .font(MekaType.upNextCardTitle).tracking(MekaType.upNextCardTitleTracking)
                                .foregroundStyle(t.warn ? palette.critical : palette.textPrimary)
                                .contentTransition(.numericText())
                                .lineLimit(1)
                            Spacer(minLength: 0)
                            Text(NeedsYouMailTilesView.chevron)
                                .font(MekaType.captionStrong).foregroundStyle(palette.textTertiary)
                        }
                        Text(t.label)
                            .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                            .lineLimit(2)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(MekaSpace.xs)
                    .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(t.spoken)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// "Unsubscribe from The Weekly Digest?" over its line ("Personal · 6 emails, none opened · last Mon 12 Oct · 1 of 11"),
/// Keep and Unsubscribe on the right.
private struct NeedsYouUnsubQuestionView: View {
    @Environment(CoreModel.self) private var model
    let question: NeedsYouUnsubQuestion
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .center, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(question.question)
                    .font(MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                    .lineLimit(2)
                Text([question.line, question.more].compactMap { $0 }.joined(separator: " · "))
                    .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(question.spoken)
            Button(NeedsYouMailRules.shared.KEEP) { model.keepMailSender(question.senderId) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .accessibilityLabel("Keep, don't offer this sender again")
            Button(NeedsYouMailRules.shared.UNSUBSCRIBE) { model.unsubscribeMailSender(question.senderId) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.captionStrong).foregroundStyle(palette.onAccent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
                .background(palette.accent, in: Capsule())
                .accessibilityLabel("Unsubscribe: Navilife unsubscribes at its next check")
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s, style: .continuous))
    }
}

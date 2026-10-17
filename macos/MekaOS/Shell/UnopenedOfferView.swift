import SwiftUI
@preconcurrency import MekaKit

/// "Not opened in 30 days" on the Mac (Inbox autopilot, slice 5c), in Needs you under "Archived for you": once a week,
/// from Saturday 09:00 until answered on either app, the newsletter and promo senders whose last 30 days of email Meka
/// never opened, each with Keep (never offered again); past eight, Show all N unfolds the rest with their own Keep;
/// Unsubscribe from N (only those not kept) asks the server to unsubscribe from each at
/// its next check; Not now folds it away until next Saturday. Every answer has Undo on the bar. It staggers in after the
/// cards above; Show/Hide unfolds the senders on the expand spring; the headline cross-fades. Reduced motion: cross-fades.
struct UnopenedOfferView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true
    /// "Show all 31" unfolds the senders past the first eight in place, each with Keep (Meka, 2026-10-17 04:05).
    @State private var showAll = false

    var body: some View {
        Group {
            if let card = model.mailUnopened {
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
                    .accessibilityLabel(card.spoken)
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.xs) {
                            ForEach(card.rows, id: \.id) { row in
                                UnopenedRowView(row: row, palette: palette) { model.keepMailSender(row.id) }
                            }
                            if showAll {
                                ForEach(card.moreRows, id: \.id) { row in
                                    UnopenedRowView(row: row, palette: palette) { model.keepMailSender(row.id) }
                                }
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                            }
                            if let label = card.showAllLabel {
                                Button {
                                    MekaHaptics.tick()
                                    showAll.toggle()
                                } label: {
                                    Text(showAll ? MailUnopenedRules.shared.SHOW_FEWER : label)
                                        .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                                }
                                .buttonStyle(.plain)
                                .padding(.vertical, MekaSpace.xs)
                                .accessibilityLabel(showAll ? "Show fewer senders" : (card.moreLine ?? label))
                            }
                            Text(card.note).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                    HStack(spacing: MekaSpace.xs) {
                        UnopenedPill(label: card.unsubscribeLabel, palette: palette,
                                     spoken: "\(card.unsubscribeLabel): Navilife unsubscribes at its next check") { model.unsubscribeUnopened() }
                        UnopenedPill(label: MailUnopenedRules.shared.NOT_NOW, palette: palette, quiet: true,
                                     spoken: "Not now, ask again next Saturday") { model.notNowUnopened() }
                        UnopenedPill(label: open ? MailUnopenedRules.shared.HIDE : MailUnopenedRules.shared.SHOW,
                                     palette: palette, quiet: true, spoken: open ? "Hide the senders" : "Show the senders") {
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
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailUnopened == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }
}

private struct UnopenedRowView: View {
    let row: MailUnopenedRow
    let palette: MekaPalette
    let keep: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: MekaSpace.xs)
            UnopenedPill(label: MailUnopenedRules.shared.KEEP, palette: palette, quiet: true,
                         spoken: "Keep \(row.name), don't offer it again", action: keep)
        }
    }
}

private struct UnopenedPill: View {
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

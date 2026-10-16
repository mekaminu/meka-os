@preconcurrency import MekaKit
import SwiftUI

/// Discord on the Mac (Discord channel watch, slice 1), in Needs you under "Renewals from your mail": one card per
/// channel he follows with the posts the Fold kept from Discord's notifications since he last looked, the newest few
/// under it, and Done (gone on both apps; Undo on the bar brings them back). Information only: nothing here posts to
/// Discord or trades. Clicking the header folds the cards on the expand spring; reduced motion: cross-fades.
struct DiscordCardsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true

    var body: some View {
        Group {
            if let block = model.discord {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(DiscordRules.shared.TITLE.uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(block.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: block.headline)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Discord. \(block.headline)")
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.m) {
                            ForEach(block.cards, id: \.key) { card in
                                DiscordChannelCardRow(card: card, palette: palette, keep: { model.keepDiscordIdea($0) }) { model.discordDone(card.key) }
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
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.discord == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.discord?.cards.map(\.key) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.discord?.cards.map { $0.summary ?? "" } ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }
}

private struct DiscordChannelCardRow: View {
    let card: DiscordChannelCard
    let palette: MekaPalette
    let keep: (String) -> Void
    let done: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(card.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    if let server = card.server {
                        Text(server).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    }
                }
                .accessibilityElement(children: .combine)
                Spacer()
                Button("Done", action: done)
                    .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent).font(MekaType.itemMeta)
                    .accessibilityLabel("Done with \(card.title)")
            }
            Text(card.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            // Slice 3: Navilife's AI's one line on what was said (synced from the Fold); fades in when it arrives.
            if let summary = card.summary {
                Text(summary).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.xxs)
                    .transition(.opacity)
                    .accessibilityLabel("In short: \(summary)")
            }
            // Slice 2: posts from a person or with a word he picked.
            if let alerts = card.alertLine {
                Text(alerts).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            if let more = card.moreLine {
                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            }
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(card.posts, id: \.id) { post in
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text("\(post.author) · \(post.timeLine)").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        if let why = post.alert {
                            Text(why).font(MekaType.caption).foregroundStyle(palette.onAccent)
                                .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                                .background(palette.accent, in: Capsule())
                                .accessibilityLabel("Alert: \(why)")
                        }
                        Text(post.text).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                        // Slice 4: a note in Lists → Someday → Trading ideas, never an order; cross-fades once kept.
                        Group {
                            if post.kept {
                                Text("✓ " + DiscordRules.shared.KEPT_CHIP).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                    .accessibilityLabel("Kept in Trading ideas")
                            } else {
                                Button(DiscordRules.shared.KEEP_ACTION) { keep(post.id) }
                                    .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent).font(MekaType.itemMeta)
                                    .accessibilityLabel("Keep as a trade idea in Lists. A note only, nothing is traded")
                            }
                        }
                        .transition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: post.kept)
                    }
                    .accessibilityElement(children: .contain)
                }
            }
            .padding(.top, MekaSpace.xs)
        }
    }
}

import SwiftUI
@preconcurrency import MekaKit

/// The group digest on the Mac (V1, messages slice 4b), in Needs you under the message and request cards: the busy groups
/// the Fold gathered at the latest digest time (12:30, 18:30), each "Barça lads · 47 messages", MEKA's gist of the chat
/// and who wrote — never the messages themselves, which stay on the phone. Caught up (one group, or all of them) clears
/// the cards here and on the Fold, with Undo on the bar. Cards stagger in after the cards above and fold away with the
/// expand spring; Caught up presses in with a light haptic. Reduced motion: cross-fades.
struct GroupGistsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first row.
    var firstIndex: Int = 2

    var body: some View {
        let gists = model.groupGists
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            if let head = GroupGistRules.shared.headLine(gists: gists) {
                HStack {
                    Text(head).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    Spacer()
                    if gists.count > 1 {
                        GistPill(label: GroupDigestRules.shared.CAUGHT_UP, palette: palette, spoken: "Caught up with every group") {
                            model.catchUpGroupDigest(gists)
                        }
                    }
                }
                .staggeredAppear(firstIndex)
                .transition(.opacity)
            }
            ForEach(Array(gists.enumerated()), id: \.element.groupKey) { i, gist in
                GroupGistRow(gist: gist, palette: palette) { model.catchUpGroupDigest([gist]) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + 1 + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: gists.map(\.groupKey))
    }
}

private struct GroupGistRow: View {
    let gist: GroupGist
    let palette: MekaPalette
    let caughtUp: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text("\(gist.title) · \(gist.countLine)").font(MekaType.body).foregroundStyle(palette.textPrimary)
            if let line = gist.gist {
                Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(3)
            }
            if !gist.people.isEmpty {
                Text(gist.people).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
            }
            GistPill(label: GroupDigestRules.shared.CAUGHT_UP, palette: palette, quiet: true, spoken: "Caught up with \(gist.title)", action: caughtUp)
                .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(gist.spoken)
        .mekaHoverLift()
    }
}

private struct GistPill: View {
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

@preconcurrency import MekaKit
import SwiftUI

/// The Gym on Today (build plan M1), as on the Fold: one card per booked habit with something today — "Today 17:45–18:45",
/// "Now · until 18:45", then "Did you go?" with Went · Didn't go once the slot is over (Went pops a check with a spring
/// and a light haptic, then a one-line note can follow), "Rebooked for Thu 17:45" after Didn't go; Undo puts today's
/// answer back. The content pushes in from the right as the state moves on. Reduce Motion: cross-fades.
struct SessionCardsView: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    var body: some View {
        let cards = model.sessions?.cards ?? []
        if !cards.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.s) {
                ForEach(cards, id: \.habitId) { c in SessionCardView(card: c, palette: palette) }
            }
        }
    }
}

private struct SessionCardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    let card: SessionCard
    let palette: MekaPalette
    @State private var note = ""
    @State private var popped = false

    private var went: Bool { card.status == .went }
    private var lit: Bool { card.status == .ask || card.status == .now }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            HStack(alignment: .center, spacing: MekaSpace.m) {
                if went {
                    // Went: the check draws itself in and pops, like a habit tick (Off: shown at once).
                    TickRingView(done: popped, palette: palette)
                        .frame(width: 22, height: 22)
                        .onAppear { popped = true }
                        .onDisappear { popped = false }
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.heading).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    Text(card.line).font(MekaType.itemMeta).foregroundStyle(lit ? palette.accent : palette.textSecondary)
                    if let n = card.note {
                        Text("“\(n)”").font(MekaType.body).foregroundStyle(palette.textSecondary)
                    }
                    if let next = card.next {
                        Text(next).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .id("\(card.status.name)|\(card.line)")
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .trailing))
            .accessibilityElement(children: .combine)
            .accessibilityLabel(card.spoken)

            HStack(spacing: MekaSpace.l) {
                if card.asks {
                    Button("Went") { model.sessionWent(card.habitId) }
                    Button("Didn't go") { model.sessionMissed(card.habitId) }
                }
                if card.answered {
                    Button("Undo") { model.undoSession(card.habitId) }
                }
                // The workout app ("Open Hevy ↗"): its link opens in the app that claims it, else the browser.
                if let label = card.openLabel, let link = card.appLink, let url = URL(string: link) {
                    Button("\(label) ↗") { MekaHaptics.light(); openURL(url) }
                        .accessibilityHint("Opens \(card.appName ?? "the app") outside MEKA")
                }
            }
            .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)

            if went && card.note == nil {
                TextField("Add a note · push day, 5 km… (optional)", text: $note)
                    .textFieldStyle(.roundedBorder)
                    .font(MekaType.body)
                    .onSubmit {
                        model.setSessionNote(card.habitId, String(note.prefix(80)))
                        note = ""
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.l)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .animation(MekaMotion.replan(reduced: reduceMotion), value: "\(card.status.name)|\(card.line)")
    }
}

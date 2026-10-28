@preconcurrency import MekaKit
import SwiftUI

/// Study plan, slice 2b rest, like the Fold's: from 10 minutes before a study session, Obi's quiet card on Today
/// ("Study in 8 min · Data analytics", the time, lessons and where) with Start, then Done (its lessons ticked, Undo in
/// the card) and Not now. Never spoken. A click shows at once; the card cross-fades and folds away.
/// Only Strings cross to the core.
struct StudyNudgeCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let nudge: StudyNudge?
    let palette: MekaPalette
    /// Started, put away or done here, shown at once until the view says the same.
    @State private var started: Set<String> = []
    @State private var gone: Set<String> = []
    @State private var done: CoreModel.TripUndo?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            if let n = nudge, !gone.contains(n.key) {
                card(n).transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            if let d = done {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(d.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Spacer()
                    Button("Undo") {
                        let token = d.token
                        done = nil
                        model.undoStudySessionDone(token)
                        gone = []
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Undo: \(d.line)")
                }
                .transition(.opacity)
                .task(id: d) {
                    try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                    if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { done = nil } }
                }
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: nudge?.key)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: gone)
        .onChange(of: nudge?.on) { _, on in if on == true, let k = nudge?.key { started.remove(k) } }
    }

    private func card(_ n: StudyNudge) -> some View {
        let key = n.key
        let on = n.on || started.contains(key)
        let headline = on && !n.on ? "Studying · " + (n.headline.components(separatedBy: " · ").dropFirst().joined(separator: " · ")) : n.headline
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(headline).font(MekaType.captionStrong).foregroundStyle(palette.accent).lineLimit(1)
                .contentTransition(.opacity)
            Text(n.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
            HStack(spacing: MekaSpace.m) {
                Button {
                    if on {
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { _ = gone.insert(key) }
                        Task {
                            if let r = await model.finishStudySession(key) {
                                if !r.token.isEmpty { done = r }
                            } else {
                                gone.remove(key)
                            }
                        }
                    } else {
                        started.insert(key)
                        Task { if !(await model.startStudySession(key)) { started.remove(key) } }
                    }
                } label: {
                    Text(on ? StudyWeekRules.shared.DONE : StudyWeekRules.shared.START)
                        .font(MekaType.captionStrong)
                        .foregroundStyle(on ? palette.onAccent : palette.accent)
                        .padding(.horizontal, MekaSpace.m)
                        .padding(.vertical, MekaSpace.xxs)
                        .background(on ? palette.accent : palette.surfaceRaised, in: Capsule())
                        .contentTransition(.opacity)
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(on ? "Done, tick the session's lessons" : "Start the study session")
                Button(StudyNudgeRules.shared.NOT_NOW) {
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { _ = gone.insert(key) }
                    Task { if !(await model.putAwayStudyNudge(key)) { gone.remove(key) } }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textSecondary)
            }
            .padding(.top, MekaSpace.xxs)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.l))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(n.spoken)
    }
}

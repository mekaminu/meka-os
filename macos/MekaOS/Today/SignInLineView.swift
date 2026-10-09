@preconcurrency import MekaKit
import SwiftUI

/// Today's sign-in line (Reliability first, item 2): "Google sign-in ends tomorrow at 14:05 · Reconnect" in the accent,
/// or "Google sign-in expired · calendars aren't updating · Reconnect" in the critical colour. Clicking it unfolds why
/// (expand spring, tick haptic) with Reconnect (light haptic), which opens the provider's sign-in page in the browser,
/// asking for editing again when the account had it. The line cross-fades as it changes.
struct SignInLineView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let line: SignInLine
    let palette: MekaPalette
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button {
                MekaHaptics.tick()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
            } label: {
                Text(line.text).font(MekaType.caption)
                    .foregroundStyle(line.critical ? palette.critical : palette.accent)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line.text)
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(line.spoken)
            if open {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(line.detail).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let message = model.calendarsMessage {
                        Text(message).font(MekaType.caption).foregroundStyle(palette.critical)
                    }
                    Button("Reconnect") {
                        MekaHaptics.light()
                        let provider = line.provider
                        let editing = line.editing
                        Task { await model.connectCalendar(provider, editing: editing) }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                }
                .padding(.leading, MekaSpace.m)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}

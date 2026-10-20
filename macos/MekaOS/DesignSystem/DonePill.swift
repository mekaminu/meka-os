@preconcurrency import MekaKit
import SwiftUI

/// Done on a Needs you card (Meka, 2026-10-20 17:33; `DonePillSpec` in the core, shared with the Fold): a
/// gold-outlined pill, 32 pt high with 14 pt each side and a 1 pt accent border on the raised surface, accent text,
/// at the card's trailing edge so it sits in the same place on every card. The 48 pt hit area is added outside the
/// drawn pill. Press: the pill scales to 0.96; on the click it flashes gold for a moment before `action` runs and the
/// card slides away. Reduce Motion: no scale or flash, the action runs at once and the card fades.
struct DonePill: View {
    let palette: MekaPalette
    var label: String = "Done"
    let spoken: String
    let action: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var flashing = false

    var body: some View {
        let spec = DonePillSpec.shared
        Button {
            if spec.flashMs(reduced: reduceMotion) == 0 {
                action()
            } else {
                withAnimation(MekaMotion.appear(reduced: false)) { flashing = true }
            }
        } label: {
            Text(label)
                .font(MekaType.captionStrong)
                .foregroundStyle(palette.accent)
                .lineLimit(1)
                .padding(.horizontal, CGFloat(spec.SIDE_PAD_DP))
                .frame(height: CGFloat(spec.HEIGHT_DP))
                .background(flashing ? palette.accent.opacity(0.35) : Color.clear, in: Capsule())
                .background(palette.surfaceRaised, in: Capsule())
                .overlay(Capsule().strokeBorder(palette.accent, lineWidth: CGFloat(spec.BORDER_DP)))
                .frame(minWidth: CGFloat(spec.TOUCH_DP), minHeight: CGFloat(spec.TOUCH_DP))
                .contentShape(Rectangle())
        }
        .buttonStyle(DonePillPressStyle())
        .accessibilityLabel(spoken)
        .task(id: flashing) {
            guard flashing else { return }
            try? await Task.sleep(for: .milliseconds(Int(DonePillSpec.shared.flashMs(reduced: false))))
            guard !Task.isCancelled else { return }
            action()
            flashing = false
        }
    }
}

/// The Done pill's own press: 0.96 while held (`DonePillSpec.scale`), none with Reduce Motion.
private struct DonePillPressStyle: ButtonStyle {
    @Environment(\.mekaReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(CGFloat(DonePillSpec.shared.scale(pressed: configuration.isPressed, reduced: reduceMotion)))
            .animation(MekaMotion.complete(reduced: reduceMotion), value: configuration.isPressed)
    }
}

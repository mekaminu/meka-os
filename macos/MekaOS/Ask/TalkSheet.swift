@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Talk on the Mac (Talk without tapping the mic, slice 1), like the Fold's TalkPane: how to start
/// talking to MEKA without reaching for the mic (⌥Space while MEKA is in front, or the mic beside Ask's field) and why
/// it stays safe. The words are the core's `TalkStartRules`, shared with the Fold (whose pane adds the side button and
/// the headphones' button).
///
/// Motion: the sheet scale-fades (system); sections stagger in. Reduce Motion: cross-fades.
struct TalkSheet: View {
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette

    var body: some View {
        let view = TalkStartRules.shared.setup(mac: true, assistantHeld: false, samsung: false)
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text(view.title).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(view.intro).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(0)
            ForEach(Array(view.sections.enumerated()), id: \.offset) { i, s in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(s.label.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityAddTraits(.isHeader)
                    Text(s.status).font(MekaType.body).foregroundStyle(s.lit ? palette.accent : palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(s.steps, id: \.self) { step in
                        Text(step).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, MekaSpace.s)
                .staggeredAppear(i + 1)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
    }
}

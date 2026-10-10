@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → What MEKA may do on the Mac (policy engine UI, slice 1), like the Fold's AutonomyPane: the pause switch
/// on top, each earned-trust kind under Your lists ("Does it for you" with Ask first, "Asks first · 3 of 5 yeses in a
/// row", "Asks first · you said keep asking" with Let MEKA offer again), then the locked rows no setting changes. Only a
/// Bool or a kind's key (a String) crosses to the core.
///
/// Motion: the sheet scale-fades (system); the summary, each row's line and the pause button cross-fade; the sections
/// stagger in; a row's dot blends to the accent while MEKA does it on its own; Pause / Resume a light haptic, Ask first
/// a tick; the answer's line arrives on the expand spring. Reduce Motion: cross-fades.
struct AutonomySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let v = model.autonomy ?? AutonomyView.companion.EMPTY
        let rules = AutonomyRules.shared
        let paused = v.paused
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text("What MEKA may do").font(MekaType.upNextTitle).staggeredAppear(0)
                Text(v.summary).font(MekaType.body)
                    .foregroundStyle(paused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    .staggeredAppear(1)
                if let said = model.autonomySaid {
                    Text(said).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                        .transition(.opacity)
                }
                HStack(spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(rules.PAUSE_TITLE).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(rules.PAUSE_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: MekaSpace.xs)
                    pill(v.pauseButton, lit: paused) {
                        Task { await model.setAutonomyPaused(!paused) }
                    }
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(2)
                ForEach(Array(v.sections.enumerated()), id: \.offset) { i, section in
                    label(section.label).staggeredAppear(3 + 2 * i)
                    ForEach(section.rows, id: \.key) { row in
                        rowView(row)
                    }
                    .staggeredAppear(4 + 2 * i)
                }
                Text(rules.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.xs)
                    .staggeredAppear(3 + 2 * v.sections.count)
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
                .padding(.top, MekaSpace.m)
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: model.autonomySaid)
            .padding(MekaSpace.l)
        }
        .frame(width: 560, height: 640)
        .background(palette.surface)
    }

    private func rowView(_ row: AutonomyRow) -> some View {
        let dot: Color = row.onItsOwn ? palette.accent : (row.locked ? palette.textTertiary : palette.surfaceRaised)
        return HStack(spacing: MekaSpace.xs) {
            Circle().fill(dot).frame(width: 10, height: 10)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: row.onItsOwn)
            VStack(alignment: .leading, spacing: 0) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
            }
            Spacer(minLength: MekaSpace.xs)
            if row.locked {
                Image(systemName: "lock.fill").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Locked")
            } else if let action = row.action {
                let key = row.key
                pill(action, lit: false) {
                    Task { await model.resetTrust(key) }
                }
            }
        }
        .padding(.vertical, MekaSpace.xs)
        .accessibilityElement(children: .combine)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
    }

    private func pill(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.accent)
                .contentTransition(.opacity)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: label)
        }
        .buttonStyle(MekaPressStyle())
    }
}

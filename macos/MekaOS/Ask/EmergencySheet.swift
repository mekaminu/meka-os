@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Emergency card on the Mac (emergency card, slice 1), like the Fold's EmergencyPane: who to call, where
/// things are and key accounts by name, one typed line at a time under each part ("GP: Dr Patel, 01632 123456"), each
/// with Remove. his wife reads the same card on her family page. A line holding a password, PIN or card or account
/// number is turned away: the line under its field says why in the accent and the words stay to fix. Only Strings cross
/// to the core.
///
/// Motion: the sheet scale-fades (system); the summary and each part's line cross-fade; parts and rows stagger in;
/// rows arrive and leave on the expand spring; Add gives a light haptic, a refusal or Remove a tick. Reduce Motion:
/// cross-fades.
struct EmergencySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Per part: what its field holds and what the last Add said.
    @State private var texts: [String: String] = [:]
    @State private var said: [String: CoreModel.EmergencySaid] = [:]

    var body: some View {
        let v = model.emergency ?? EmergencyView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Emergency card").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(v.groups.enumerated()), id: \.element.section) { g, group in
                        part(group).staggeredAppear(2 + g)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: v.groups.flatMap { $0.rows.map(\.id) })
            }
            .frame(maxHeight: 480)
            Text(EmergencyRules.shared.SHARED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    @ViewBuilder
    private func part(_ group: EmergencyGroup) -> some View {
        let section = group.section
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(group.label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xs)
            ForEach(group.rows, id: \.id) { row in
                EmergencyRowView(row: row, palette: palette) { model.removeEmergency(row.id) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            TextField(group.hint, text: Binding(get: { texts[section] ?? "" }, set: { texts[section] = $0 }))
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = texts[section] ?? ""
                    texts[section] = ""
                    Task {
                        guard let r = await model.addEmergency(section: section, text: typed) else { return }
                        said[section] = r
                        if r.refused { texts[section] = typed } // keep the words to fix
                    }
                }
            if let line = said[section] {
                Text(line.line).font(MekaType.caption)
                    .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
        }
    }
}

private struct EmergencyRowView: View {
    let row: EmergencyRow
    let palette: MekaPalette
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let detail = row.detail {
                    Text(detail).font(MekaType.caption).foregroundStyle(palette.textSecondary).textSelection(.enabled)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            Spacer()
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.title)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Bins on the Mac (bin days, slice 1), like the Fold's BinsPane: each bin typed once with its day
/// ("Black bin Tue fortnightly from 13 Oct", "Food caddy every Tuesday"), a row per bin with its pattern and next
/// collection (a day later after a bank holiday), each with Remove. Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line under the field cross-fade (a refused line lit in
/// the accent); rows stagger in and arrive or leave on the expand spring; Add gives a light haptic (a tick when
/// refused), Remove a tick. Reduce Motion: cross-fades.
struct BinsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: CoreModel.BinSaid?

    var body: some View {
        let v = model.bins
        let rows = v?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Bins").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? BinRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            TextField(BinRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { said = await model.addBin(typed) }
                }
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(2)
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if !rows.isEmpty {
                        Text("YOUR BINS")
                            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.m)
                            .staggeredAppear(3)
                    }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        BinRowView(row: row, palette: palette) { model.removeBin(row.id) }
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                            .staggeredAppear(4 + i)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
            }
            .frame(maxHeight: 320)
            Text(BinRules.shared.COUNCIL).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
    }
}

private struct BinRowView: View {
    let row: BinRow
    let palette: MekaPalette
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                if let note = row.note {
                    Text(note).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            Spacer()
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.name)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

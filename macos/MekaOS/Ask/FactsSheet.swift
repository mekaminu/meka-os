@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask → More → What MEKA knows on the Mac (facts page, slice 1), like the Fold's FactsPane: the facts Meka keeps so
/// MEKA can answer from them (the car's reg, the GP, sizes, the utilities), one typed line at a time ("Car reg: AB12
/// CDE"; the same name again changes it), in groups, each with "Keep from AI" (or "Let MEKA use it") and Remove. A line
/// holding a password, PIN or card, bank or ID number is turned away: the line under the field says why in the accent
/// and the words stay to fix. Copy (slice 3, filling forms) puts the fact alone on the pasteboard and the line under
/// the field says "Copied Car reg · AB12 CDE"; nothing is sent anywhere. Only Strings and a Bool cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary, the field's line and a row's "Kept from AI" cross-fade; groups
/// stagger in; rows arrive, move and leave on the expand spring; Add and Copy give a light haptic, a refusal, Keep from
/// AI and Remove a tick. Reduce Motion: cross-fades.
struct FactsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var said: CoreModel.FactSaid?

    var body: some View {
        let v = model.facts ?? FactsView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("What MEKA knows").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            TextField(FactRules.shared.HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task {
                        guard let r = await model.addFact(typed) else { return }
                        said = r
                        if r.refused { text = typed } // keep the words to fix
                    }
                }
                .staggeredAppear(2)
            if let line = said {
                Text(line.line).font(MekaType.caption)
                    .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(v.groups.enumerated()), id: \.element.group) { g, group in
                        part(group).staggeredAppear(3 + g)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: v.groups.flatMap { $0.rows.map { "\($0.id)|\($0.keptFromAi)" } })
            }
            .frame(maxHeight: 480)
            Text(FactRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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

    /// Copy: the fact alone goes on the pasteboard (the row's own String) and the field's line says what was copied.
    private func copy(_ row: FactRow) {
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(row.copyText, forType: .string)
        MekaHaptics.light()
        said = CoreModel.FactSaid(line: FactRules.shared.copiedLine(row: row), refused: false)
    }

    @ViewBuilder
    private func part(_ group: FactGroupView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(group.label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xs)
            ForEach(group.rows, id: \.id) { row in
                FactRowView(row: row, palette: palette, reduceMotion: reduceMotion,
                            onCopy: { copy(row) },
                            onAi: { model.setFactAi(row.id, use: row.keptFromAi) },
                            onRemove: { model.removeFact(row.id) })
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}

private struct FactRowView: View {
    let row: FactRow
    let palette: MekaPalette
    let reduceMotion: Bool
    let onCopy: () -> Void
    let onAi: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(row.value).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
                if let line = row.aiLine {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .transition(.opacity)
                }
            }
            .animation(MekaMotion.appear(reduced: reduceMotion), value: row.aiLine)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            Spacer()
            Button(FactRules.shared.COPY_BUTTON, action: onCopy)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel(row.copySpoken)
            Button(row.aiButton, action: onAi)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("\(row.aiButton): \(row.label)")
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.label)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

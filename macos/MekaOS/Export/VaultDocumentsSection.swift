@preconcurrency import MekaKit
import SwiftUI

/// The Vault's documents on the Mac (V2, slice 1), like the Fold's VaultDocuments: one field ("Passport expires 3 Mar
/// 2029", "Rex passport 20 May 2027", "Railcard 05/2027") and a row per document with how long is left, the ones to
/// renew first (status lit). Click a row for Remove. Only Strings cross to the core.
///
/// Motion: the section staggers in with the screen; the summary and the line under the field cross-fade (a refused
/// line lit in the accent); rows arrive and leave on the expand spring; an opened row grows on the expand spring; Add
/// gives a light haptic (a tick when refused), Remove a tick. Reduce Motion: cross-fades.
struct VaultDocumentsSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Index the section's stagger starts at.
    var firstIndex = 0
    @State private var text = ""
    @State private var said: CoreModel.TripSaid?
    @State private var open: String?

    var body: some View {
        let v = model.documents
        let rows = v?.rows ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("DOCUMENTS")
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.textTertiary)
                .staggeredAppear(firstIndex)
            Text(v?.summary ?? DocumentRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(firstIndex + 1)
            TextField(DocumentRules.shared.ADD_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { said = await model.addDocument(typed) }
                }
                .staggeredAppear(firstIndex + 2)
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(rows, id: \.id) { row in
                    DocumentRowView(row: row, palette: palette, isOpen: open == row.id) {
                        open = open == row.id ? nil : row.id
                    }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
            .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            .staggeredAppear(firstIndex + 3)
            Text(DocumentRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct DocumentRowView: View {
    @Environment(CoreModel.self) private var model
    let row: DocumentRow
    let palette: MekaPalette
    let isOpen: Bool
    let onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(row.status).font(MekaType.caption).foregroundStyle(row.statusLit ? palette.accent : palette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            if isOpen {
                Button("Remove") { model.removeDocument(row.id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(row.title)")
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

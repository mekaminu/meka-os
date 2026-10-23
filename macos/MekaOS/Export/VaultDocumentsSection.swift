import AppKit
@preconcurrency import MekaKit
import SwiftUI

/// The Vault's documents on the Mac (V2, slice 1), like the Fold's VaultDocuments: one field ("Passport expires 3 Mar
/// 2029", "Theo passport 20 May 2027", "Railcard 05/2027") and a row per document with how long is left, the ones to
/// renew first (status lit). Click a row for Remove. Only Strings cross to the core.
/// Slice 2b: an opened row offers Add a copy… (a photo or PDF, sealed with the Vault key on this Mac), and once there is
/// one Open copy (a sheet, in the app only), Replace copy… and Remove copy; the row says what is kept.
/// Slice 4: Open copy asks for Touch ID first (VaultLockRules); leaving the Vault, the screen sleeping or the session
/// going away locks the copies again.
///
/// Motion: the section staggers in with the screen; the summary and the line under the field cross-fade (a refused
/// line lit in the accent); rows arrive and leave on the expand spring; an opened row grows on the expand spring; Add
/// gives a light haptic (a tick when refused), Remove a tick. The copy line and what a copy action said cross-fade (a
/// refusal in the accent); the viewer is a sheet whose content fades in. Reduce Motion: cross-fades.
struct VaultDocumentsSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Index the section's stagger starts at.
    var firstIndex = 0
    @State private var text = ""
    @State private var said: CoreModel.TripSaid?
    @State private var open: String?
    /// Slice 2b: what the last copy action said, by row, and the opened copy.
    @State private var copySaid: (id: String, said: CoreModel.TripSaid)?
    @State private var viewing: ViewedCopy?

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
                .accessibilityLabel(DocumentRules.shared.ADD_SPOKEN)
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
                    DocumentRowView(
                        row: row, palette: palette, isOpen: open == row.id,
                        said: copySaid?.id == row.id ? copySaid?.said : nil,
                        onToggle: { open = open == row.id ? nil : row.id },
                        onAddCopy: {
                            let id = row.id
                            Task { if let s = await model.chooseVaultCopy(id) { copySaid = (id, s) } }
                        },
                        onOpenCopy: {
                            let id = row.id
                            Task {
                                let (copy, s) = await model.openVaultCopyUnlocking(id)
                                if let copy { viewing = copy } else if let s { copySaid = (id, s) }
                            }
                        },
                        onRemoveCopy: {
                            let id = row.id
                            Task { if let s = await model.removeVaultCopy(id) { copySaid = (id, s) } }
                        },
                        onOpenEmail: {
                            let id = row.id
                            Task {
                                let line = await model.openVaultReceipt(docId: id)
                                copySaid = (id, CoreModel.TripSaid(line: line, refused: false))
                            }
                        }
                    )
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
            .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            .staggeredAppear(firstIndex + 3)
            Text(DocumentRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if rows.contains(where: { $0.hasCopy }) {
                Text(VaultFileRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let note = VaultLockRules.shared.note(rows: rows) {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // Slice 2a: the household's Vault key, and the devices asking for it.
            VaultKeySection(palette: palette, firstIndex: firstIndex + 4)
        }
        .sheet(item: $viewing) { copy in
            VaultCopyViewer(copy: copy, palette: palette) { viewing = nil }
        }
        // Slice 4: leaving the Vault, the screen sleeping or the session going away locks the copies again (and
        // closes a shown one).
        .onDisappear { model.lockVault() }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.screensDidSleepNotification)) { _ in
            viewing = nil; model.lockVault()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidResignActiveNotification)) { _ in
            viewing = nil; model.lockVault()
        }
    }
}

/// The Vault key on the Mac (Vault slice 2a), like the Fold's VaultKeySection: the key's id and where it is, whether
/// the recovery key opens it, each device asking with its six-digit code, Allow and Not this one; or what this Mac
/// waits for (lit) and Ask again. Only Strings cross to the core.
///
/// Motion: staggers in after the documents; the line and sub cross-fade, the line blending to the accent while it
/// waits; request cards arrive and leave on the expand spring; Allow gives a light haptic, Not this one a tick.
/// Reduce Motion: cross-fades.
private struct VaultKeySection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let firstIndex: Int

    var body: some View {
        if let v = model.vaultKey, v.shown {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text("VAULT KEY")
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.m)
                    .staggeredAppear(firstIndex)
                Text(v.line).font(MekaType.body).foregroundStyle(v.lit ? palette.accent : palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.line)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.lit)
                    .staggeredAppear(firstIndex + 1)
                if let sub = v.sub {
                    Text(sub).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: sub)
                }
                if v.canAskAgain {
                    Button(VaultKeyRules.shared.ASK_AGAIN) { model.askVaultAgain() }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("Ask again for the Vault key")
                }
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(v.requests, id: \.deviceId) { r in
                        VaultRequestCard(request: r, palette: palette)
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: v.requests.map(\.deviceId))
            }
        }
    }
}

private struct VaultRequestCard: View {
    @Environment(CoreModel.self) private var model
    let request: VaultRequest
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(request.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Text(request.line).font(MekaType.caption).foregroundStyle(palette.accent)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: MekaSpace.xs) {
                Button(VaultKeyRules.shared.ALLOW) { model.allowVaultDevice(request.deviceId) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("\(VaultKeyRules.shared.ALLOW): \(request.title)")
                Button(VaultKeyRules.shared.REFUSE) { model.refuseVaultDevice(request.deviceId) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.textSecondary)
                    .accessibilityLabel("\(VaultKeyRules.shared.REFUSE): \(request.title)")
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(request.spoken)
    }
}

private struct DocumentRowView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let row: DocumentRow
    let palette: MekaPalette
    let isOpen: Bool
    let said: CoreModel.TripSaid?
    let onToggle: () -> Void
    let onAddCopy: () -> Void
    let onOpenCopy: () -> Void
    let onRemoveCopy: () -> Void
    let onOpenEmail: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(row.status).font(MekaType.caption).foregroundStyle(row.statusLit ? palette.accent : palette.textSecondary)
                    if let copy = row.copyLine {
                        Text(copy).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            if isOpen {
                HStack(spacing: MekaSpace.xs) {
                    let rules = VaultFileRules.shared
                    // Receipts → Vault: the email it was kept from (the browser; the link is made at the click).
                    if row.fromMail {
                        copyButton(VaultReceiptRules.shared.OPEN, "Open the email \(row.title) came from", lit: !row.hasCopy, action: onOpenEmail)
                    }
                    if row.hasCopy {
                        copyButton(rules.OPEN, "Open the copy of \(row.title)", lit: true, action: onOpenCopy)
                        copyButton(rules.REPLACE + "…", "Replace the copy of \(row.title)", lit: false, action: onAddCopy)
                        copyButton(rules.REMOVE, "Remove the copy of \(row.title)", lit: false, action: onRemoveCopy)
                    } else {
                        copyButton(rules.ADD + "…", "Add a photo or PDF of \(row.title)", lit: true, action: onAddCopy)
                    }
                    Button("Remove") { model.removeDocument(row.id) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityLabel("Remove \(row.title)")
                }
                .transition(.opacity)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.copyLine)
    }

    private func copyButton(_ label: String, _ spoken: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.caption)
            .foregroundStyle(lit ? palette.accent : palette.textSecondary)
            .accessibilityLabel(spoken)
    }
}

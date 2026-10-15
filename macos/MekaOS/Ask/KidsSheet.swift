@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Kids on the Mac (Kids' admin, slices 1–2), like the Fold's KidsPane: a card per child from About you with
/// their sizes (shoes, football boots, tops, trousers, coat, school jumper), when they were last checked (in the accent
/// three months on) and All still fit. Type a size and press Return to keep it; clear it to remove it, with Undo.
/// Slice 2: CLUB FEES under the sizes (each a bill on the radar, the line in the accent within 3 days of due) with Paid
/// and Remove (Undo), and a field that reads "Football £35 monthly from 1 Nov". Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and each card's lines cross-fade; cards stagger in; the checked
/// line blends to the accent when due; a save gives a light haptic, a refusal a tick. Reduce Motion: cross-fades.
struct KidsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Per "child|size": what the field holds while it's being edited.
    @State private var texts: [String: String] = [:]
    /// Per child: what the last save or All still fit said.
    @State private var said: [String: CoreModel.KidSaid] = [:]
    /// Slice 2, per child: the fee field's text and what the last fee action said.
    @State private var feeTexts: [String: String] = [:]
    @State private var feeSaid: [String: CoreModel.KidSaid] = [:]

    var body: some View {
        let v = model.kids ?? KidsView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(KidsRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    if let empty = v.empty {
                        Button("About you…") { dismiss(); model.showAboutYou = true }
                            .accessibilityLabel(empty)
                    }
                    ForEach(Array(v.kids.enumerated()), id: \.element.name) { i, kid in
                        card(kid).staggeredAppear(2 + i)
                    }
                }
            }
            .frame(maxHeight: 520)
            Text(KidsRules.shared.FOOT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
    private func card(_ kid: KidCard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(kid.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(kid.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(kid.spoken)
                Spacer()
                if kid.sizesLine != nil {
                    Button("All still fit") {
                        Task {
                            guard let line = await model.kidSizesFit(child: kid.name) else { return }
                            said[kid.name] = CoreModel.KidSaid(line: line, refused: false, undo: nil)
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                }
            }
            Text(kid.checkedLine).font(MekaType.caption)
                .foregroundStyle(kid.due ? palette.accent : palette.textTertiary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: kid.checkedLine)
            ForEach(kid.sizes, id: \.size) { row in
                sizeRow(kid: kid.name, row: row)
            }
            if let line = said[kid.name] {
                HStack(spacing: MekaSpace.xs) {
                    Text(line.line).font(MekaType.caption)
                        .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let undo = line.undo, let size = undoSize[kid.name] {
                        Button("Undo") {
                            Task {
                                _ = await model.setKidSize(child: kid.name, size: size, text: undo)
                                said[kid.name] = nil
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                    }
                }
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
            fees(kid)
        }
        .padding(MekaSpace.m)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Which size the last clear was, per child, so Undo writes it back there.
    @State private var undoSize: [String: String] = [:]

    private func sizeRow(kid: String, row: KidSizeRow) -> some View {
        let key = "\(kid)|\(row.size)"
        return HStack(spacing: MekaSpace.xs) {
            Text(row.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .frame(width: 130, alignment: .leading)
            TextField(row.hint, text: Binding(get: { texts[key] ?? row.value ?? "" }, set: { texts[key] = $0 }))
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = texts[key] ?? row.value ?? ""
                    let size = row.size
                    Task {
                        guard let r = await model.setKidSize(child: kid, size: size, text: typed) else { return }
                        said[kid] = r
                        if r.undo != nil { undoSize[kid] = size }
                        if !r.refused { texts[key] = nil } // the kept value shows again
                    }
                }
        }
    }

    /// Slice 2: the child's club fees, Paid / Remove with Undo, and the field that adds one.
    @ViewBuilder
    private func fees(_ kid: KidCard) -> some View {
        let name = kid.name
        Text(KidsRules.shared.FEE_HEAD).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
        ForEach(kid.fees, id: \.id) { fee in
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(fee.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(fee.line).font(MekaType.caption)
                        .foregroundStyle(fee.due ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: fee.line)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(fee.spoken)
                Spacer()
                let id = fee.id
                Button("Paid") {
                    Task { if let r = await model.kidFeePaid(id: id) { feeSaid[name] = r } }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                Button("Remove") {
                    Task { if let r = await model.removeKidFee(id: id) { feeSaid[name] = r } }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        TextField(KidsRules.shared.FEE_HINT, text: Binding(get: { feeTexts[name] ?? "" }, set: { feeTexts[name] = $0 }))
            .textFieldStyle(.roundedBorder)
            .onSubmit {
                let typed = feeTexts[name] ?? ""
                Task {
                    guard let r = await model.addKidFee(child: name, text: typed) else { return }
                    feeSaid[name] = r
                    if !r.refused { feeTexts[name] = nil }
                }
            }
        if let line = feeSaid[name] {
            HStack(spacing: MekaSpace.xs) {
                Text(line.line).font(MekaType.caption)
                    .foregroundStyle(line.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let id = line.feeUndo {
                    Button("Undo") {
                        Task {
                            if await model.undoKidFee(id: id) { feeSaid[name] = nil }
                            else { feeSaid[name] = CoreModel.KidSaid(line: KidsRules.shared.FEE_NOTHING_TO_UNDO, refused: true, undo: nil) }
                        }
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
            }
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
        }
    }
}

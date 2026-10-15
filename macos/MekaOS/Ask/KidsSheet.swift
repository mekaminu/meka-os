@preconcurrency import MekaKit
import SwiftUI

/// Ask → Life → Kids on the Mac (Kids' admin, slice 1), like the Fold's KidsPane: a card per child from About you with
/// their sizes (shoes, football boots, tops, trousers, coat, school jumper), when they were last checked (in the accent
/// three months on) and All still fit. Type a size and press Return to keep it; clear it to remove it, with Undo. Only
/// Strings cross to the core.
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
}

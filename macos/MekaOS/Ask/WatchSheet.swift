@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Watch on the Mac (Galaxy Watch, slice 1), like the Fold's WatchPane: "+ Link a watch" opens a sheet to
/// type the 8-digit code the watch shows (walk-through week 5, A17), see the linked watches ("Galaxy Watch · Linked today") and unlink one in a click. The rows
/// are the core's `WatchLinkRules`; only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); a shimmer until the server answers; rows stagger in; the summary and each
/// line cross-fade; the code's sheet stays while the server checks: Link gives a light haptic once linked and it closes,
/// a refusal fades and drops in under the code (critical colour) with a tick haptic; Unlink gives a tick haptic. Reduce Motion: cross-fades.
struct WatchSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var linking = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Watch").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.watchLink {
                Text(LoneWordGlue.shared.kept(line: view.summary)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: view.summary)
                    .staggeredAppear(1)
                Text(LoneWordGlue.shared.kept(line: view.how)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(1)
                // Walk-through week 5, A17: "+ Link a watch" is a row opening the code's sheet, not an open box.
                GoalAddRowButton(row: WatchLinkRules.shared.linkRow(linked: Int32(view.rows.count)), palette: palette) { linking = true }
                    .disabled(model.watchLinkBusy)
                    .staggeredAppear(2)
                // A wrong code is said in the sheet, under the code it's about; here only the other problems (A11).
                if let problem = WatchLinkRules.shared.problemShown(problem: view.problem, typed: "") {
                    // A refused code is an error: the critical colour, not the link gold (walk-through A22).
                    Text(LoneWordGlue.shared.kept(line: problem)).font(MekaType.caption).foregroundStyle(palette.critical)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(view.rows.enumerated()), id: \.element.id) { i, row in
                        WatchLinkRowView(row: row, palette: palette, busy: model.watchLinkBusy) {
                            MekaHaptics.tick()
                            let id = row.id
                            Task { await model.unlinkWatch(id) }
                        }
                        .staggeredAppear(i + 3)
                    }
                }
            } else {
                Text("Checking…").font(MekaType.body).foregroundStyle(palette.textSecondary)
                SkeletonRows(count: 1, palette: palette)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.watchLink?.problem)
        .task { await model.refreshWatches() }
        .sheet(isPresented: $linking) {
            WatchLinkCodeSheet(palette: palette) { linking = false }
        }
    }
}

private struct WatchLinkRowView: View {
    let row: LinkedWatchRow
    let palette: MekaPalette
    let busy: Bool
    let onUnlink: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Circle().fill(palette.success).frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: row.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
            }
            Spacer()
            Button("Unlink", action: onUnlink)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.critical)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surfaceRaised, in: Capsule())
                .disabled(busy)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        // Walk-through 2026-11-15, A21 part 9: Light's 1 pt edge, so the row doesn't melt into the sheet.
        .lightCardEdge(palette)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.title). \(row.line)")
    }
}

/// The code's sheet (walk-through week 5, A17), like the Fold's: the code with an example, where the watch shows it,
/// Close · Link (disabled until something is typed and while the server checks). It stays open until the watch is
/// linked, a wrong code said under it while the code it's about is still there. Only Strings cross to the core.
private struct WatchLinkCodeSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let close: () -> Void
    @State private var typed = ""

    var body: some View {
        let sheet = WatchLinkRules.shared.linkSheet
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sheet.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            TextField(sheet.hint, text: $typed).textFieldStyle(.roundedBorder).onSubmit(link)
                .accessibilityLabel(sheet.spokenHint)
                .onChange(of: typed) { _, raw in
                    let kept = WatchLinkRules.shared.typedCode(raw: raw)
                    if kept != raw { typed = kept }
                }
            if let problem = WatchLinkRules.shared.problemShown(problem: model.watchLink?.problem, typed: typed) {
                Text(LoneWordGlue.shared.kept(line: problem)).font(MekaType.caption).foregroundStyle(palette.critical)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            Text(LoneWordGlue.shared.kept(line: sheet.note)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(GoalAddRules.shared.CLOSE, action: close).keyboardShortcut(.cancelAction)
                Button(sheet.addLabel, action: link).keyboardShortcut(.defaultAction)
                    .disabled(!WatchLinkRules.shared.canLink(typed: typed, busy: model.watchLinkBusy))
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.watchLink?.problem)
    }

    private func link() {
        let code = typed
        guard WatchLinkRules.shared.canLink(typed: code, busy: model.watchLinkBusy) else { return }
        Task {
            if await model.linkWatch(code) {
                MekaHaptics.light()
                close()
            } else {
                MekaHaptics.tick()
            }
        }
    }
}

@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Watch on the Mac (Galaxy Watch, slice 1), like the Fold's WatchPane: type the 8-digit code the watch
/// shows to link it to MEKA, see the linked watches ("Galaxy Watch · Linked today") and unlink one in a click. The rows
/// are the core's `WatchLinkRules`; only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); a shimmer until the server answers; rows stagger in; the summary and each
/// line cross-fade; Link gives a light haptic once linked and the field clears; a problem fades and drops in (accent)
/// with a tick haptic; Unlink gives a tick haptic. Reduce Motion: cross-fades.
struct WatchSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var typed = ""

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Watch").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.watchLink {
                Text(view.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: view.summary)
                    .staggeredAppear(1)
                Text(view.how).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(1)
                HStack(spacing: MekaSpace.xs) {
                    TextField("Code from the watch", text: $typed)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(link)
                        .disabled(model.watchLinkBusy)
                    Button("Link", action: link)
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                        .disabled(model.watchLinkBusy || typed.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .staggeredAppear(2)
                if let problem = view.problem {
                    Text(problem).font(MekaType.caption).foregroundStyle(palette.accent)
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
    }

    private func link() {
        let code = typed
        guard !code.trimmingCharacters(in: .whitespaces).isEmpty, !model.watchLinkBusy else { return }
        Task {
            if await model.linkWatch(code) {
                MekaHaptics.light()
                typed = ""
            } else {
                MekaHaptics.tick()
            }
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
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.title). \(row.line)")
    }
}

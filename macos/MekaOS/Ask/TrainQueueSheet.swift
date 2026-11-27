@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Train queue on the Mac (train-time queue, slice 1), like the Fold's TrainQueuePane: links and podcasts
/// saved for the commute (shared on the Fold, or pasted in the "+ Add a link" sheet), newest first. Click one to open it; Done when read or
/// listened to (it moves to "Done lately", with Back), Remove for good. Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the field's line cross-fade; rows stagger in and move
/// between Waiting and Done lately on the expand spring; a save or Done gives a light haptic, a refusal, Back or Remove
/// a tick. Reduce Motion: cross-fades.
struct TrainQueueSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var adding = false
    @State private var said: CoreModel.QueueSaid?

    var body: some View {
        let v = model.queue ?? QueueView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Train queue").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            // Walk-through week 5, A17: no open link box on the page; "+ Add a link" is a row opening the link's sheet.
            GoalAddRowButton(row: QueueRules.shared.addRow, palette: palette) { adding = true }
                .staggeredAppear(2)
            if let said {
                Text(LoneWordGlue.shared.kept(line: said.line)).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if !v.waiting.isEmpty { label("Waiting") }
                    ForEach(v.waiting, id: \.id) { row in
                        QueueRowView(row: row, palette: palette, primary: "Done",
                                     onOpen: { open(row.url) },
                                     onPrimary: { model.queueDone(row.id, done: true) },
                                     onRemove: { model.removeFromQueue(row.id) })
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                    if !v.done.isEmpty { label("Done lately") }
                    ForEach(v.done, id: \.id) { row in
                        QueueRowView(row: row, palette: palette, primary: "Back",
                                     onOpen: { open(row.url) },
                                     onPrimary: { model.queueDone(row.id, done: false) },
                                     onRemove: { model.removeFromQueue(row.id) })
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: v.waiting.map(\.id) + v.done.map(\.id))
            }
            .frame(maxHeight: 420)
            .staggeredAppear(3)
            Text(LoneWordGlue.shared.kept(line: QueueRules.shared.NOTE)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
        .sheet(isPresented: $adding) {
            LineAddSheet(sheet: QueueRules.shared.addSheet, palette: palette,
                         canAdd: { QueueRules.shared.canAdd(text: $0) }, close: { adding = false }) { typed in
                Task {
                    guard let r = await model.addToQueue(typed) else { return }
                    said = r
                }
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
    }

    private func open(_ link: String) {
        if let url = URL(string: link) { openURL(url) }
    }
}

private struct QueueRowView: View {
    let row: QueueRow
    let palette: MekaPalette
    let primary: String
    let onOpen: () -> Void
    let onPrimary: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(LoneWordGlue.shared.kept(line: row.title)).font(MekaType.body)
                        .foregroundStyle(row.done ? palette.textSecondary : palette.textPrimary)
                        .lineLimit(2)
                    Text(LoneWordGlue.shared.kept(line: row.meta)).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("Open \(row.spoken)")
            Button(primary, action: onPrimary)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("\(primary): \(row.title)")
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.title)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        // Walk-through 2026-11-15, A21 part 9: Light's 1 pt edge, so the row doesn't melt into the sheet.
        .lightCardEdge(palette)
        .mekaHoverLift()
    }
}

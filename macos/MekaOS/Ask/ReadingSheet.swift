@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Reading on the Mac (reading and learning, slice 1), like the Fold's ReadingPane: one field adds a
/// book, podcast or course; ON THE GO with how far (a brass bar) and a field for progress, Finished and Put down; UP
/// NEXT with Start; FINISHED LATELY and PUT DOWN with Again / Pick up; Remove on each; every answer with Undo. Only
/// Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary, the month line and the answer cross-fade; rows move between
/// sections on the expand spring; a bar grows to its new length on the same spring; a save, Start or Finished gives a
/// light haptic, the rest a tick. Reduce Motion: cross-fades, the bar jumps.
struct ReadingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var said: CoreModel.ReadingSaidLine?

    var body: some View {
        let v = model.reading ?? ReadingView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(ReadingRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            if let month = v.month {
                Text(month).font(MekaType.caption).foregroundStyle(palette.accent)
                    .transition(.opacity)
            }
            TextField(ReadingRules.shared.HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { add() }
                .staggeredAppear(2)
            if let said { saidLine(said) }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    section("On the go", v.onTheGo, actions: goActions, progress: true)
                    section("Up next", v.upNext, actions: startAction("Start"), progress: false)
                    section("Finished lately", v.finished, actions: startAction("Again"), progress: false)
                    section("Put down", v.putDown, actions: startAction("Pick up"), progress: false)
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: ids(v))
            }
            .frame(maxHeight: 460)
            .staggeredAppear(3)
            Text(ReadingRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.month)
    }

    /// One button on a row: its label and what it asks the core (with the row's id).
    typealias RowAct = (label: String, run: (String) async -> CoreModel.ReadingSaidLine?)

    private var goActions: [RowAct] {
        let m = model
        let finish: RowAct = (label: "Finished", run: { id in await m.finishReading(id) })
        let down: RowAct = (label: "Put down", run: { id in await m.putDownReading(id) })
        return [finish, down]
    }

    private func startAction(_ label: String) -> [RowAct] {
        let m = model
        let start: RowAct = (label: label, run: { id in await m.startReading(id) })
        return [start]
    }

    private func ids(_ v: ReadingView) -> [String] {
        let groups: [[ReadingRow]] = [v.onTheGo, v.upNext, v.finished, v.putDown]
        return groups.flatMap { rows in rows.map { $0.id + $0.state + $0.progress } }
    }

    private func add() {
        let typed = text
        text = ""
        Task {
            guard let r = await model.addReading(typed) else { return }
            said = r
            if r.refused { text = typed } // keep the words to fix
        }
    }

    private func saidLine(_ s: CoreModel.ReadingSaidLine) -> some View {
        HStack(spacing: MekaSpace.m) {
            Text(s.line).font(MekaType.caption)
                .foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let token = s.undo {
                Button("Undo") {
                    Task {
                        let ok = await model.undoReading(token)
                        said = CoreModel.ReadingSaidLine(line: ok ? "Taken back" : "Too late to undo · change it by hand", refused: !ok, undo: nil)
                    }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
            }
        }
        .contentTransition(.opacity)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: s)
    }

    @ViewBuilder
    private func section(
        _ title: String, _ rows: [ReadingRow],
        actions: [RowAct], progress: Bool
    ) -> some View {
        if !rows.isEmpty {
            Text(title.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xs)
            ForEach(rows, id: \.id) { row in
                ReadingRowView(
                    row: row, palette: palette, progress: progress,
                    actions: buttons(actions, row.id),
                    onProgress: { typed in act { await model.readingProgress(row.id, typed) } },
                    onRemove: { act { await model.removeReading(row.id) } }
                )
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func buttons(_ acts: [RowAct], _ id: String) -> [(String, () -> Void)] {
        acts.map { a -> (String, () -> Void) in
            let run = a.run
            return (a.label, { act { await run(id) } })
        }
    }

    private func act(_ body: @escaping () async -> CoreModel.ReadingSaidLine?) {
        Task {
            if let r = await body() { said = r }
        }
    }
}

private struct ReadingRowView: View {
    let row: ReadingRow
    let palette: MekaPalette
    let progress: Bool
    let actions: [(String, () -> Void)]
    let onProgress: (String) -> Void
    let onRemove: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var typed = ""

    var body: some View {
        let ended = row.state == "finished" || row.state == "put_down"
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body)
                    .foregroundStyle(ended ? palette.textSecondary : palette.textPrimary)
                    .lineLimit(2)
                Text(row.meta).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                Text(row.progress).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            if let f = row.fraction?.doubleValue {
                ProgressBar(fraction: f, palette: palette)
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: f)
            }
            if progress {
                TextField(ReadingRules.shared.PROGRESS_HINT, text: $typed)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        let t = typed
                        typed = ""
                        onProgress(t)
                    }
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(actions.indices, id: \.self) { i in
                    Button(actions[i].0, action: actions[i].1)
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("\(actions[i].0): \(row.title)")
                }
                Button("Remove", action: onRemove)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(row.title)")
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

private struct ProgressBar: View {
    let fraction: Double
    let palette: MekaPalette

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(palette.surfaceRaised)
                Capsule().fill(palette.accent).frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 4)
        .padding(.vertical, MekaSpace.xxs)
        .accessibilityHidden(true)
    }
}

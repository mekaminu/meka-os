@preconcurrency import MekaKit
import SwiftUI

/// Repeat row in the task detail: the rule, opening a menu of presets for the task's day (simpler than the Fold's
/// inline picker, as rule 7 allows).
struct RepeatMenu: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette
    @State private var choices: [RepeatChoice] = []

    var body: some View {
        Menu {
            ForEach(choices, id: \.label) { c in
                Button {
                    model.setRepeat(task.id, rule: c.rule)
                } label: {
                    if c.selected { Label(c.label, systemImage: "checkmark") } else { Text(c.label) }
                }
            }
        } label: {
            HStack {
                Text("Repeat").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(task.repeatLabel ?? "Doesn't repeat").font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .task(id: refreshKey) { choices = await model.repeatChoices(task.id) }
    }

    /// Re-read the presets whenever the rule or the task's day changes.
    private var refreshKey: String {
        "\(task.id)|\(task.recurrenceRule ?? "")|\(task.occurrenceDay?.int64Value ?? -1)|\(task.scheduledAtMs?.int64Value ?? -1)|\(task.dueAtMs?.int64Value ?? -1)"
    }
}

/// Steps. A repeating task with steps is a routine: every new occurrence brings its steps back unticked.
/// Motion: a tick pops with a spring; Reduce Motion changes colour only.
struct StepsList: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette
    @State private var newStep = ""

    private func addPending(to id: String) {
        if let step = TextAutosave.shared.pendingAdd(typed: newStep) { model.addStep(id, step) }
        newStep = ""
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(task.isRepeating ? "Routine steps" : "Steps", palette)
                .padding(.top, MekaSpace.m)
            ForEach(task.checklist, id: \.id) { step in
                StepRow(step: step, palette: palette)
                    .transition(.opacity)
            }
            TextField("Add a step…", text: $newStep)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .onSubmit { model.addStep(task.id, newStep); newStep = "" }
                // A step typed but not yet added is added when the detail closes or shows another task, as if
                // Return had been pressed (TextAutosave: typing is never lost).
                .onChange(of: task.id) { oldId, _ in addPending(to: oldId) }
                .onDisappear { addPending(to: task.id) }
        }
    }
}

private struct StepRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let step: ChecklistItem
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Button {
                model.setStepDone(step.id, !step.checked)
            } label: {
                TickRingView(done: step.checked, palette: palette)
                    .frame(width: 18, height: 18)
                    .contentShape(Circle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel((step.checked ? "Untick " : "Tick ") + step.text)
            Text(step.text)
                .font(MekaType.body)
                .foregroundStyle(step.checked ? palette.textTertiary : palette.textPrimary)
            Spacer()
            Button("Remove") { model.removeStep(step.id) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
        }
    }
}

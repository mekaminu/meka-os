@preconcurrency import MekaKit
import SwiftUI

/// When (Fold review 2026-10-08, item 8): "Today · 14:30" in the row; clicking unfolds Today · Tomorrow · (a picked
/// day) · Pick a date… (a graphical date popover the system scale-fades), then the time: Add a time, or ‹ 14:30 ›
/// stepping a quarter hour with a tick haptic and No time. Unfolds with the expand spring (Reduce Motion: fades).
struct WhenRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var open = false
    @State private var picking = false

    var body: some View {
        if let v = model.taskWhen(task) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Button {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
                } label: {
                    HStack {
                        Text("When").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(v.label).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: v.label)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("When, \(v.label)")

                if open {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        HStack(spacing: MekaSpace.xs) {
                            ForEach(v.chips, id: \.day) { c in
                                chip(c.label, lit: c.selected) {
                                    if !c.selected { model.setWhen(task.id, day: c.day, minute: minute(v)) }
                                }
                            }
                            chip("Pick a date…", lit: false) { picking = true }
                                .popover(isPresented: $picking) { picker(v) }
                        }
                        HStack(spacing: MekaSpace.xs) {
                            if let m = minute(v) {
                                step("‹", "15 minutes earlier") { model.setWhen(task.id, day: v.day, minute: Int(TaskWhenRules.shared.step(minute: Int32(m), steps: -1))) }
                                Text(v.timeLabel ?? "").font(MekaType.itemTitle).monospacedDigit()
                                    .contentTransition(.opacity)
                                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.timeLabel)
                                step("›", "15 minutes later") { model.setWhen(task.id, day: v.day, minute: Int(TaskWhenRules.shared.step(minute: Int32(m), steps: 1))) }
                                chip("No time", lit: false) { model.setWhen(task.id, day: v.day, minute: nil) }
                                    .padding(.leading, MekaSpace.xs)
                            } else {
                                chip("Add a time", lit: false) { model.setWhen(task.id, day: v.day, minute: Int(v.suggestedMinute)) }
                            }
                        }
                    }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    private func minute(_ v: TaskWhenView) -> Int? { v.minuteOrNone >= 0 ? Int(v.minuteOrNone) : nil }

    private func picker(_ v: TaskWhenView) -> some View {
        let today = model.todayEpochDay
        let last = today + Int64(TaskWhenRules.shared.MAX_DAYS_AHEAD)
        return DatePicker(
            "Day",
            selection: Binding(
                get: { CoreModel.date(ofEpochDay: v.day) },
                set: { d in
                    picking = false
                    model.setWhen(task.id, day: CoreModel.epochDay(of: d), minute: minute(v))
                }
            ),
            in: CoreModel.date(ofEpochDay: today)...CoreModel.date(ofEpochDay: last),
            displayedComponents: .date
        )
        .datePickerStyle(.graphical)
        .labelsHidden()
        .padding(MekaSpace.m)
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }

    private func step(_ symbol: String, _ description: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(symbol).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
                .background(Capsule().fill(palette.surfaceRaised))
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(description)
    }
}

/// Remind me (Fold review 2026-10-08, item 8): "Off" or when ("Today · 14:15") in the row; clicking unfolds the chips
/// still ahead (At 14:30 · 15 min before · 1 h before, or In 1 h · 13:00 · 18:00) and Off, with the expand spring
/// (Reduce Motion: fades). A heads-up through the notification governor; it moves with the task's When.
struct ReminderRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var open = false

    var body: some View {
        if let v = model.taskReminder(task), v.isSet || !v.choices.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Button {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
                } label: {
                    HStack {
                        Text("Remind me").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(v.label).font(MekaType.itemMeta).foregroundStyle(v.isSet ? palette.accent : palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: v.label)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Remind me, \(v.label)")

                if open {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(v.choices, id: \.atMs) { c in
                            chip(c.label, lit: c.selected) { model.setReminder(task.id, at: c.selected ? nil : c.atMs) }
                        }
                        if v.isSet { chip("Off", lit: false) { model.setReminder(task.id, at: nil) } }
                    }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

/// Notes (walk-through 2026-11-22 A17: no open box in the detail): with no notes, one "+ Add notes" row; with notes,
/// they show whole in a raised card. A click on either opens the editor in place (fade and expand; Reduce Motion
/// fades), focused, with Done at its foot. Saved a moment after typing stops, on Done and when the detail closes;
/// blank clears them and the row comes back.
struct NotesEditor: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var text = ""
    @State private var dirty = false
    @State private var editing = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(TaskNotesRules.shared.LABEL, palette).padding(.top, MekaSpace.m)
            content
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: editing)
        .onAppear { text = task.notes ?? "" }
        // Synced notes arrive while nothing here is waiting to be saved.
        .onChange(of: task.notes) { _, new in if !dirty { text = new ?? "" } }
        .task(id: text) {
            guard dirty else { return }
            try? await Task.sleep(for: .milliseconds(800))
            if !Task.isCancelled { save() }
        }
        .onDisappear { save() }
    }

    @ViewBuilder private var content: some View {
        if editing {
            editor
        } else if let shown = TaskNotesRules.shared.shown(notes: text) {
            Button { MekaHaptics.light(); editing = true; focused = true } label: {
                Text(shown).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(MekaSpace.m)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(TaskNotesRules.shared.EDIT_DESCRIPTION)
            .transition(.opacity)
        } else {
            Button(TaskNotesRules.shared.ADD) { MekaHaptics.light(); editing = true; focused = true }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.body)
                .foregroundStyle(palette.accent)
                .transition(.opacity)
        }
    }

    private var editor: some View {
        VStack(alignment: .trailing, spacing: MekaSpace.xs) {
            ZStack(alignment: .topLeading) {
                if text.isEmpty {
                    Text(TaskNotesRules.shared.HINT).font(MekaType.body).foregroundStyle(palette.textTertiary)
                        .padding(.top, 1).padding(.leading, 5) // rhythm: ok (lines the placeholder up with TextEditor's own text inset)
                }
                TextEditor(text: Binding(get: { text }, set: { text = $0; dirty = true }))
                    .font(MekaType.body)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 72)
                    .focused($focused)
                    .accessibilityLabel(TaskNotesRules.shared.LABEL)
            }
            Button(TaskNotesRules.shared.DONE) { MekaHaptics.tick(); finish() }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
        }
        .padding(MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private func finish() {
        let keep = TaskNotesRules.shared.saved(text: text) ?? ""
        if keep != text { text = keep; dirty = true }
        save()
        editing = false
    }

    private func save() {
        guard dirty else { return }
        dirty = false
        model.setNotes(task.id, text)
    }
}

/// The detail's actions as pills: Done (filled) · Skip (repeating) · Tomorrow · Someday (one-off); Delete is a quiet
/// red line under them with an undo bar, no dialog.
struct DetailActionPills: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                pill("Done", filled: true) { MekaHaptics.light(); model.complete(task.id) } // ⌘↩ lives in the Today menu
                if task.isRepeating { pill("Skip", filled: false) { MekaHaptics.tick(); model.skip(task.id) } }
                pill("Tomorrow", filled: false) { MekaHaptics.tick(); model.snooze(task.id) }
                if !task.isRepeating { pill("Someday", filled: false) { MekaHaptics.tick(); model.moveToSomeday(task.id) } }
            }
            Button("Delete") { MekaHaptics.tick(); model.delete(task.id) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.critical)
        }
    }

    private func pill(_ label: String, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption)
                .foregroundStyle(filled ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(filled ? palette.accent : palette.surfaceRaised))
        }
        .buttonStyle(MekaPressStyle())
    }
}

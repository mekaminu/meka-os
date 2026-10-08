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
            VStack(alignment: .leading, spacing: MekaSpace.s) {
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
                    VStack(alignment: .leading, spacing: MekaSpace.s) {
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
                                    .padding(.leading, MekaSpace.s)
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

/// Notes: a multi-line editor, saved a moment after typing stops and when the detail closes; blank clears them.
struct NotesEditor: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette
    @State private var text = ""
    @State private var dirty = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("Notes", palette).padding(.top, MekaSpace.m)
            ZStack(alignment: .topLeading) {
                if text.isEmpty {
                    Text("Add notes…").font(MekaType.body).foregroundStyle(palette.textTertiary)
                        .padding(.top, 1).padding(.leading, 5)
                }
                TextEditor(text: Binding(get: { text }, set: { text = $0; dirty = true }))
                    .font(MekaType.body)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 72)
                    .accessibilityLabel("Notes")
            }
            .padding(MekaSpace.s)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        }
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
        VStack(alignment: .leading, spacing: MekaSpace.s) {
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
                .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.s)
                .background(Capsule().fill(filled ? palette.accent : palette.surfaceRaised))
        }
        .buttonStyle(MekaPressStyle())
    }
}

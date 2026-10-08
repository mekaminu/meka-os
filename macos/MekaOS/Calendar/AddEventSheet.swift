@preconcurrency import MekaKit
import SwiftUI

/// Add event on the Mac (calendar editing, slice 2b), matching android/.../calendar/AddEventPane.kt: a real event on
/// one of Meka's Google/Outlook accounts where editing is allowed. Title (focused), the day (Today · Tomorrow · Pick a
/// date… · All day), the start stepped a quarter hour at a time with the length chips, the calendar (a menu when there
/// are several; the one added to last time first), a place and notes. Add (⏎) makes a synced edit that waits five
/// seconds for Undo (the shell's undo bar) before the server sends it; Esc cancels.
///
/// Motion (catalogue "Add event"): the system sheet scale-fades in; rows stagger in; chips blend their colour with a
/// tick haptic; the digits cross-fade; Add gives a light haptic and the sheet drops away as the undo bar rises.
/// Reduce Motion: cross-fades.
struct AddEventSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let day: Int64
    let palette: MekaPalette
    @State private var form: AddEventForm?
    @State private var refusal: String?
    @State private var picking = false
    @State private var sending = false
    @FocusState private var titleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let f = form, let v = model.addEventView(f) {
                content(f, v)
            } else {
                Color.clear.frame(height: 240)
            }
        }
        .padding(MekaSpace.xl)
        .frame(width: 480)
        .background(palette.background)
        .task {
            form = await model.addEventForm(day: day)
            titleFocused = true
        }
        .onChange(of: model.editAccounts) { _, accounts in
            // An account allowed while the sheet is open becomes the choice.
            guard let f = form, !accounts.contains(where: { $0.key == f.accountKey }), let first = accounts.first else { return }
            form = f.withAccount(key: first.key)
        }
    }

    @ViewBuilder
    private func content(_ f: AddEventForm, _ v: AddEventView) -> some View {
        Text("Add event")
            .font(MekaType.greeting).tracking(MekaType.greetingTracking)
            .foregroundStyle(palette.textPrimary)
            .accessibilityAddTraits(.isHeader)
            .staggeredAppear(0)

        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            field("Title", text: Binding(get: { f.title }, set: { set(form?.withTitle(text: $0)) }))
                .font(MekaType.itemTitle)
                .focused($titleFocused)
            Text(v.summary).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
        }
        .padding(.top, MekaSpace.m)
        .staggeredAppear(1)

        Group {
        label("When").staggeredAppear(2)
        HStack(spacing: MekaSpace.xs) {
            ForEach(v.chips, id: \.day) { c in
                chip(c.label, lit: c.selected) { if !c.selected { MekaHaptics.tick(); set(f.withDay(epochDay: c.day)) } }
            }
            chip("Pick a date…", lit: false) { picking = true }
                .popover(isPresented: $picking) { picker(f) }
            chip("All day", lit: f.isAllDay) { MekaHaptics.tick(); set(f.withAllDay(on: !f.isAllDay)) }
        }
        .staggeredAppear(2)

        if let start = v.startLabel {
            VStack(alignment: .leading, spacing: MekaSpace.s) {
                HStack(spacing: MekaSpace.xs) {
                    step("‹", "15 minutes earlier") { MekaHaptics.tick(); set(f.stepTime(steps: -1)) }
                    Text(start).font(MekaType.itemTitle).monospacedDigit().foregroundStyle(palette.textPrimary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: start)
                    step("›", "15 minutes later") { MekaHaptics.tick(); set(f.stepTime(steps: 1)) }
                    if let end = v.endLabel {
                        Text("until \(end)").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: end)
                            .padding(.leading, MekaSpace.s)
                    }
                }
                HStack(spacing: MekaSpace.xs) {
                    ForEach(v.lengths, id: \.minutes) { l in
                        chip(l.label, lit: l.selected) { if !l.selected { MekaHaptics.tick(); set(f.withLength(minutes: l.minutes)) } }
                    }
                }
            }
            .padding(.top, MekaSpace.s)
            .transition(.opacity)
            .staggeredAppear(3)
        }
        }

        Group {
        label("Calendar").staggeredAppear(4)
        Group {
            if v.accounts.count > 1 {
                Picker("Calendar", selection: Binding(
                    get: { f.accountKey ?? "" },
                    set: { key in MekaHaptics.tick(); set(form?.withAccount(key: key)) }
                )) {
                    ForEach(v.accounts, id: \.key) { a in Text(a.label).tag(a.key) }
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .fixedSize()
            } else {
                Text(v.accounts.first?.label ?? "No calendar allows editing yet")
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
        }
        .staggeredAppear(4)

        label("Place").staggeredAppear(5)
        field("Add a place", text: Binding(get: { f.location }, set: { set(form?.withLocation(text: $0)) }))
            .staggeredAppear(5)
        }

        Group {
        label("Notes").staggeredAppear(6)
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            field("Add notes…", text: Binding(get: { f.notes }, set: { set(form?.withNotes(text: $0)) }), lines: 3)
            if let note = v.notesNote {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .staggeredAppear(6)
        }

        VStack(alignment: .leading, spacing: MekaSpace.s) {
            if let problem = refusal ?? v.problem {
                Text(problem).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: problem)
            }
            HStack(spacing: MekaSpace.s) {
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                    .buttonStyle(MekaPressStyle())
                    .foregroundStyle(palette.accent)
                Spacer()
                Button { add() } label: {
                    Text(v.addLabel).font(MekaType.caption)
                        .foregroundStyle(palette.onAccent)
                        .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.s)
                        .background(Capsule().fill(palette.accent))
                        .opacity(v.canAdd && !sending ? 1 : 0.45)
                }
                .buttonStyle(MekaPressStyle())
                .keyboardShortcut(.defaultAction)
                .disabled(!v.canAdd || sending)
            }
        }
        .padding(.top, MekaSpace.l)
        .staggeredAppear(7)
    }

    /// A new form value from a step (clears an earlier refusal).
    private func set(_ f: AddEventForm?) {
        guard let f else { return }
        form = f
        refusal = nil
    }

    private func add() {
        guard let f = form, !sending else { return }
        sending = true
        Task {
            let problem = await model.addEvent(f)
            sending = false
            if let problem { refusal = problem } else { dismiss() }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.l).padding(.bottom, MekaSpace.xs)
            .accessibilityAddTraits(.isHeader)
    }

    private func field(_ placeholder: String, text: Binding<String>, lines: Int = 1) -> some View {
        TextField(placeholder, text: text, axis: lines > 1 ? .vertical : .horizontal)
            .lineLimit(lines > 1 ? lines...8 : 1...1)
            .textFieldStyle(.plain)
            .foregroundStyle(palette.textPrimary)
            .padding(MekaSpace.m)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
            .accessibilityLabel(placeholder)
    }

    private func picker(_ f: AddEventForm) -> some View {
        let last = f.today + Int64(TaskWhenRules.shared.MAX_DAYS_AHEAD)
        return DatePicker(
            "Day",
            selection: Binding(
                get: { CoreModel.date(ofEpochDay: f.day) },
                set: { d in
                    picking = false
                    MekaHaptics.tick()
                    set(form?.withDay(epochDay: CoreModel.epochDay(of: d)))
                }
            ),
            in: CoreModel.date(ofEpochDay: f.today)...CoreModel.date(ofEpochDay: last),
            displayedComponents: .date
        )
        .datePickerStyle(.graphical)
        .labelsHidden()
        .padding(MekaSpace.m)
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
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

/// The Calendar section's lines about edits ("Adding “Dentist” to Google", "Added …", a clash or a refusal lit in the
/// accent colour); each cross-fades as it changes.
struct EditLinesView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let lines: [EditLine]
    let palette: MekaPalette

    var body: some View {
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                ForEach(lines, id: \.id) { l in
                    Text(l.text).font(MekaType.caption).lineLimit(2)
                        .foregroundStyle(l.needsMeka ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: l.text)
                }
            }
        }
    }
}

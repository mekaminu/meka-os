@preconcurrency import MekaKit
import SwiftUI

/// Event detail on the Mac (calendar redesign, slice 3), matching android/.../calendar/EventDetailPane.kt: what, when
/// and how soon, which calendar, where (click to open Maps), a Join button for Meet/Teams/Zoom links, and the event's
/// notes. Opened by clicking an event in Today or the Calendar section. Shows only: events change in their calendar.
///
/// The notes come from whoever made the event, so they are untrusted (ADR-006): plain text only, nothing in them is
/// opened unless you click Join (an https link to a known call service, or the provider's own link).
///
/// Calendar editing (slice 2c): where the account allows editing, Edit turns the sheet into the event's Edit form
/// (`AddEventSheet` with `editing`); Save and Delete close it and raise the shell's undo bar for five seconds before
/// anything is sent. The event's latest edit is said under the actions, and a delete the server held back for its
/// guests offers Delete anyway (Meka's second click).
///
/// Motion: the sheet scale-fades in (macOS); sections stagger in; Edit cross-fades to the form. Reduce Motion: cross-fades.
struct EventDetailSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var editing = false

    var body: some View {
        // "In 25 min" moves on while the sheet is open.
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            VStack(alignment: .leading, spacing: MekaSpace.s) {
                // Reading the marks here re-renders the sheet when a prep task or hide lands (or syncs in).
                let _ = model.eventMarks
                let _ = model.editAccounts
                let _ = model.editLines
                if editing, let e = model.openEvent {
                    AddEventSheet(day: -1, palette: palette, editing: e, onCancel: { editing = false })
                        .transition(.opacity)
                } else if let e = model.openEvent, let d = model.eventDetail(e) {
                    content(d, event: e)
                } else {
                    Text("Event").font(MekaType.upNextTitle)
                }
                if !editing {
                    HStack {
                        Spacer()
                        Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                    }
                }
            }
            .padding(editing ? 0 : MekaSpace.l)
            .frame(width: editing ? 480 : 440)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: editing)
        }
        .task { await model.refreshEditAccounts() }
    }

    @ViewBuilder
    private func content(_ d: EventDetailView, event: CalendarEvent) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(d.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Text([d.whenLine, d.duration].compactMap { $0 }.joined(separator: " · "))
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            if let status = d.status {
                Text(status).font(MekaType.itemMeta)
                    .foregroundStyle(d.statusLit ? palette.accent : palette.textTertiary)
                    .contentTransition(.opacity)
            }
        }
        .staggeredAppear(0)

        // Calendar actions: MEKA-only, the real event is untouched. An event just added in MEKA has none until Google
        // has it (its line below says "Adding “Dentist” to Google").
        if !d.provisional {
            HStack(spacing: MekaSpace.s) {
                if d.canPrep {
                    Button("Prep task") { model.addPrepTask(event) }
                }
                // Weekend football: a club fixture's kit list, planned and reminded at 19:00 the evening before.
                if d.canKit {
                    Button(FootballRules.shared.CHIP) { model.addKitReminder(event) }
                }
                // Weekend football, slice 2: a ground Meka set a travel time for before.
                if let offer = d.leaveOfferLabel {
                    Button(offer) { model.useLastLeaveBy(event) }
                }
                Button(d.hidden ? "Show in my day" : "Hide from my day") {
                    if d.hidden { model.showEvent(d.id) } else { model.hideEvent(d.id, offerUndo: false) }
                }
                // Calendar editing: changes the real event (after five seconds' Undo); not while an edit is on its way.
                if d.editable && !(d.edit?.waiting ?? false) {
                    Button("Edit") { MekaHaptics.tick(); editing = true }
                }
                let note = [d.prepLine, d.kitLine, d.reminderLine, d.hidden ? "Hidden from your day" : nil].compactMap { $0 }.joined(separator: " · ")
                if !note.isEmpty {
                    Text(note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                }
            }
            .controlSize(.small)
            .staggeredAppear(1)
        }

        // Weekend football, slice 3: "Running late?" around kick-off. Each choice opens the share menu with the drafted
        // message; Meka picks where it goes and sends it himself (MEKA never sends it). Only Strings cross from the core.
        if !d.provisional && !d.lateDrafts.isEmpty {
            let drafts = d.lateDrafts.map { LateDraftRow(label: $0.label, text: $0.text) }
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(FootballRules.shared.LATE_TITLE.uppercased()).font(MekaType.sectionLabel)
                    .foregroundStyle(palette.textTertiary)
                HStack(spacing: MekaSpace.s) {
                    ForEach(drafts) { draft in
                        ShareLink(item: draft.text) { Text(draft.label) }
                    }
                }
                .controlSize(.small)
                Text(FootballRules.shared.LATE_CAPTION).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            .staggeredAppear(1)
        }

        // Weekend football, slice 4: "How did it go?" once a club fixture is over. Only Strings, Ints and Bools cross
        // from the core into the view.
        if !d.provisional && d.canResult {
            MatchResultView(
                eventID: d.id, scoreAllowed: d.resultScore, kept: d.result != nil,
                line: d.resultLine, note: d.resultNote,
                keptFor: Int(d.result?.forOrNone ?? -1), keptAgainst: Int(d.result?.againstOrNone ?? -1),
                keptScorers: d.result?.scorers ?? "", keptNote: d.result?.note ?? "",
                palette: palette
            ) { ours, theirs, scorers, note in
                model.saveMatchResult(event, ours: ours, theirs: theirs, scorers: scorers, note: note)
            }
            .id(d.id)
            .staggeredAppear(2)
        }

        if let note = d.edit {
            HStack(spacing: MekaSpace.s) {
                Text(note.text).font(MekaType.caption)
                    .foregroundStyle(note.needsMeka ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: note.text)
                    .fixedSize(horizontal: false, vertical: true)
                if note.deleteAnyway && d.editable, let e = model.openEvent {
                    Button("Delete anyway") {
                        Task { if await model.deleteEvent(e, guestsOk: true) == nil { dismiss() } }
                    }
                    .buttonStyle(MekaPressStyle())
                    .foregroundStyle(palette.critical)
                }
            }
            .staggeredAppear(1)
        }
        // Clash chooser (slice 2c-iii): both versions of what the edit touched; Meka chooses, never MEKA. Unfolds
        // with the expand spring.
        Group {
            if let c = d.edit?.clash {
                ClashChooserView(
                    clash: c, canKeepMine: d.editable,
                    keepMine: {
                        let id = c.editId
                        Task { if await model.keepMyVersion(id) == nil { dismiss() } }
                    },
                    keepTheirs: {
                        let id = c.editId
                        Task { await model.keepTheirVersion(id) }
                    },
                    palette: palette
                )
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: d.edit?.clash?.editId)

        // Remind me / Leave by: a heads-up through the notification governor (quiet hours apply).
        let remindChoices = d.remindChoices.map { $0.int32Value }
        let travelChoices = d.travelChoices.map { $0.int32Value }
        if !remindChoices.isEmpty || d.remindMin != 0 || !travelChoices.isEmpty || d.travelMin != 0 {
            HStack(spacing: MekaSpace.m) {
                if !remindChoices.isEmpty || d.remindMin != 0 {
                    Picker("Remind me", selection: Binding(
                        get: { d.remindMin },
                        set: { model.setEventReminder(d.id, $0, offerUndo: false) }
                    )) {
                        Text("No reminder").tag(Int32(0))
                        ForEach(Self.withCurrent(remindChoices, d.remindMin), id: \.self) { m in
                            Text(ReminderRules.shared.choiceLabel(minutes: m)).tag(m)
                        }
                    }
                    .fixedSize()
                }
                if !travelChoices.isEmpty || d.travelMin != 0 {
                    Picker("Leave by", selection: Binding(
                        get: { d.travelMin },
                        set: { model.setEventLeaveBy(d.id, $0, offerUndo: false) }
                    )) {
                        Text("Off").tag(Int32(0))
                        ForEach(Self.withCurrent(travelChoices, d.travelMin), id: \.self) { m in
                            Text(ReminderRules.shared.travelLabel(minutes: m)).tag(m)
                        }
                    }
                    .fixedSize()
                    .help("How long it takes to get there; MEKA says when to leave")
                }
            }
            .controlSize(.small)
            .staggeredAppear(1)
        }
        // Alarms, slice 3: once a travel time is set, Leave by can ring as an alarm (a notification with Snooze /
        // Dismiss here; full screen on the Fold) instead of a heads-up. Unfolds with the expand spring.
        Group {
            if d.travelMin != 0 {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Toggle(LeaveAlarmRules.shared.SWITCH_LABEL, isOn: Binding(
                        get: { d.leaveAlarm },
                        set: { model.setEventLeaveAlarm(d.id, $0) }
                    ))
                    .toggleStyle(.switch)
                    Text(d.leaveAlarm ? "Rings when it's time to go · Snooze or Dismiss" : "A heads-up when it's time to go")
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: d.leaveAlarm)
                }
                .controlSize(.small)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: d.travelMin != 0)

        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.l) {
                if let join = d.join, let url = URL(string: join.url), url.scheme == "https" {
                    Button {
                        MekaHaptics.tick()
                        openURL(url)
                    } label: {
                        Text(join.label).font(MekaType.itemTitle).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .staggeredAppear(1)
                }

                if let place = d.location {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        SectionLabel("Where", palette)
                        Text(place).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
                        if let query = d.mapsQuery, let url = Self.mapsURL(query) {
                            Button("Open in Maps") { openURL(url) }.buttonStyle(.link)
                        }
                    }
                    .staggeredAppear(2)
                }

                if let line = d.calendarLine {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        SectionLabel("Calendar", palette)
                        Text(line).font(MekaType.body).foregroundStyle(d.isFixture ? palette.accent : palette.textPrimary)
                        if let link = d.openIn, let url = URL(string: link.url), url.scheme == "https" {
                            Button(link.label) {
                                MekaHaptics.tick()
                                openURL(url)
                            }
                            .buttonStyle(.link)
                            .help("Open the real event in its own calendar")
                        }
                    }
                    .staggeredAppear(3)
                }

                if let notes = d.notes {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        SectionLabel("Notes", palette)
                        // Verbatim: plain text, never Markdown, so nothing in the notes becomes a link.
                        Text(verbatim: notes).font(MekaType.body).foregroundStyle(palette.textSecondary)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .staggeredAppear(4)
                }

                Text(d.editable
                     ? "Edit and Delete change the event in your calendar too. Prep tasks, reminders and hiding stay in MEKA."
                     : "Change the event itself in your calendar. Prep tasks, reminders and hiding stay in MEKA.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .staggeredAppear(5)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, MekaSpace.s)
        }
        .frame(maxHeight: 420)
    }

    /// The choices, with the one that's set kept at the front when it's no longer offered (so it still shows).
    static func withCurrent(_ choices: [Int32], _ current: Int32) -> [Int32] {
        current != 0 && !choices.contains(current) ? [current] + choices : choices
    }

    /// Apple Maps search for the place.
    static func mapsURL(_ query: String) -> URL? {
        var c = URLComponents(string: "https://maps.apple.com/")
        c?.queryItems = [URLQueryItem(name: "q", value: query)]
        return c?.url
    }
}


/// The clash chooser in the event sheet: "It changed in Google after you edited it", then per thing the edit touched
/// its label over Yours and Google's side by side, and Keep mine · Keep Google's (press style).
struct ClashChooserView: View {
    let clash: EventClashView
    let canKeepMine: Bool
    let keepMine: () -> Void
    let keepTheirs: () -> Void
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text(clash.explain).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            Grid(alignment: .leading, horizontalSpacing: MekaSpace.m, verticalSpacing: MekaSpace.xs) {
                GridRow {
                    Text("")
                    Text(clash.mineLabel.uppercased()).font(MekaType.sectionLabel).foregroundStyle(palette.accent)
                    Text(clash.theirsLabel.uppercased()).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                }
                ForEach(Array(clash.rows.enumerated()), id: \.offset) { _, r in
                    GridRow(alignment: .firstTextBaseline) {
                        Text(r.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        Text(r.mine).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(r.theirs).font(MekaType.body).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            HStack(spacing: MekaSpace.s) {
                if canKeepMine {
                    Button(clash.keepMineLabel, action: keepMine).buttonStyle(MekaPressStyle())
                        .foregroundStyle(palette.accent)
                }
                Button(clash.keepTheirsLabel, action: keepTheirs).buttonStyle(MekaPressStyle())
            }
        }
        .padding(MekaSpace.m)
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).stroke(palette.hairline, lineWidth: 1))
    }
}

/// One "running late" choice as plain Strings ("10 min" and the message), so no Kotlin object is kept by the view.
private struct LateDraftRow: Identifiable {
    let label: String
    let text: String
    var id: String { label }
}

/// "HOW DID IT GO?" (weekend football, slice 4), matching the Fold's MatchResultBlock: with nothing kept the form is
/// open — Us and Them steppers ("–" until one is clicked; none for training), Scorers and a note, Save. Once kept, the
/// line ("Won 3–1 · Leo 2, Sam"), the note and Edit result. The form and the line swap on the expand spring; the
/// digits roll (numeric text) as they step. Reduce Motion: cross-fades.
private struct MatchResultView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let eventID: String
    let scoreAllowed: Bool
    let kept: Bool
    let line: String?
    let note: String?
    let keptFor: Int
    let keptAgainst: Int
    let keptScorers: String
    let keptNote: String
    let palette: MekaPalette
    let onSave: (Int, Int, String, String) -> Void

    @State private var editing: Bool?
    @State private var ours = -1
    @State private var theirs = -1
    @State private var scorers = ""
    @State private var noteText = ""

    private var isEditing: Bool { editing ?? !kept }

    var body: some View {
        let rules = FootballRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rules.RESULT_TITLE.uppercased()).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
            if isEditing {
                VStack(alignment: .leading, spacing: MekaSpace.s) {
                    if scoreAllowed {
                        HStack(spacing: MekaSpace.l) {
                            stepper("Us", value: ours, side: 0)
                            stepper("Them", value: theirs, side: 1)
                        }
                        TextField(rules.SCORERS_HINT, text: $scorers)
                            .textFieldStyle(.roundedBorder)
                            .onChange(of: scorers) { _, v in if v.count > Int(rules.MAX_SCORERS) { scorers = String(v.prefix(Int(rules.MAX_SCORERS))) } }
                    }
                    TextField(rules.NOTE_HINT, text: $noteText, axis: .vertical)
                        .lineLimit(2...5)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: noteText) { _, v in if v.count > Int(rules.MAX_NOTE) { noteText = String(v.prefix(Int(rules.MAX_NOTE))) } }
                    Button(rules.RESULT_SAVE) {
                        if kept || ours >= 0 || !scorers.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !noteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            onSave(ours, theirs, scorers, noteText)
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { editing = false }
                        } else {
                            MekaHaptics.tick()
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .foregroundStyle(palette.accent)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            } else {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if let line { Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity) }
                    if let note {
                        Text(note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button(rules.RESULT_EDIT) {
                        MekaHaptics.tick()
                        ours = keptFor; theirs = keptAgainst; scorers = keptScorers; noteText = keptNote
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { editing = true }
                    }
                    .buttonStyle(MekaPressStyle())
                    .foregroundStyle(palette.accent)
                    .padding(.top, MekaSpace.xxs)
                }
                .transition(.opacity)
            }
        }
        .controlSize(.small)
        .onAppear { ours = keptFor; theirs = keptAgainst; scorers = keptScorers; noteText = keptNote }
        .onChange(of: kept) { _, isKept in if !isKept { editing = nil } }
    }

    private func stepper(_ label: String, value: Int, side: Int) -> some View {
        HStack(spacing: MekaSpace.s) {
            Text(label).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Button("−") { step(side, by: -1) }.buttonStyle(MekaPressStyle())
            Text(value < 0 ? "–" : "\(value)").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .monospacedDigit()
                .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(max(value, 0))))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: value)
            Button("+") { step(side, by: 1) }.buttonStyle(MekaPressStyle())
        }
    }

    private func step(_ side: Int, by: Int) {
        MekaHaptics.tick()
        let top = Int(FootballRules.shared.MAX_SCORE)
        if ours < 0 || theirs < 0 { ours = max(ours, 0); theirs = max(theirs, 0) }
        if side == 0 { ours = min(max(ours + by, 0), top) } else { theirs = min(max(theirs + by, 0), top) }
    }
}

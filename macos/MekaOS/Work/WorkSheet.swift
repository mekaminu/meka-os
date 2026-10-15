@preconcurrency import MekaKit
import SwiftUI

/// Work mode on the Mac (build plan M1): the Work switch and work hours, synced with the Fold. The Fold does the
/// holding (WhatsApp, texts and missed calls during work) and the family and always-notify lists are picked there
/// from contacts; the after-work summary is synced and shows in Needs you (`AfterWorkSheet`).
struct WorkSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    @State private var days: Set<Int> = [1, 2, 3, 4, 5]
    @State private var start = 9 * 60
    @State private var end = 17 * 60 + 30
    @State private var enabled = true
    @State private var showMessageDetails = false
    @State private var openDay: Int?
    @State private var commuteDay: Int64?
    @State private var blockNumber = ""
    @State private var blockRefused = false
    @State private var spamAdvancedOpen = false
    @State private var spamConfirming = false
    @State private var leavingTo = ""

    private static let dayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private static let step = 15

    private var atWork: Bool { model.work?.atWork ?? false }
    private var callAssistant: Bool { model.work?.callAssistant ?? false }
    private var recordingDays: Int { Int(model.work?.recordingDays ?? Int32(VoiceRecordingRules.shared.DEFAULT_KEEP_DAYS)) }
    private static let keepChoices = [7, 30, 0]

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Work mode").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(model.work?.line ?? " ")
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .staggeredAppear(0)
            HStack(spacing: MekaSpace.m) {
                Button(atWork ? "Stop work now" : "Start work now") { model.setWorkSwitch(!atWork) }
                    .buttonStyle(.borderedProminent)
                if model.work?.switchedManually == true {
                    Button("Back to my hours") { model.workBackToSchedule() }
                        .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent)
                        .transition(.opacity)
                }
            }
            .staggeredAppear(1)

            Text("HOURS").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
                .staggeredAppear(2)
            HStack(spacing: MekaSpace.xxs) {
                ForEach(1...7, id: \.self) { d in
                    Toggle(Self.dayNames[d - 1], isOn: Binding(
                        get: { days.contains(d) },
                        set: { on in
                            if on { days.insert(d) } else { days.remove(d) }
                            save()
                        }
                    ))
                    .toggleStyle(.button)
                }
            }
            .staggeredAppear(2)
            stepper("Usual start", value: $start).staggeredAppear(2)
            stepper("Usual end", value: $end).staggeredAppear(2)
            eachDay.staggeredAppear(2)
            Toggle("Use these hours (off: the switch only)", isOn: Binding(get: { enabled }, set: { enabled = $0; save() }))
                .staggeredAppear(2)

            commute.staggeredAppear(3)
            leavingNow.staggeredAppear(3)

            Text("CALL ASSISTANT").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
                .staggeredAppear(3)
            Toggle(isOn: Binding(get: { callAssistant }, set: { model.setCallAssistant($0) })) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("Screen calls at work on the Fold").font(MekaType.itemMeta)
                    Text(CallScreeningRules.shared.statusLine(switchedOn: callAssistant, atWork: atWork, screeningAllowed: nil, paused: model.work?.callAssistantPaused ?? false))
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                }
            }
            .toggleStyle(.switch)
            .staggeredAppear(3)
            Text("Family, the always-notify list and anyone calling twice within 3 minutes ring; other calls are declined and the network's \"forward when busy\" sends them to Navilife's assistant (\(CallScreeningRules.shared.ASSISTANT_NUMBER)), which takes a message. The Fold asks once for permission to screen calls.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(3)
            Text("Test it: ring \(CallScreeningRules.shared.ASSISTANT_NUMBER) from another phone")
                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .staggeredAppear(3)
            keepRecordings.staggeredAppear(3)
            spam.staggeredAppear(3)

            Text("During work your Fold holds WhatsApp, texts and missed calls and shows them after, grouped by person, urgent first. \"Urgent\" or \"emergency\" alerts you straight away. The summary shows here too, in Needs you. Navilife never replies or marks anything read.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(4)

            messages.staggeredAppear(5)

            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: model.work?.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.work?.switchedManually)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.work?.callAssistant)
        .onAppear(perform: load)
    }

    /// The commute counts everywhere, slice 1: the door-to-door time each way, synced with the Fold (default about
    /// 1 h 20 until set). Counted on office days only; the wake alarm uses it. The line cross-fades.
    private var commute: some View {
        let rules = CommuteRules.shared
        let setting = model.work?.commute ?? CommuteSetting.companion.DEFAULT
        let toWork = Int(setting.toWorkMin), home = Int(setting.homeMin)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("COMMUTE").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
            Text(rules.summary(s: setting))
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
            HStack(spacing: MekaSpace.xs) {
                Picker("To work", selection: Binding(get: { toWork }, set: { model.setCommute(toWorkMin: $0, homeMin: home) })) {
                    ForEach(rules.choices(current: Int32(toWork)).map { Int($0.int32Value) }, id: \.self) { m in
                        Text(rules.label(min: Int32(m))).tag(m)
                    }
                }
                .frame(width: 180)
                Picker("Home", selection: Binding(get: { home }, set: { model.setCommute(toWorkMin: toWork, homeMin: $0) })) {
                    ForEach(rules.choices(current: Int32(home)).map { Int($0.int32Value) }, id: \.self) { m in
                        Text(rules.label(min: Int32(m))).tag(m)
                    }
                }
                .frame(width: 160)
            }
            Text(rules.NOTE)
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            oneDay
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: setting.chosen)
    }

    /// The commute, slice 6, "Just one day": pick an office day in the coming week and give it its own times; "Use the
    /// usual" puts it back. Synced with the Fold; the wake alarm, Today and the brief follow. The day's pickers unfold
    /// with the standard appear (reduced motion: instant) and the line cross-fades.
    @ViewBuilder private var oneDay: some View {
        let rules = CommuteRules.shared
        let rows = model.work?.commuteDays ?? []
        if !rows.isEmpty {
            let row = rows.first { $0.epochDay == commuteDay }
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Picker(rules.ONE_DAY_TITLE, selection: $commuteDay) {
                    Text("Choose a day").tag(Int64?.none)
                    ForEach(rows, id: \.epochDay) { r in
                        Text(r.changed ? "\(r.label) •" : r.label).tag(Int64?.some(r.epochDay))
                    }
                }
                .frame(width: 280)
                if let row {
                    let day = row.epochDay
                    let toWork = Int(row.toWorkMin), home = Int(row.homeMin)
                    Text(row.line)
                        .font(MekaType.itemMeta)
                        .foregroundStyle(row.changed ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                    HStack(spacing: MekaSpace.xs) {
                        Picker("To work", selection: Binding(get: { toWork }, set: { model.setCommuteDay(epochDay: day, toWorkMin: $0, homeMin: home) })) {
                            ForEach(rules.choices(current: Int32(toWork)).map { Int($0.int32Value) }, id: \.self) { m in
                                Text(rules.label(min: Int32(m))).tag(m)
                            }
                        }
                        .frame(width: 180)
                        Picker("Home", selection: Binding(get: { home }, set: { model.setCommuteDay(epochDay: day, toWorkMin: toWork, homeMin: $0) })) {
                            ForEach(rules.choices(current: Int32(home)).map { Int($0.int32Value) }, id: \.self) { m in
                                Text(rules.label(min: Int32(m))).tag(m)
                            }
                        }
                        .frame(width: 160)
                    }
                    if row.changed {
                        Button("Use the usual") { model.clearCommuteDay(epochDay: day) }
                    }
                }
                Text(rules.ONE_DAY_NOTE)
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, MekaSpace.xs)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: commuteDay)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: row?.line)
        }
    }

    /// "Leaving now" (V1): on or off and who the draft goes to (the journey home is the commute home), synced with the Fold (where the
    /// Family list lives, so the name is typed here). The line cross-fades; the choices fold away when off.
    private var leavingNow: some View {
        let rules = LeavingRules.shared
        let view = model.leaving
        let on = view?.on ?? true
        let to = view?.to ?? ""
        let journey = Int(view?.journeyMin ?? Int32(rules.DEFAULT_JOURNEY))
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("LEAVING NOW").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
            Toggle(isOn: Binding(get: { on }, set: { model.setLeavingNow(on: $0, to: to, journeyMin: journey) })) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("Draft \"Leaving now\" when work ends").font(MekaType.itemMeta)
                    Text(view?.summary ?? rules.summary(s: LeavingSetting.companion.DEFAULT))
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                }
            }
            .toggleStyle(.switch)
            if on {
                HStack(spacing: MekaSpace.xs) {
                    TextField("To", text: $leavingTo)
                        .textFieldStyle(.roundedBorder).frame(width: 160)
                        .onSubmit { model.setLeavingNow(on: true, to: leavingTo, journeyMin: journey) }
                    // The commute counts everywhere, slice 4: the journey home is the commute home (Commute above).
                    Text(rules.journeyNote(journeyMin: Int32(journey)))
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                }
                .transition(.opacity)
            }
            Text("On the Fold the draft comes with Send and Edit; here, Copy. Nothing is sent until you tap.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .onAppear { leavingTo = to }
        .onChange(of: to) { _, new in leavingTo = new }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
    }

    /// "Keep callers' recordings" (call assistant polish 8c): 7 days · 30 days · Don't keep, synced with the Fold. A
    /// segmented control (tick haptic); the line under it cross-fades.
    private var keepRecordings: some View {
        let rules = VoiceRecordingRules.shared
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(rules.SETTING_TITLE).font(MekaType.itemMeta)
            Picker(rules.SETTING_TITLE, selection: Binding(get: { recordingDays }, set: { model.setRecordingDays($0) })) {
                ForEach(Self.keepChoices, id: \.self) { d in Text(rules.choiceLabel(days: Int32(d))).tag(d) }
            }
            .pickerStyle(.segmented).labelsHidden()
            Text(rules.settingLine(days: Int32(recordingDays)))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
        }
        .padding(.top, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: recordingDays)
    }

    /// Spam protection (call assistant polish 8b): the synced block list and Suspected spam (8b c, Block · Not spam);
    /// the Fold does the blocking. Rows fade and
    /// slide in and out on the expand spring; the summary line cross-fades.
    private var spam: some View {
        let view = model.blockedCallers
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("SPAM PROTECTION").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            Text(view?.line ?? BlockedCallerRules.shared.EMPTY_LINE)
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
            Text(SpamProtectionRules.shared.HINT)
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            Text(SpamProtectionRules.shared.SYSTEM_LIST_LINE)
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            // Suspected spam (8b c): numbers MEKA's AI flagged from their message, waiting for Block or Not spam.
            if !(view?.suspects ?? []).isEmpty {
                Text(SuspectedSpamRules.shared.TITLE).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .padding(.top, MekaSpace.xxs)
                    .transition(.opacity)
            }
            ForEach(view?.suspects ?? [], id: \.key) { row in
                HStack {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(row.number).font(MekaType.itemMeta)
                        Text(row.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    Spacer()
                    Button(SuspectedSpamRules.shared.NOT_SPAM_LABEL) { model.dismissSuspectedCaller(row.key) }
                        .buttonStyle(MekaPressStyle()).foregroundStyle(palette.textSecondary)
                    Button(BlockedCallerRules.shared.BLOCK_LABEL) { model.confirmSuspectedCaller(row.key) }
                        .buttonStyle(MekaPressStyle()).foregroundStyle(palette.critical)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            if !(view?.suspects ?? []).isEmpty {
                Text(SuspectedSpamRules.shared.HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            ForEach(view?.rows ?? [], id: \.key) { row in
                HStack {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(row.number).font(MekaType.itemMeta)
                        Text(row.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    Spacer()
                    Button("Unblock") { model.unblockCaller(row.key) }
                        .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            Text(CallScreeningRules.shared.ONE_SCREENER_TITLE).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .padding(.top, MekaSpace.xxs)
            ForEach(CallScreeningRules.shared.ONE_SCREENER_LINES, id: \.self) { line in
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                TextField("Number to block", text: $blockNumber)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(block)
                    .onChange(of: blockNumber) { blockRefused = false }
                Button("Block", action: block)
                    .disabled(blockNumber.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if blockRefused {
                Text("That isn't a phone number").font(MekaType.caption).foregroundStyle(palette.critical)
                    .transition(.opacity)
            }
            spamAdvanced
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: view?.rows.map(\.key) ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: view?.suspects.map(\.key) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: blockRefused)
    }

    /// Spam calls still ring, slice 1: Advanced holds the only switch for spam protection, behind a question.
    /// Unfolds on the expand spring; the line cross-fades between on and the red warning.
    private var spamAdvanced: some View {
        let on = model.work?.spamProtection ?? true
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(SpamProtectionRules.shared.ADVANCED + (spamAdvancedOpen ? " ▴" : " ▾")) {
                MekaHaptics.tick(); spamAdvancedOpen.toggle(); spamConfirming = false
            }
            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            if spamAdvancedOpen || !on {
                Text(SpamProtectionRules.shared.SWITCH_LABEL + " · " + (on ? SpamProtectionRules.shared.ON_LINE : SpamProtectionRules.shared.OFF_WARNING))
                    .font(MekaType.caption).foregroundStyle(on ? palette.textSecondary : palette.critical)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                if !on {
                    Button("Turn spam protection on") { model.setSpamProtection(true) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                } else if spamConfirming {
                    Text(SpamProtectionRules.shared.OFF_CONFIRM)
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: MekaSpace.m) {
                        Button("Keep it on") { spamConfirming = false }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        Button("Turn off") { spamConfirming = false; model.setSpamProtection(false) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.critical)
                    }
                } else {
                    Button("Turn spam protection off…") { spamConfirming = true }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
        }
        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: spamAdvancedOpen)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: spamConfirming)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
    }

    private func block() {
        let number = blockNumber
        Task {
            if await model.blockCaller(number) { blockNumber = "" } else { blockRefused = true }
        }
    }

    /// Work mode → Messages (V1, messages assistant slice 5): the Fold reads the messages and keeps the never-to-AI
    /// list, so the Mac shows what the assistant sends, what it costs and what it can't see.
    private var messages: some View {
        let rules = MessagesSetupRules.shared
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("MESSAGES").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
            Text(rules.statusLine(listening: nil, aiOn: nil))
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            DisclosureGroup(isExpanded: $showMessageDetails) {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(rules.PRIVACY + [rules.COST] + rules.LIMITS, id: \.self) { line in
                        Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, MekaSpace.xxs)
            } label: {
                Text("Privacy, cost and limits").font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
        }
        .padding(.top, MekaSpace.m)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showMessageDetails)
    }

    /// Hours → each day (Places item 1): one row per work day, lit when it has its own hours; clicking a row unfolds
    /// its Start/End steppers in place (expand spring, the chevron turns); "Same as usual" clears them.
    @ViewBuilder private var eachDay: some View {
        if let s = model.work?.schedule, s.enabled, !s.days.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("Each day").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                ForEach(s.weekRows, id: \.isoDay) { row in
                    dayRow(row)
                }
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: openDay)
        }
    }

    private func dayRow(_ row: WorkDayRow) -> some View {
        let day = Int(row.isoDay)
        let start = Int(row.startMinute), end = Int(row.endMinute)
        let open = openDay == day
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button {
                MekaHaptics.tick()
                openDay = open ? nil : day
            } label: {
                HStack {
                    Text(row.dayShort).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).frame(width: 44, alignment: .leading)
                    Text(row.line).font(MekaType.itemMeta)
                        .foregroundStyle(row.own ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                    Spacer()
                    Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.accent)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("\(row.name): \(row.line)")
            if open {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    dayStepper("\(row.dayShort) start", value: start) { model.setWorkDayHours(isoDay: day, startMinute: $0, endMinute: end) }
                    dayStepper("\(row.dayShort) end", value: end) { model.setWorkDayHours(isoDay: day, startMinute: start, endMinute: $0) }
                    if row.own {
                        Button("Same as usual") { model.clearWorkDayHours(isoDay: day) }
                            .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent).font(MekaType.caption)
                    }
                }
                .padding(.leading, MekaSpace.m)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
    }

    private func dayStepper(_ label: String, value: Int, onChange: @escaping (Int) -> Void) -> some View {
        Stepper(
            value: Binding(get: { value }, set: { onChange($0) }),
            in: 0...(24 * 60 - Self.step), step: Self.step
        ) {
            HStack {
                Text(label).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(Self.hhmm(value)).monospacedDigit().foregroundStyle(palette.textPrimary)
            }
            .font(MekaType.itemMeta)
        }
    }

    private func stepper(_ label: String, value: Binding<Int>) -> some View {
        Stepper(
            value: Binding(get: { value.wrappedValue }, set: { value.wrappedValue = $0; save() }),
            in: 0...(24 * 60 - Self.step), step: Self.step
        ) {
            HStack {
                Text(label).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(Self.hhmm(value.wrappedValue)).monospacedDigit().foregroundStyle(palette.textPrimary)
            }
            .font(MekaType.itemMeta)
        }
    }

    private func load() {
        guard let s = model.work?.schedule else { return }
        days = Set(s.days.map { $0.intValue })
        start = Int(s.startMinute)
        end = Int(s.endMinute)
        enabled = s.enabled
    }

    private func save() {
        model.setWorkSchedule(days: days, startMinute: start, endMinute: end, enabled: enabled)
    }

    static func hhmm(_ minute: Int) -> String { String(format: "%02d:%02d", minute / 60, minute % 60) }
}

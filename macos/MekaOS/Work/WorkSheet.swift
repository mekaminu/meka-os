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
    @State private var blockNumber = ""
    @State private var blockRefused = false

    private static let dayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private static let step = 15

    private var atWork: Bool { model.work?.atWork ?? false }
    private var callAssistant: Bool { model.work?.callAssistant ?? false }

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
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.s)
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

            Text("CALL ASSISTANT").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.s)
                .staggeredAppear(3)
            Toggle(isOn: Binding(get: { callAssistant }, set: { model.setCallAssistant($0) })) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("Screen calls at work on the Fold").font(MekaType.itemMeta)
                    Text(CallScreeningRules.shared.statusLine(switchedOn: callAssistant, atWork: atWork, screeningAllowed: nil))
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                }
            }
            .toggleStyle(.switch)
            .staggeredAppear(3)
            Text("Family, the always-notify list and anyone calling twice within 3 minutes ring; other calls are declined and the network's \"forward when busy\" sends them to MEKA's assistant (\(CallScreeningRules.shared.ASSISTANT_NUMBER)), which takes a message. The Fold asks once for permission to screen calls.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(3)
            Text("Test it: ring \(CallScreeningRules.shared.ASSISTANT_NUMBER) from another phone")
                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .staggeredAppear(3)
            spam.staggeredAppear(3)

            Text("During work your Fold holds WhatsApp, texts and missed calls and shows them after, grouped by person, urgent first. \"Urgent\" or \"emergency\" alerts you straight away. The summary shows here too, in Needs you. MEKA never replies or marks anything read.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.s)
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

    /// Spam protection (call assistant polish 8b): the synced block list; the Fold does the blocking. Rows fade and
    /// slide in and out on the expand spring; the summary line cross-fades.
    private var spam: some View {
        let view = model.blockedCallers
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("SPAM PROTECTION").font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.xs)
            Text(view?.line ?? BlockedCallerRules.shared.EMPTY_LINE)
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
            Text("Blocked numbers never ring on the Fold, any time of day. With the call assistant on, numbers the network can't verify and withheld numbers in quiet hours go to the assistant. Family, always-notify, contacts and anyone you called in the last 90 days always get through.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(view?.rows ?? [], id: \.key) { row in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
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
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: view?.rows.map(\.key) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: blockRefused)
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
        .padding(.top, MekaSpace.s)
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
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(palette.accent)
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

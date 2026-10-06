@preconcurrency import MekaKit
import SwiftUI

/// Work mode on the Mac (build plan M1): the Work switch and work hours, synced with the Fold. The Fold does the
/// holding: WhatsApp, texts and missed calls during work, and the after-work summary, stay on the phone, and the
/// family and always-notify lists are picked there from contacts.
struct WorkSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette

    @State private var days: Set<Int> = [1, 2, 3, 4, 5]
    @State private var start = 9 * 60
    @State private var end = 17 * 60 + 30
    @State private var enabled = true

    private static let dayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private static let step = 15

    private var atWork: Bool { model.work?.atWork ?? false }

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
                        .buttonStyle(.plain).foregroundStyle(palette.accent)
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
            stepper("Start", value: $start).staggeredAppear(2)
            stepper("End", value: $end).staggeredAppear(2)
            Toggle("Use these hours (off: the switch only)", isOn: Binding(get: { enabled }, set: { enabled = $0; save() }))
                .staggeredAppear(2)

            Text("During work your Fold holds WhatsApp, texts and missed calls and shows them after, grouped by person, urgent first. \"Urgent\" or \"emergency\" alerts you straight away. MEKA never replies or marks anything read, and what it holds stays on the phone.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.s)
                .staggeredAppear(3)

            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: model.work?.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.work?.switchedManually)
        .onAppear(perform: load)
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

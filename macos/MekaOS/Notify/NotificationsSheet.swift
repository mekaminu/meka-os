@preconcurrency import MekaKit
import SwiftUI

/// Notification settings on the Mac (governor v1): what this Mac posts, quiet hours, the two digests and which tier
/// each kind of notice uses. Quiet hours, digests and tiers sync with the Fold; "This Mac" is this Mac's own.
/// Motion: sections stagger in; the quiet-hour times unfold when turned on; the preview lines cross-fade.
struct NotificationsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    private static let step = 15
    private static let devices: [DeviceAlerts] =
        (0..<Int(NotifyRules.shared.deviceCount)).map { NotifyRules.shared.deviceAt(index: Int32($0)) }
    private static let sources: [NoticeSource] =
        (0..<Int(NotifyRules.shared.sourceCount)).map { NotifyRules.shared.sourceAt(index: Int32($0)) }
    private struct DigestTime: Identifiable { let minute: Int32; let name: String; var id: Int32 { minute } }
    private static let digests = [
        DigestTime(minute: NotifyRules.shared.MIDDAY, name: "Midday · 12:30"),
        DigestTime(minute: NotifyRules.shared.EVENING, name: "Evening · 18:00"),
    ]

    private var quietOn: Bool { model.notifySettings?.quiet.enabled ?? true }
    private var quietStart: Int { Int(model.notifySettings?.quiet.startMinute ?? 22 * 60) }
    private var quietEnd: Int { Int(model.notifySettings?.quiet.endMinute ?? 7 * 60) }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Notifications").font(MekaType.upNextTitle).staggeredAppear(0)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(model.notifyPreview.map { LoneWordGlue.shared.kept(line: $0.quietLine) } ?? " ").contentTransition(.opacity)
                Text(model.notifyPreview.map { LoneWordGlue.shared.kept(line: $0.digestLine) } ?? " ").contentTransition(.opacity)
            }
            .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            .staggeredAppear(0)
            // Notifications never seen, slice 3: why silence between digests isn't broken.
            if let explain = model.notifyPreview?.explainLine, !explain.isEmpty {
                Text(LoneWordGlue.shared.kept(line: explain)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .staggeredAppear(0)
            }

            // Notifications never seen, the Mac's half: say plainly when nothing can reach this Mac.
            if let off = MacNotifyRules.shared.offLine(allowed: model.notificationsAllowed, macOff: model.macAlerts == .off) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(off).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    if !model.notificationsAllowed {
                        Button("Turn on") { model.turnOnNotifications() }
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }

            section("THIS MAC", 1)
            Picker("This Mac", selection: Binding(get: { model.macAlerts }, set: { model.setMacAlerts($0) })) {
                ForEach(Self.devices, id: \.self) { d in Text(NotifyRules.shared.deviceLabel(d: d)).tag(d) }
            }
            .pickerStyle(.segmented).labelsHidden()
            .staggeredAppear(1)
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Button("Send a test") { model.sendTestNotification() }
                    .disabled(model.notifyTest.map { !$0.done } ?? false)
                if let test = model.notifyTest {
                    Text(LoneWordGlue.shared.kept(line: test.localLine)).font(MekaType.itemMeta)
                        .foregroundStyle(test.localOk?.boolValue == false ? palette.critical : palette.textSecondary)
                        .contentTransition(.opacity)
                }
            }
            .staggeredAppear(1)
            if let hint = model.notifyTest?.hint {
                Text(LoneWordGlue.shared.kept(line: hint)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }

            section("QUIET HOURS", 2)
            Toggle("Quiet hours", isOn: Binding(get: { quietOn }, set: { model.setQuietHours(enabled: $0, start: quietStart, end: quietEnd) }))
                .staggeredAppear(2)
            if quietOn {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    stepper("From", value: quietStart) { model.setQuietHours(enabled: true, start: $0, end: quietEnd) }
                    stepper("Until", value: quietEnd) { model.setQuietHours(enabled: true, start: quietStart, end: $0) }
                    Text(LoneWordGlue.shared.kept(line: NotifyRules.shared.QUIET_NOTE))
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }

            section("DIGESTS", 3)
            ForEach(Self.digests) { d in
                Toggle(d.name, isOn: Binding(
                    get: { model.notifySettings.map { NotifyRules.shared.hasDigest(settings: $0, minute: d.minute) } ?? true },
                    set: { model.setDigest(d.minute, on: $0) }
                ))
            }
            .staggeredAppear(3)
            Text(LoneWordGlue.shared.kept(line: NotifyRules.shared.DIGEST_NOTE))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)

            section("WHAT GOES WHERE", 4)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(Self.sources, id: \.self) { source in
                    HStack {
                        Text(NotifyRules.shared.sourceLabel(s: source)).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Picker(NotifyRules.shared.sourceLabel(s: source), selection: Binding(
                            get: { model.notifySettings.map { NotifyRules.shared.tierOf(settings: $0, s: source) } ?? NotifyRules.shared.tierChoices(s: source)[0] },
                            set: { model.setNoticeTier(source, $0) }
                        )) {
                            ForEach(NotifyRules.shared.tierChoices(s: source), id: \.self) { t in
                                Text(NotifyRules.shared.tierLabel(t: t)).tag(t)
                            }
                        }
                        .labelsHidden().fixedSize()
                    }
                    .font(MekaType.itemMeta)
                }
            }
            .staggeredAppear(4)

            Text(LoneWordGlue.shared.kept(line: NotifyRules.shared.TIER_NOTE))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: quietOn)
        .animation(MekaMotion.replan(reduced: reduceMotion), value: model.notifyPreview?.digestLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.notifyTest?.localLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.notificationsAllowed)
        .task { await model.refreshNotificationsAllowed() }
    }

    private func section(_ title: String, _ index: Int) -> some View {
        Text(title).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
            .staggeredAppear(index)
    }

    private func stepper(_ label: String, value: Int, save: @escaping (Int) -> Void) -> some View {
        Stepper(
            value: Binding(get: { value }, set: { save(($0 + 24 * 60) % (24 * 60)) }),
            in: -Self.step...(24 * 60), step: Self.step
        ) {
            HStack {
                Text(label).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(String(format: "%02d:%02d", value / 60, value % 60)).monospacedDigit().foregroundStyle(palette.textPrimary)
            }
            .font(MekaType.itemMeta)
        }
    }
}

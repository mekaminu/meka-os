@preconcurrency import MekaKit
import SwiftUI

/// Notification settings on the Mac (governor v1): what this Mac posts, quiet hours, the two digests and which tier
/// each kind of notice uses. Quiet hours, digests and tiers sync with the Fold; "This Mac" is this Mac's own.
/// Motion: sections stagger in; the quiet-hour times unfold when turned on; the preview lines cross-fade.
struct NotificationsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
                Text(model.notifyPreview?.quietLine ?? " ").contentTransition(.opacity)
                Text(model.notifyPreview?.digestLine ?? " ").contentTransition(.opacity)
            }
            .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            .staggeredAppear(0)

            section("THIS MAC", 1)
            Picker("This Mac", selection: Binding(get: { model.macAlerts }, set: { model.setMacAlerts($0) })) {
                ForEach(Self.devices, id: \.self) { d in Text(NotifyRules.shared.deviceLabel(d: d)).tag(d) }
            }
            .pickerStyle(.segmented).labelsHidden()
            .staggeredAppear(1)

            section("QUIET HOURS", 2)
            Toggle("Quiet hours", isOn: Binding(get: { quietOn }, set: { model.setQuietHours(enabled: $0, start: quietStart, end: quietEnd) }))
                .staggeredAppear(2)
            if quietOn {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    stepper("From", value: quietStart) { model.setQuietHours(enabled: true, start: $0, end: quietEnd) }
                    stepper("Until", value: quietEnd) { model.setQuietHours(enabled: true, start: quietStart, end: $0) }
                    Text("Heads-ups that fall in quiet hours wait for the next digest. Only critical things come through.")
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

            Text("Heads-up: a notification at the time. Digest: in the midday or evening round-up. App only: never a notification. Nothing here sends anything for you.")
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
    }

    private func section(_ title: String, _ index: Int) -> some View {
        Text(title).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.s)
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

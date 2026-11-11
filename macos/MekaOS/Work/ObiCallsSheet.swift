@preconcurrency import MekaKit
import SwiftUI

/// Work mode → Obi on calls on the Mac (Obi on calls, slice 4; mockup `docs/design/calls/settings.dc.html`), like the
/// Fold's ObiCallsPane: THIS MONTH (spent, calls, booked, a bar against the cap and the £10 · £20 · £30 · £50 chips),
/// WHEN SOMEONE CALLS YOU's three switches, WHAT CALLERS MAY KNOW's chips, the fixed BOOKING CALLS limits, RECORDINGS
/// and "Obi always says he's an AI assistant when asked". The words are the core's `CallsSettingsRules.view`; everything is
/// synced with the Fold. Only Bools, Int32s and Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; the bar grows from the left to its share on the expand
/// spring; chips and switches blend with a tick haptic; lines cross-fade. Reduce Motion: cross-fades, the bar at once.
struct ObiCallsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var view: CallsSettingsView?
    @State private var grown = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(CallsSettingsRules.shared.TITLE).font(MekaType.greeting).tracking(MekaType.greetingTracking).staggeredAppear(0)
            Text(CallsSettingsRules.shared.SUB).font(MekaType.caption).foregroundStyle(palette.textSecondary).staggeredAppear(0)
            ScrollView {
                if let v = view {
                    content(v).transition(.opacity)
                }
            }
            .scrollIndicators(.never)
            .frame(maxHeight: 600)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
        .task(id: model.work?.description ?? "") {
            if let next = await model.callsSettings() { view = next }
            if !grown {
                if reduceMotion { grown = true } else { withAnimation(MekaMotion.expand(reduced: false)) { grown = true } }
            }
        }
    }

    @ViewBuilder
    private func content(_ v: CallsSettingsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            month(v).staggeredAppear(1)

            label(CallsSettingsRules.shared.WHEN_CALLS).staggeredAppear(2)
            // The design check (2026-11-07): each switch is its own card, as on the mockup.
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(Array(v.toggles.enumerated()), id: \.offset) { _, t in card { toggle(t) } }
            }
            .staggeredAppear(2)

            label(CallsSettingsRules.shared.KNOWS_TITLE).staggeredAppear(3)
            card {
                chips(v.knows) { c in model.toggleCallKnows(c.key) }
                caption(CallsSettingsRules.shared.NEVER)
            }
            .staggeredAppear(3)

            label(CallsSettingsRules.shared.LIMITS_TITLE).staggeredAppear(4)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: MekaSpace.xs), GridItem(.flexible(), spacing: MekaSpace.xs)], spacing: MekaSpace.xs) {
                ForEach(Array(v.limits.enumerated()), id: \.offset) { _, l in tile(l.label, l.value, raised: true) }
            }
            .staggeredAppear(4)

            label(CallsSettingsRules.shared.RECORDINGS_TITLE).staggeredAppear(5)
            card {
                chips(v.keeps) { c in if !c.on, let d = Int(c.key) { model.setRecordingDays(d) } }
                caption(CallsSettingsRules.shared.RECORDINGS_NOTE)
            }
            .staggeredAppear(5)

            Text(CallsSettingsRules.shared.FOOTER).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, MekaSpace.l)
                .staggeredAppear(6)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.toggles.map(\.on))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.knows.map(\.on))
    }

    private func month(_ v: CallsSettingsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Text(CallsSettingsRules.shared.THIS_MONTH.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                    .foregroundStyle(palette.textTertiary)
                Spacer()
                Text(v.capLine).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(v.tiles.enumerated()), id: \.offset) { _, t in tile(t.label, t.value) }
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.surfaceRaised)
                    Capsule().fill(v.atCap ? palette.critical : palette.accent)
                        .frame(width: g.size.width * CGFloat(grown ? v.fraction : 0))
                }
            }
            .frame(height: 8)
            .accessibilityLabel("\(v.tiles.first?.value ?? "") of £\(v.capGbp)")
            chips(v.caps, fill: true) { c in if !c.on, let g = Int(c.key) { model.setCallLiveCap(g) } }
            caption(CallsSettingsRules.shared.CAP_NOTE)
        }
        .padding(MekaSpace.m)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.fraction)
    }

    private func toggle(_ t: CallsToggle) -> some View {
        Toggle(isOn: Binding(get: { t.on }, set: { on in
            switch t.kind {
            case .live: model.setCallLive(on)
            case .slots: model.setCallSlots(on)
            default: model.setCallBooking(on)
            }
        })) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(t.title).font(MekaType.body)
                Text(LoneWordGlue.shared.kept(line: t.sub)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                if let line = t.line {
                    Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity)
                }
            }
        }
        .toggleStyle(.switch)
    }

    private func chips(_ list: [CallsChip], fill: Bool = false, onTap: @escaping (CallsChip) -> Void) -> some View {
        // A wrapping row would need a custom layout; five short chips fit two rows of a 460-wide sheet.
        let rows = fill ? [list] : stride(from: 0, to: list.count, by: 3).map { Array(list[$0..<min($0 + 3, list.count)]) }
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: MekaSpace.xs) {
                    ForEach(row, id: \.key) { c in
                        Button { onTap(c) } label: {
                            Text(c.label).font(MekaType.caption)
                                .foregroundStyle(c.on ? palette.onAccent : palette.textSecondary)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .frame(maxWidth: fill ? .infinity : nil)
                                .background(c.on ? palette.accent : palette.surface, in: Capsule())
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityValue(c.on ? "On" : "Off")
                    }
                }
            }
        }
    }

    private func tile(_ label: String, _ value: String, raised: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(label.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).lineLimit(1)
            Text(value).font(MekaType.upNextCardTitle).tracking(MekaType.upNextCardTitleTracking)
                .foregroundStyle(palette.textPrimary).contentTransition(.opacity)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(raised ? palette.surfaceRaised : palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
    }

    /// A section's card, as on the mockup: raised on the sheet, 16 inside, its lines 8 apart.
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) { content() }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
    }

    private func caption(_ text: String) -> some View {
        Text(LoneWordGlue.shared.kept(line: text)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

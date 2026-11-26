@preconcurrency import MekaKit
import SwiftUI

/// Work mode → Obi on calls on the Mac (Obi on calls, slice 4; mockup `docs/design/calls/settings.dc.html`), like the
/// Fold's ObiCallsPane: THIS MONTH (spent, calls, booked, a bar against the cap), WHEN SOMEONE CALLS YOU's three
/// switches, WHAT CALLERS MAY KNOW, the fixed BOOKING CALLS limits, RECORDINGS and "Obi always says he's an AI assistant
/// when asked". Walk-through week 5, A17 (no open chip rows): the monthly cap and keep recordings are each one card row
/// with a menu built from the core's choice sheet (`CallsChoiceRules`); what callers may know is a switch per thing. The
/// words are the core's; everything is synced with the Fold. Only Bools, Int32s and Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; the bar grows from the left to its share on the expand
/// spring; switches slide; a menu's value cross-fades as it changes; lines cross-fade. Reduce Motion: cross-fades, the bar at once.
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
            choice(CallsChoiceRules.shared.capRow(v: v), CallsChoiceRules.shared.capSheet(v: v)) { name in
                if let g = CallsChoiceRules.shared.capNamed(name: name)?.int32Value, g != v.capGbp { model.setCallLiveCap(Int(g)) }
            }
            .staggeredAppear(1)

            label(CallsSettingsRules.shared.WHEN_CALLS).staggeredAppear(2)
            // The design check (2026-11-07): each switch is its own card, as on the mockup.
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(Array(v.toggles.enumerated()), id: \.offset) { _, t in card { toggle(t) } }
            }
            .staggeredAppear(2)

            label(CallsSettingsRules.shared.KNOWS_TITLE).staggeredAppear(3)
            card {
                ForEach(v.knows, id: \.key) { c in knows(c) }
                caption(CallsSettingsRules.shared.NEVER)
            }
            .staggeredAppear(3)

            label(CallsSettingsRules.shared.LIMITS_TITLE).staggeredAppear(4)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: MekaSpace.xs), GridItem(.flexible(), spacing: MekaSpace.xs)], spacing: MekaSpace.xs) {
                ForEach(Array(v.limits.enumerated()), id: \.offset) { _, l in tile(l.label, l.value, raised: true) }
            }
            .staggeredAppear(4)

            label(CallsSettingsRules.shared.RECORDINGS_TITLE).staggeredAppear(5)
            choice(CallsChoiceRules.shared.keepRow(v: v), CallsChoiceRules.shared.keepSheet(v: v)) { name in
                let now = v.keeps.first(where: { $0.on })?.key
                if let d = CallsChoiceRules.shared.keepNamed(name: name)?.int32Value, String(d) != now { model.setRecordingDays(Int(d)) }
            }
            .staggeredAppear(5)
            caption(CallsSettingsRules.shared.RECORDINGS_NOTE).padding(.horizontal, MekaSpace.m).staggeredAppear(5)

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
            caption(CallsSettingsRules.shared.CAP_NOTE)
        }
        .padding(MekaSpace.m)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .lightCardEdge(palette)
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

    /// One thing callers may know: its name and what Obi may say, with a switch.
    private func knows(_ c: CallsChip) -> some View {
        Toggle(isOn: Binding(get: { c.on }, set: { on in if on != c.on { model.toggleCallKnows(c.key) } })) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(c.label).font(MekaType.body)
                Text(LoneWordGlue.shared.kept(line: CallsChoiceRules.shared.knowsLine(key: c.key))).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(.switch)
        .accessibilityLabel(CallsChoiceRules.shared.knowsSpoken(c: c))
    }

    /// A choice as one card row ("Monthly cap" · a menu showing "£20"), the menu built from the core's sheet. A pick sends
    /// only the choice's name (a String) back.
    private func choice(_ row: NotifyChoiceRow, _ sheet: NotifyChoiceSheet, onPick: @escaping (String) -> Void) -> some View {
        let lit = sheet.choices.first(where: { $0.lit })?.name ?? ""
        return HStack(spacing: MekaSpace.xs) {
            Text(LoneWordGlue.shared.kept(line: row.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
            Spacer(minLength: MekaSpace.xs)
            Picker(row.title, selection: Binding(get: { lit }, set: { name in if name != lit { onPick(name) } })) {
                ForEach(sheet.choices, id: \.name) { c in Text(c.label).tag(c.name) }
            }
            .labelsHidden().pickerStyle(.menu).fixedSize()
            .help(LoneWordGlue.shared.kept(line: sheet.note))
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .lightCardEdge(palette)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(row.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.value)
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
            .lightCardEdge(palette)
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

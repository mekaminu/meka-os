@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Week ahead on the Mac (Sunday week ahead, slice 1), like the Fold's WeekAheadPane: the coming week day
/// by day — what kind of day (office or from home with the hours, annual leave, a bank holiday), the weather while the
/// forecast reaches it, then events, fixtures, school, date night, appointments and renewals in time order — and
/// FAMILY SYNC, what needs both Meka and Jeanette. Read-only; Today's "Your week ahead · …" line opens it on Sunday
/// evening.
///
/// Slice 2: **▶ Listen** reads the week aloud in MEKA's voice (else the Mac's own); Listen on Sunday's heads-up opens
/// the sheet already reading (`readWeekAheadOnOpen`). **Sunday heads-up**: on or off and its time (default 18:00),
/// synced with the Fold; only a Bool and an Int32 cross to the core.
///
/// Goals, properly, item 6: **Goals for the week** — click up to three open goals to make them the week's goals (Today's
/// "This week: …" line and the face's goal ring); with three picked the rest dim until one is unpicked.
///
/// Motion: the sheet scale-fades (system); the summary cross-fades as it changes; the days and the family sync stagger
/// in 40 ms apart; Listen's label cross-fades to "■ Stop"; the time picker fades away when the heads-up is off. A goal's
/// tick ring draws its check as it's picked; the line above cross-fades; the rest dim (opacity) once three are picked.
/// Reduce Motion: cross-fades.
struct WeekAheadSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var speaker = MekaSpeaker()

    var body: some View {
        let v = model.weekAhead ?? WeekAheadView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Week ahead").font(MekaType.upNextTitle).staggeredAppear(0)
            Text("\(v.title) · \(v.rangeLabel)").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .staggeredAppear(0)
            if !v.spoken.isEmpty {
                Button { toggleListen() } label: {
                    Text(speaker.speaking ? "■  Stop" : "▶  Listen")
                        .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .contentTransition(.opacity)
                }
                .buttonStyle(.borderless)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: speaker.speaking)
                .help(speaker.speaking ? "Stop reading" : "Read the week aloud")
                .accessibilityLabel(speaker.speaking ? "Stop reading the week" : "Read the week aloud")
                .staggeredAppear(0)
            }
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    ForEach(Array(v.days.enumerated()), id: \.element.epochDay) { i, d in
                        WeekAheadDayView(day: d, palette: palette).staggeredAppear(2 + i)
                    }
                    if !v.sync.isEmpty {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            label("Family sync")
                            Text("To sort out with Jeanette").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            ForEach(v.sync, id: \.self) { line in
                                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                                    Circle().fill(palette.accent).frame(width: 6, height: 6)
                                    Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                        .staggeredAppear(2 + v.days.count)
                    }
                    if !v.goals.goals.isEmpty {
                        goalsPick(v.goals).staggeredAppear(3 + v.days.count)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 480)
            headsUp
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .onAppear { speaker.attach(model); readIfAsked() }
        .onChange(of: model.weekAhead?.spoken) { readIfAsked() }
        .onChange(of: model.readWeekAheadOnOpen) { readIfAsked() }
        .onDisappear {
            speaker.stop()
            model.readWeekAheadOnOpen = false
        }
    }

    /// Listen on Sunday's heads-up: reads once the week is in, once.
    private func readIfAsked() {
        guard model.readWeekAheadOnOpen, let v = model.weekAhead, !v.spoken.isEmpty else { return }
        model.readWeekAheadOnOpen = false
        if !speaker.speaking { speaker.say(v.spoken, reading: true) }
    }

    /// Listen reads the week aloud (light haptic); Stop ends it (tick haptic).
    private func toggleListen() {
        if speaker.speaking {
            MekaHaptics.tick()
            speaker.stop()
        } else if let v = model.weekAhead, !v.spoken.isEmpty {
            MekaHaptics.light()
            speaker.say(v.spoken, reading: true)
        }
    }

    /// Goals for the week: click a goal to pick it (up to three); the rest dim once three are picked.
    private func goalsPick(_ g: WeekAheadGoals) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            label("Goals for the week")
            Text(g.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: g.line)
            ForEach(g.goals, id: \.goalId) { goal in
                let enabled = goal.picked || !g.full
                let id = goal.goalId
                let next = !goal.picked
                Button { model.pickWeekGoal(id, on: next) } label: {
                    HStack(spacing: MekaSpace.m) {
                        TickRingView(done: goal.picked, palette: palette).frame(width: 18, height: 18)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(goal.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Text(goal.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.45)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: enabled)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(goal.spoken)
                .accessibilityAddTraits(goal.picked ? .isSelected : [])
                .help(goal.picked ? "Unpick" : "Pick for the week")
            }
        }
    }

    /// Sunday heads-up: a switch and the time, synced with the Fold. The line under it cross-fades.
    private var headsUp: some View {
        let s = model.weekAheadSetting ?? WeekAheadSettingView.companion.EMPTY
        let on = s.on
        let minute = Int(s.minute)
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            label("Sunday heads-up")
            HStack(spacing: MekaSpace.m) {
                Toggle(isOn: Binding(get: { on }, set: { model.setWeekAhead(on: $0, minute: minute) })) {
                    Text(s.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: s.summary)
                }
                .toggleStyle(.switch)
                if on {
                    Picker("Time", selection: Binding(get: { minute }, set: { model.setWeekAhead(on: true, minute: $0) })) {
                        ForEach(s.choices.map { Int($0.int32Value) }, id: \.self) { m in
                            Text(LocalClock.companion.formatMinute(m: Int32(m))).tag(m)
                        }
                    }
                    .labelsHidden().frame(width: 90)
                    .transition(.opacity)
                }
            }
        }
        .padding(.top, MekaSpace.m)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct WeekAheadDayView: View {
    let day: WeekAheadDay
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(day.label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .accessibilityAddTraits(.isHeader)
            let meta = [day.dayLine, day.weather].compactMap { $0 }.joined(separator: " · ")
            if !meta.isEmpty {
                Text(meta).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if day.lines.isEmpty && day.dayLine == nil {
                Text("Nothing in the diary").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            ForEach(day.lines, id: \.id) { l in
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(l.time ?? WeekAheadDayView.mark(l.kind)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .frame(width: 52, alignment: .leading)
                    Text(l.text).font(MekaType.body).foregroundStyle(l.lit ? palette.accent : palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(l.spoken)
            }
            if day.more > 0 {
                Text("and \(day.more) more").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
    }

    /// The lead mark for a line with no time: school, money due, the rest a dot.
    static func mark(_ kind: WeekAheadKind) -> String {
        if kind == .school { return "School" }
        if kind == .due { return "Due" }
        return "·"
    }
}

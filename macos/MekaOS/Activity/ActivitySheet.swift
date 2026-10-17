@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// What MEKA did and why (V1 activity log): every reminder and digest that reached you, and later every change MEKA
/// makes for you, newest first by day, each with the rule or setting behind it. A change can be undone here (only what
/// is still as MEKA left it). Synced with the Fold. Opened from Today's header or File → Activity… (⇧⌘A).
/// Motion: the sheet scale-fades; day sections stagger in; Undo gives a light haptic and the row's line cross-fades
/// to "Undone at 09:12"; the filter (All · Reminders · Done for you · Calendar · Calls · Other, only the kinds there are)
/// is a segmented control (tick haptic) and the days cross-fade; Blocked calls (spam calls still ring, slice 4: numbers
/// the block list stopped lately, with Unblock / Block again) shows under it for All and Calls, its rows cross-fading,
/// then Recent calls (call notifications, slice 4), a click unfolding a call's detail on the expand spring; MEKA's AI today ("Talk 6 · Ask 2 · 3¢") and MEKA's voice lines (how quickly it answered on this Mac lately, the month's characters) fade in once known. Reduce Motion:
/// cross-fades.
struct ActivitySheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The filter (slice 3), by name: this Mac's choice while the sheet is open, never synced.
    @State private var filterName = ActivityRules.shared.filterNamed(name: nil).name
    /// Recent calls: the call whose detail is unfolded (this Mac only).
    @State private var openCall: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Activity").font(MekaType.upNextTitle).staggeredAppear(0)
            if let line = model.aiTodayLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let line = model.voiceTimingLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let line = model.heardTimingLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let line = model.voiceUsageLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let view = model.activity, !view.isEmpty {
                Text("What Navilife did and why. \(view.weekLine).")
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(0)
                if let note = model.activityNote {
                    Text(note).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).transition(.opacity)
                }
                let chips = ActivityRules.shared.filters(view: view)
                let filter = ActivityRules.shared.chosen(view: view, chosen: ActivityRules.shared.filterNamed(name: filterName))
                if !chips.isEmpty {
                    Picker("Show", selection: Binding(
                        get: { filter.name },
                        set: { name in
                            guard name != filter.name else { return }
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { filterName = name }
                        }
                    )) {
                        ForEach(chips, id: \.filter.name) { c in
                            Text(c.label).tag(c.filter.name).accessibilityLabel(c.spoken)
                        }
                    }
                    .pickerStyle(.segmented).labelsHidden()
                    .staggeredAppear(1)
                }
                let shown = ActivityRules.shared.filtered(view: view, filter: filter)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        if let blocked = model.blockedCallers, !blocked.stopped.isEmpty,
                           filter.name == "ALL" || filter.name == "CALLS" {
                            blockedCalls(blocked).staggeredAppear(2)
                        }
                        if let calls = model.callLog, !calls.isEmpty,
                           filter.name == "ALL" || filter.name == "CALLS" {
                            recentCalls(calls).staggeredAppear(2)
                        }
                        ForEach(Array(shown.days.enumerated()), id: \.element.day) { i, day in
                            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                                Text(day.label.uppercased())
                                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                    .foregroundStyle(palette.textTertiary)
                                ForEach(day.rows, id: \.id) { row in rowView(row) }
                            }
                            .staggeredAppear(i + 2)
                        }
                    }
                    .id(filter.name)
                    .transition(.opacity)
                }
                .frame(maxHeight: 460)
            } else {
                Text(model.activity?.emptyLine ?? ActivityRules.shared.EMPTY_LINE)
                    .font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(1)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.activityNote)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voiceUsageLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voiceTimingLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.aiTodayLine)
        .task { await model.refreshVoiceUsage() }
    }

    /// Blocked calls: the numbers whose calls the block list stopped in the last 30 days, with Unblock / Block again.
    private func blockedCalls(_ view: BlockedCallersView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(StoppedCallRules.shared.TITLE.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
            Text([view.stoppedLine, StoppedCallRules.shared.HINT].compactMap { $0 }.joined(separator: ". "))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(view.stopped, id: \.key) { row in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.number).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    Text(row.why).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .id(row.why).transition(.opacity)
                    let key = row.key
                    if row.blocked {
                        Button(StoppedCallRules.shared.UNBLOCK_LABEL) { model.unblockCaller(key) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    } else {
                        Button(StoppedCallRules.shared.BLOCK_AGAIN_LABEL) { model.reblockCaller(key) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: row.blocked)
            }
        }
    }

    /// Recent calls (call notifications, slice 4): the call assistant's messages and calls missed at work from the last
    /// 7 days, one line each with a block chip; a click unfolds the call's detail in place.
    private func recentCalls(_ view: CallLogView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(CallLogRules.shared.TITLE.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
            Text([view.line, CallLogRules.shared.HINT].compactMap { $0 }.joined(separator: ". "))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(view.days, id: \.label) { day in
                Text(day.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                ForEach(day.rows, id: \.id) { row in callRow(row) }
            }
        }
    }

    private func callRow(_ row: CallLogRow) -> some View {
        let expanded = openCall == row.id
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button {
                MekaHaptics.tick()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { openCall = expanded ? nil : row.id }
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(row.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).monospacedDigit()
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(row.who).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(row.line).font(MekaType.itemMeta)
                            .foregroundStyle(row.urgent ? palette.critical : palette.textSecondary)
                        Text(row.chip).font(MekaType.caption)
                            .foregroundStyle(row.blocked ? palette.critical : palette.textTertiary)
                            .id(row.chip).transition(.opacity)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(expanded ? "Fold away" : "Show what happened")
            if expanded {
                callDetail(row.id, row.detail).transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.chip)
    }

    private func callDetail(_ id: String, _ d: CallDetail) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                ForEach(d.timeline, id: \.self) { step in
                    HStack(spacing: MekaSpace.xs) {
                        Circle().fill(palette.accent).frame(width: 6, height: 6)
                        Text(step).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                }
            }
            if let summary = d.summary {
                Text(summary).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let words = d.words {
                Text(words).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            }
            if let spam = d.spam {
                Text(spam).font(MekaType.caption).foregroundStyle(palette.critical)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(d.blockStatus).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .id(d.blockStatus).transition(.opacity)
            if d.listen != nil {
                VoiceMessagePlayerView(id: id, palette: palette)
            }
            HStack(spacing: MekaSpace.m) {
                if let label = d.callBack, let number = d.number,
                   let url = URL(string: "tel:" + number.filter { $0.isNumber || $0 == "+" }) {
                    Button(label) { MekaHaptics.light(); NSWorkspace.shared.open(url) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                if let label = d.block {
                    Button(label) { model.blockLoggedCaller(id) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                }
                if let label = d.unblock, let key = d.key {
                    Button(label) { model.unblockCaller(key) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                if let label = d.notSpam, let key = d.key {
                    Button(label) { model.dismissSuspectedCaller(key) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
        }
        .padding(.top, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: d.blockStatus)
    }

    private func rowView(_ row: ActivityRow) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            Text(row.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).monospacedDigit()
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                // Context, not something to act on: the regular body weight.
                Text(row.summary).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if let detail = row.detail {
                    Text(detail).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
                Text(row.why).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Group {
                    if let undone = row.undoneLine {
                        Text(undone).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    } else if row.canUndo {
                        Button("Undo") { model.undoActivity(row.id) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.undoneLine)
    }
}

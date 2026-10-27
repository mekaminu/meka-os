@preconcurrency import MekaKit
import SwiftUI

/// Evening shutdown on the Mac (build plan M1): what got done, what's left from today (tick it, carry it to tomorrow,
/// skip a repeating one, or send a one-off to Someday) and tomorrow at a glance, then Shut down. Synced with the Fold.
/// Motion: sections stagger in; ticks complete like a task; carried rows leave and land in Tomorrow; Shut down pops a
/// check and the sheet goes. Reduce Motion: cross-fades.
struct ShutdownSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var closing = false
    @State private var cardSaid: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Shut down the day").font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = model.shutdown {
                doneCount(v).staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        // Done for the day: what got done today, newest first, each with a check and its time.
                        if !v.done.isEmpty {
                            SectionLabel("Done today", palette).staggeredAppear(1)
                            ForEach(v.done, id: \.id) { r in
                                DoneLine(row: r, palette: palette).staggeredAppear(1)
                            }
                            if let more = v.doneMore {
                                Text(more).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                    .padding(.bottom, MekaSpace.m)
                                    .staggeredAppear(1)
                            }
                        }
                        SectionLabel("Left from today", palette).staggeredAppear(1)
                        if v.left.isEmpty {
                            Text("Nothing left from today.")
                                .font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .transition(.opacity)
                        }
                        ForEach(v.left, id: \.task.id) { item in
                            LeftRow(item: item, palette: palette)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                                .staggeredAppear(1)
                        }
                        if v.left.count > 1 {
                            Button("Move the rest to tomorrow") { model.carryAllToTomorrow() }
                                .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                                .padding(.vertical, MekaSpace.xs)
                                .staggeredAppear(1)
                        }

                        // Week board, item (11): how he feels; Tired or Stretched offers to lighten tomorrow.
                        FeelingSection(palette: palette).padding(.top, MekaSpace.l).staggeredAppear(2)

                        SectionLabel(v.tomorrow.label, palette).padding(.top, MekaSpace.l).staggeredAppear(2)
                        // Done for the day: tomorrow's first commitment.
                        if let first = v.firstLine {
                            Text(LoneWordGlue.shared.kept(line: first)).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).staggeredAppear(2)
                        }
                        if let work = v.tomorrow.workLine {
                            Text(LoneWordGlue.shared.kept(line: work)).font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(2)
                        }
                        // Done for the day, the first train: "First train · the 07:42 · leave by 07:32".
                        if let train = v.tomorrow.train {
                            Text(LoneWordGlue.shared.kept(line: train.text)).font(MekaType.caption)
                                .foregroundStyle(train.lit ? palette.accent : palette.textSecondary)
                                .accessibilityLabel(train.spoken).staggeredAppear(2)
                        }
                        // Tomorrow's weather (Weather slice 2).
                        if let weather = v.tomorrow.weatherLine {
                            Text(LoneWordGlue.shared.kept(line: weather)).font(MekaType.caption).foregroundStyle(palette.textSecondary).staggeredAppear(2)
                        }
                        // School rhythm, slice 2: the evening before, "Tomorrow · Theo: PE kit", in the accent.
                        if let school = v.tomorrow.school {
                            Text(LoneWordGlue.shared.kept(line: school.text)).font(MekaType.caption).foregroundStyle(palette.accent)
                                .accessibilityLabel(school.spoken).staggeredAppear(2)
                        }
                        // Tomorrow on Today, slice 2: bills and renewals due tomorrow, and a last day to cancel.
                        if let bills = v.tomorrow.bills {
                            VStack(alignment: .leading, spacing: 0) {
                                if let due = bills.due {
                                    Text(LoneWordGlue.shared.kept(line: due)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                }
                                if let cancel = bills.cancel {
                                    Text(LoneWordGlue.shared.kept(line: cancel)).font(MekaType.caption).foregroundStyle(palette.accent)
                                }
                            }
                            .accessibilityElement(children: .ignore).accessibilityLabel(bills.spoken).staggeredAppear(2)
                        }
                        Text(LoneWordGlue.shared.kept(line: v.tomorrow.summary))
                            .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .staggeredAppear(2)
                        ForEach(v.tomorrow.rows, id: \.id) { r in
                            TomorrowLine(row: r, palette: palette)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .top)))
                                .staggeredAppear(2)
                        }
                        // The smart wake alarm (Alarms, slice 1): confirm MEKA's suggestion or set your own.
                        if let wake = model.wake {
                            WakeSection(wake: wake, palette: palette)
                                .padding(.top, MekaSpace.l)
                                .staggeredAppear(3)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.left.map { $0.task.id })
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.tomorrow.rows.map(\.id))
                }
                .frame(maxHeight: 440)

                if closing || v.doneToday {
                    HStack(spacing: MekaSpace.xs) {
                        Image(systemName: "checkmark.circle.fill").font(MekaType.nowTitle).foregroundStyle(palette.accent)
                        Text(v.doneLine ?? "Day shut down").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.scale(scale: 0.6).combined(with: .opacity))
                }
                // Done for the day, completeness: the evening card's switch (synced) and what the last click did.
                HStack(spacing: MekaSpace.xs) {
                    Text(LoneWordGlue.shared.kept(line: ShutdownRules.shared.cardLine(off: v.cardOff, onLeave: v.onLeave)))
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Spacer()
                    Button(v.cardOff ? "Turn on" : "Turn off") { setCard(on: v.cardOff) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                }
                .padding(.top, MekaSpace.xs)
                if let said = cardSaid {
                    Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .transition(.opacity)
                }
            } else {
                SkeletonRows(count: 4, palette: palette)
            }

            HStack {
                Spacer()
                if model.shutdown?.doneToday == true || closing {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                } else {
                    Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button("Shut down") { shutDown() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
    }

    @ViewBuilder
    private func doneCount(_ v: ShutdownView) -> some View {
        if v.doneCount > 0 {
            CountUpText(Int(v.doneCount)) { "\($0) done today" }
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
        } else {
            Text(LoneWordGlue.shared.kept(line: v.doneCountLine)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
        }
    }

    /// The evening card on or off, on both apps; the line under it cross-fades to what it did.
    private func setCard(on: Bool) {
        MekaHaptics.tick()
        Task { @MainActor in
            let line = await model.setShutdownCard(on: on)
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { cardSaid = line }
        }
    }

    /// The check pops (a spring) with a light haptic, holds a moment, then the sheet goes.
    private func shutDown() {
        withAnimation(reduceMotion ? MekaMotion.appear(reduced: true) : .spring(response: 0.35, dampingFraction: 0.6)) { closing = true }
        MekaHaptics.light()
        Task { @MainActor in
            await model.shutDown()
            try? await Task.sleep(for: .milliseconds(700))
            dismiss()
        }
    }
}

/// One thing left from today: tick it, or carry it over.
private struct LeftRow: View {
    @Environment(CoreModel.self) private var model
    let item: ShutdownItem
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .top, spacing: MekaSpace.m) {
            CompleteButton(task: item.task, palette: palette)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(item.task.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let line = item.line {
                    Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.itemMeta).foregroundStyle(item.overdue ? palette.critical : palette.textSecondary)
                }
                HStack(spacing: MekaSpace.m) {
                    Button("Tomorrow") { model.carryOver(item.task.id) }
                    if item.canSkip { Button("Skip") { model.skip(item.task.id) } }
                    if item.canSomeday { Button("Someday") { model.moveToSomeday(item.task.id) } }
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                .padding(.top, MekaSpace.xxs)
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// One thing done today: a small check, the title (calm, like an event) and when it was ticked.
private struct DoneLine: View {
    let row: DoneRow
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            Text("✓").font(MekaType.captionStrong).foregroundStyle(palette.accent)
            Text(row.title).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(1)
            Spacer()
            Text(row.time).font(MekaType.caption).monospacedDigit().foregroundStyle(palette.textTertiary)
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// One line of tomorrow: time on the left (empty for a task with no time), title and detail on the right.
struct TomorrowLine: View {
    let row: TomorrowRow
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
            Text(row.time ?? "").font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(palette.textSecondary)
                .frame(width: 56, alignment: .leading)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(row.isEvent ? palette.textPrimary : palette.textSecondary)
                if let d = row.detail {
                    Text(LoneWordGlue.shared.kept(line: d)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// The evening card in Today: "Shut down the day" with what's left and tomorrow in one line.
struct ShutdownCard: View {
    @Environment(CoreModel.self) private var model
    let shutdown: ShutdownView
    let palette: MekaPalette

    var body: some View {
        Button { model.showShutdown = true } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("Shut down the day").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(shutdown.cardLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(MekaSpace.l)
            .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
        // Done for the day, completeness: Later puts the card and its heads-up off an hour, never past 23:00.
        .overlay(alignment: .trailing) {
            if shutdown.laterOffered {
                Button(ShutdownRules.shared.LATER_LABEL) { model.shutdownLater() }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .padding(.trailing, MekaSpace.l)
            }
        }
    }
}

/// Tomorrow at a glance in Today once the evening starts and the shutdown card isn't showing: the quiet
/// "Day shut down at 18:42" line (if done) over "Tomorrow: first thing 09:00 Standup · 3 events". The glance
/// cross-fades as tomorrow changes (only ever a fade, so reduced motion is the same). Clicking opens the shutdown sheet.
struct TomorrowGlanceView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let doneLine: String?
    let glance: String?
    let palette: MekaPalette

    var body: some View {
        Button { model.showShutdown = true } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                if let doneLine {
                    Text(doneLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                if let glance {
                    Text(glance).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        .lineLimit(2)
                        .id(glance)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .animation(MekaMotion.appear(reduced: reduceMotion), value: glance)
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// Daily receipt in Today's evening: "MEKA handled 12 things today" over its biggest parts ("5 reminders · 3 calls
/// stopped · 2 digests"), quiet like the glance, with the accent "Activity ›". The count cross-fades as it changes
/// (only ever a fade, so reduced motion is the same). Clicking opens the Activity sheet.
struct DailyReceiptView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let receipt: DailyReceipt
    let palette: MekaPalette

    var body: some View {
        Button { model.showActivity = true } label: {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(receipt.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                    if !receipt.detail.isEmpty {
                        Text(receipt.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                .id(receipt.line + receipt.detail)
                .transition(.opacity)
                .frame(maxWidth: .infinity, alignment: .leading)
                Text("Activity ›").font(MekaType.caption).foregroundStyle(palette.accent)
            }
            .contentShape(Rectangle())
            .animation(MekaMotion.appear(reduced: reduceMotion), value: receipt.line + receipt.detail)
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// "How are you feeling?" (Week board, item (11)): Good · Tired · Stretched, kept on this Mac only for a week. Tired and
/// Stretched offer to lighten tomorrow (two tasks to lighter days, one Undo on the bar) and Stretched names one that
/// could wait on Someday. Motion: the picked chip's fill cross-fades to the accent; the offer slides in under the chips
/// and goes once a click acts on it (Reduce Motion: fades only).
struct FeelingSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var picked: String?
    @State private var offer: FeelingOffer?
    /// Part 2: while Stretched holds the alerts that can wait, the sheet says so with Resume.
    @State private var held: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(FeelingRules.shared.QUESTION, palette)
            HStack(spacing: MekaSpace.xs) {
                ForEach(FeelingRules.shared.CHOICES, id: \.name) { f in
                    let name = f.name
                    chip(f.label, selected: picked == name) {
                        picked = name
                        Task { let o = await model.setFeeling(name); withAnimation(MekaMotion.appear(reduced: reduceMotion)) { offer = o } }
                    }
                }
            }
            if let o = offer {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(LoneWordGlue.shared.kept(line: o.line)).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    if let hl = o.holdLine {
                        Text(LoneWordGlue.shared.kept(line: hl)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    Text(LoneWordGlue.shared.kept(line: FeelingRules.shared.pickedLine(f: o.feeling))).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    HStack(spacing: MekaSpace.xs) {
                        if let label = o.lightenLabel, let name = picked {
                            chip(label, selected: true) { model.lightenTomorrow(name); close() }
                        }
                        if let drop = o.drop, let label = o.dropLabel {
                            let id = drop.taskId
                            chip(label, selected: false) { model.somedayOnBoard(id); close() }
                        }
                        if let label = o.holdLabel {
                            chip(label, selected: false) { model.holdAlerts(); close() }
                        }
                        if o.lightenLabel != nil || o.drop != nil || o.holdLabel != nil {
                            chip(FeelingRules.shared.LEAVE, selected: false) { close() }
                        }
                    }
                    .padding(.top, MekaSpace.xxs)
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            if let line = held {
                HStack(spacing: MekaSpace.xs) {
                    Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    Spacer(minLength: MekaSpace.xs)
                    Button(AlertHoldRules.shared.RESUME) {
                        Task {
                            await model.resumeAlerts()
                            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { held = nil }
                        }
                    }
                    .buttonStyle(.plain).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                .transition(.opacity)
            }
        }
        .task {
            held = await model.alertHoldLine()
            guard let name = await model.feelingToday() else { return }
            picked = name
            offer = await model.feelingOffer(name)
        }
    }

    private func close() { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { offer = nil } }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption)
                .foregroundStyle(selected ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(selected ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: selected)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(selected ? "\(label), selected" : label)
    }
}

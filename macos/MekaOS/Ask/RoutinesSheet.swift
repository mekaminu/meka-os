import AppKit
@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Routines, on the Mac (Routines, slices 1–4; slice 4: + New opens New routine, said by Dictation or
/// typed in one sentence; a click on a card opens the routine's page, where Run now
/// starts a run and the Running now card offers Carry on · Skip this step · Stop, also above the list), like the Fold's RoutinesPane: how many are on, the rule (a
/// routine never sends, pays, books or trades), YOUR ROUTINES — each a card with its day tile ("SUN" over "19:00"), its
/// name, its line, its next run, Remove and an On/Off switch (synced with the Fold) — and START WITH ONE, the starters
/// not added yet, each with Add. The words are the core's `RoutineRules`. Only Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); cards stagger in; a switch slides with a tick haptic and the list re-flows
/// on the expand spring as a card comes or goes. Reduce Motion: cross-fades.
struct RoutinesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var said: String?
    /// Slice 2: the routine whose page is open (nil: the list).
    @State private var openId: String?
    /// Slice 4: New routine is open; what he said or typed, the labels he chose (codes), and what Save said.
    @State private var making = false
    @State private var typed = ""
    @State private var modes = ""
    @State private var makeSaid: String?
    @FocusState private var fieldFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if making {
                newPage.transition(.opacity)
            } else if let v = model.routines, let id = openId, let d = v.detail(id: id) {
                page(d).transition(.opacity)
            } else {
                list
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: openId)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: making)
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    @ViewBuilder private var list: some View {
            HStack(alignment: .center) {
                Text(RoutineRules.shared.TITLE).font(MekaType.upNextTitle)
                Spacer(minLength: 0)
                // Slice 4: the mockup's brass + New beside the title opens New routine.
                Button(RoutineSentence.shared.NEW) {
                    MekaHaptics.light()
                    typed = ""; modes = ""; makeSaid = nil; said = nil
                    making = true
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta).foregroundStyle(palette.onAccent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.accent, in: Capsule())
                .accessibilityLabel(RoutineSentence.shared.NEW_SPOKEN)
            }
            .staggeredAppear(0)
            if let v = model.routines {
                Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body)
                    .foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    .staggeredAppear(0)
                Text(LoneWordGlue.shared.kept(line: RoutineRules.shared.RULE_LINE)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(0)
                if let said {
                    Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .transition(.opacity)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(v.running, id: \.id) { c in
                            runningCard(c).padding(.top, MekaSpace.m).transition(.opacity)
                        }
                        if !v.rows.isEmpty {
                            label(RoutineRules.shared.YOURS).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
                        }
                        ForEach(Array(v.rows.enumerated()), id: \.element.id) { i, r in
                            routineCard(r).staggeredAppear(i + 1)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                        if !v.starters.isEmpty {
                            label(RoutineRules.shared.START_WITH).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
                        }
                        ForEach(Array(v.starters.enumerated()), id: \.element.key) { i, s in
                            starterCard(s).staggeredAppear(v.rows.count + i + 2)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.rows.count)
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.starters.count)
                }
                .scrollIndicators(.never)
                .frame(maxHeight: 520)
            }
    }

    /// New routine (slice 4, the mockup's): ‹ Routines, the title, the field he types or dictates the routine into (the
    /// mic starts macOS Dictation in it), the foot line, then OBI MADE THESE STEPS: WHEN · STARTS WITH, each step with
    /// its place and label (a click moves it on), the note, what was left out, what's missing and Save routine. The draft
    /// is the core's `RoutineSentence.draft`, worked out here on the main actor from two Strings; Save sends only those
    /// two Strings to the core. Motion: the steps stagger in and re-flow on the expand spring as he types.
    @ViewBuilder private var newPage: some View {
        let d = RoutineSentence.shared.draft(text: typed, modes: modes)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button("‹ \(RoutineRules.shared.BACK)") { making = false }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                .accessibilityLabel("Back to \(RoutineRules.shared.BACK)")
            Text(RoutineSentence.shared.TITLE).font(MekaType.upNextTitle).accessibilityAddTraits(.isHeader).staggeredAppear(0)
            HStack(alignment: .top, spacing: MekaSpace.xs) {
                TextField(RoutineSentence.shared.HINT, text: $typed, axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(MekaType.body)
                    .lineLimit(3...6)
                    .focused($fieldFocused)
                    .onChange(of: typed) { _, _ in modes = "" }
                    .accessibilityLabel(RoutineSentence.shared.TITLE)
                Button { dictate() } label: { Image(systemName: "mic").foregroundStyle(palette.accent) }
                    .buttonStyle(MekaPressStyle())
                    .frame(width: 32, height: 32)
                    .background(palette.surface, in: Circle())
                    .help(RoutineSentence.shared.MIC_SPOKEN)
                    .accessibilityLabel(RoutineSentence.shared.MIC_SPOKEN)
            }
            .padding(MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .staggeredAppear(1)
            Text(LoneWordGlue.shared.kept(line: RoutineSentence.shared.FOOT)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            if let makeSaid {
                Text(LoneWordGlue.shared.kept(line: makeSaid)).font(MekaType.caption).foregroundStyle(palette.accent).transition(.opacity)
            }
            if !typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        label(RoutineSentence.shared.MADE).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
                        HStack(spacing: MekaSpace.xs) {
                            pageTile(RoutineSentence.shared.WHEN_TILE, d.whenTile)
                            pageTile(RoutineSentence.shared.STARTS_TILE, d.startsWith)
                        }
                        ForEach(Array(d.steps.enumerated()), id: \.offset) { i, st in
                            draftStepRow(d, st).staggeredAppear(i + 2)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                        if !d.steps.isEmpty {
                            Text(LoneWordGlue.shared.kept(line: RoutineSentence.shared.NOTE)).font(MekaType.caption)
                                .foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
                        }
                        if let left = d.leftOut {
                            Text(LoneWordGlue.shared.kept(line: left)).font(MekaType.caption).foregroundStyle(palette.accent)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if let p = d.problem {
                            Text(LoneWordGlue.shared.kept(line: p)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: d.steps.count)
                }
                .scrollIndicators(.never)
                .frame(maxHeight: 420)
                let canSave = d.canSave
                let text = typed
                let chosen = d.modes
                Button(RoutineSentence.shared.SAVE) { save(text, chosen) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(canSave ? palette.onAccent : palette.textTertiary)
                    .frame(maxWidth: .infinity).padding(.vertical, MekaSpace.xs)
                    .background(canSave ? palette.accent : palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .disabled(!canSave)
                    .accessibilityLabel(canSave ? "\(RoutineSentence.shared.SAVE): \(d.name), \(d.whenTile), \(d.steps.count) steps" : RoutineSentence.shared.SAVE)
            }
        }
        .onAppear { fieldFocused = true }
    }

    private func draftStepRow(_ d: RoutineDraft, _ st: RoutineDraftStep) -> some View {
        let index = st.index
        let hasNext = st.nextCode != nil
        let ink: Color = st.modeCode == "ask" ? palette.accent : (st.modeCode == "does" ? palette.textPrimary : palette.textSecondary)
        let relabelled = RoutineSentence.shared.relabel(d: d, index: index)
        return HStack(alignment: .center, spacing: MekaSpace.xs) {
            Text(st.number).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .frame(width: 28, height: 28)
                .background(palette.surface, in: Circle())
            VStack(alignment: .leading, spacing: 0) {
                Text(LoneWordGlue.shared.kept(line: st.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(st.place).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer(minLength: 0)
            if hasNext {
                Button(st.badge) { MekaHaptics.tick(); modes = relabelled }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption).foregroundStyle(ink)
                    .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                    .background(palette.surface, in: Capsule())
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: st.badge)
                    .accessibilityLabel(st.spoken)
            } else {
                Text(st.badge).font(MekaType.caption).foregroundStyle(ink).accessibilityLabel(st.spoken)
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// The mic: the field takes focus and macOS Dictation listens into it. Dictation is the Mac's own; nothing is
    /// recorded or kept here.
    private func dictate() {
        MekaHaptics.tick()
        fieldFocused = true
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(200))
            NSApp.sendAction(Selector(("startDictation:")), to: nil, from: nil)
        }
    }

    private func save(_ text: String, _ chosen: String) {
        Task {
            let r = await model.addRoutineFromSentence(text, modes: chosen)
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) {
                if let r, !r.refused {
                    said = r.line
                    makeSaid = nil
                    making = false
                } else {
                    makeSaid = r?.line ?? RoutineRules.shared.GONE_LINE
                }
            }
        }
    }

    /// A routine's own page (slice 2): ‹ Routines, its name and when, the NEXT · STEPS · LAST RUN tiles, its steps with
    /// their labels (a click moves a label on), LAST TIME, the switch and Remove.
    private func page(_ d: RoutineDetail) -> some View {
        let id = d.id
        let name = d.name
        let isOn = d.on
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button("‹ \(RoutineRules.shared.BACK)") { openId = nil }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                .accessibilityLabel("Back to \(RoutineRules.shared.BACK)")
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(LoneWordGlue.shared.kept(line: d.name)).font(MekaType.upNextTitle).accessibilityAddTraits(.isHeader)
                    Text(LoneWordGlue.shared.kept(line: d.whenLine)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                }
                Spacer(minLength: 0)
                Toggle("", isOn: Binding(get: { isOn }, set: { model.setRoutineOn(id, on: $0) }))
                    .toggleStyle(.switch).tint(palette.accent).labelsHidden()
                    .accessibilityLabel(isOn ? "Pause \(name)" : "Switch on \(name)")
            }
            .staggeredAppear(0)
            HStack(spacing: MekaSpace.xs) {
                pageTile(RoutineRules.shared.NEXT_TILE, d.next)
                pageTile(RoutineRules.shared.STEPS_TILE, d.stepCount)
                pageTile(RoutineRules.shared.LAST_TILE, d.lastRun)
            }
            .staggeredAppear(1)
            if let c = d.running {
                runningCard(c).transition(.opacity)
            } else if let runNow = d.runNow {
                Button(runNow) { model.runRoutineNow(id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("\(runNow): \(name)")
                    .transition(.opacity)
            }
            label(RoutineRules.shared.STEPS_LABEL).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
            ForEach(Array(d.steps.enumerated()), id: \.offset) { i, st in
                stepRow(id, st).staggeredAppear(i + 2)
            }
            Text(LoneWordGlue.shared.kept(line: d.stepsNote)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            label(RoutineRules.shared.LAST_TIME).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
            Text(LoneWordGlue.shared.kept(line: d.history)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(d.lastSteps.enumerated()), id: \.offset) { _, l in
                HStack(spacing: MekaSpace.xs) {
                    Text(LoneWordGlue.shared.kept(line: l.title)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Spacer(minLength: 0)
                    Text(l.outcomeLabel).font(MekaType.caption)
                        .foregroundStyle(l.quiet ? palette.textTertiary : palette.textPrimary)
                }
                .accessibilityElement(children: .combine)
            }
            HStack {
                Spacer()
                Button(RoutineRules.shared.REMOVE) {
                    model.removeRoutine(id)
                    openId = nil
                    said = "Removed \(name)"
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surfaceRaised, in: Capsule())
                .accessibilityLabel("\(RoutineRules.shared.REMOVE) \(name)")
            }
            .padding(.top, MekaSpace.m)
        }
    }

    /// The Running now card (slice 3): RUNNING NOW, the routine, the step it's on, "Step 2 of 4 · Week board · Waiting on
    /// you" with a bar that grows on the expand spring, then Carry on · Skip this step · Stop.
    private func runningCard(_ c: RoutineRunCard) -> some View {
        let id = c.id
        let fraction = CGFloat(c.fraction)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(RoutineRules.shared.RUNNING_NOW.uppercased()).font(MekaType.sectionLabel)
                    .tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.accent)
                Text(LoneWordGlue.shared.kept(line: c.name)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                Text(LoneWordGlue.shared.kept(line: c.stepTitle)).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text("\(c.progress) · \(c.place) · \(c.state)").font(MekaType.caption)
                    .foregroundStyle(c.waitingOnYou ? palette.accent : palette.textTertiary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(c.spoken)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.hairline)
                    Capsule().fill(palette.accent).frame(width: g.size.width * min(max(fraction, 0), 1))
                }
            }
            .frame(height: 4)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: fraction)
            HStack(spacing: MekaSpace.xs) {
                Button(RoutineRules.shared.CARRY_ON) { model.carryOnRoutine(id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("\(RoutineRules.shared.CARRY_ON): \(c.stepTitle)")
                Button(RoutineRules.shared.SKIP_STEP) { model.skipRoutineStep(id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surface, in: Capsule())
                    .accessibilityLabel("\(RoutineRules.shared.SKIP_STEP): \(c.stepTitle)")
                Spacer(minLength: 0)
                Button(RoutineRules.shared.STOP) { model.stopRoutineRun(id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("\(RoutineRules.shared.STOP) \(c.name)")
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: c.progress)
    }

    private func pageTile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(title.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary).lineLimit(1)
            Text(value).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(1)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
    }

    private func stepRow(_ id: String, _ st: RoutineStepRow) -> some View {
        let index = st.index
        let nextCode = st.nextCode
        let ink: Color = st.modeCode == "ask" ? palette.accent : (st.modeCode == "does" ? palette.textPrimary : palette.textSecondary)
        return HStack(alignment: .center, spacing: MekaSpace.xs) {
            Text(st.number).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .frame(width: 28, height: 28)
                .background(palette.surfaceRaised, in: Circle())
            VStack(alignment: .leading, spacing: 0) {
                Text(LoneWordGlue.shared.kept(line: st.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(st.place).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer(minLength: 0)
            if let nextCode {
                Button(st.badge) { model.setRoutineStepMode(id, index: index, mode: nextCode) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption).foregroundStyle(ink)
                    .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                    .background(palette.surfaceRaised, in: Capsule())
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: st.badge)
                    .accessibilityLabel(st.spoken)
            } else {
                Text(st.badge).font(MekaType.caption).foregroundStyle(ink).accessibilityLabel(st.spoken)
            }
        }
    }

    /// The day tile: "SUN" over "19:00".
    private func dayTile(_ day: String, _ time: String, on: Bool) -> some View {
        VStack(spacing: MekaSpace.xxs) {
            Text(day).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(on ? palette.accent : palette.textTertiary).lineLimit(1)
            Text(time).font(MekaType.itemMeta).foregroundStyle(on ? palette.textPrimary : palette.textTertiary).lineLimit(1)
        }
        .frame(minWidth: 64)
        .padding(MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
    }

    private func routineCard(_ r: RoutineRow) -> some View {
        let id = r.id
        let name = r.name
        let isOn = r.on
        return HStack(alignment: .center, spacing: MekaSpace.m) {
            dayTile(r.day, r.time, on: r.on)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(LoneWordGlue.shared.kept(line: r.name)).font(MekaType.body)
                    .foregroundStyle(r.on ? palette.textPrimary : palette.textTertiary)
                Text(LoneWordGlue.shared.kept(line: r.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let next = r.next {
                    Text(LoneWordGlue.shared.kept(line: next)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                Button(RoutineRules.shared.REMOVE) {
                    model.removeRoutine(id)
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { said = "Removed \(name)" }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("\(RoutineRules.shared.REMOVE) \(name)")
            }
            Spacer(minLength: 0)
            Toggle("", isOn: Binding(get: { isOn }, set: { model.setRoutineOn(id, on: $0) }))
                .toggleStyle(.switch)
                .tint(palette.accent)
                .labelsHidden()
                .accessibilityLabel(r.switchSpoken)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        // Slice 2: a click on the card opens its page (the switch and Remove keep their own clicks).
        .onTapGesture { openId = id }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(r.spoken)
        .accessibilityAddTraits(.isButton)
    }

    private func starterCard(_ s: RoutineStarterRow) -> some View {
        let key = s.key
        return HStack(alignment: .center, spacing: MekaSpace.m) {
            dayTile(s.day, s.time, on: false)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(LoneWordGlue.shared.kept(line: s.name)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: s.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(LoneWordGlue.shared.kept(line: s.whenLine)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer(minLength: 0)
            Button(RoutineRules.shared.ADD) { add(key) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surface, in: Capsule())
                .accessibilityLabel("\(RoutineRules.shared.ADD) \(s.name)")
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(s.spoken)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
    }

    private func add(_ key: String) {
        Task {
            let r = await model.addStarterRoutine(key)
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { said = r?.line ?? RoutineRules.shared.GONE_LINE }
        }
    }
}

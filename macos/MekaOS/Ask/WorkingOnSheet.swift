@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Obi is working on, on the Mac (Ada plans with you, part 3 slices 1 and 4), like the Fold's
/// WorkingOnPane: first ERRANDS YOU ASKED FOR (Ask Obi for an errand in a popover, each errand a card with its status,
/// what he did and needs, the draft with Copy, and Stop), then the standing errands: what needs him first, each card with WHAT I DID and, when there is one, WHAT I NEED FROM YOU (in the accent) and the
/// button to the screen that holds it; then STANDING ERRANDS with Turn off / Turn on (synced with the Fold). The words
/// are the core's `WorkingOnRules.view`. Only Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); cards and switches stagger in; a switch presses in with a tick haptic and
/// the list re-flows on the expand spring as a card comes or goes. Reduce Motion: cross-fades.
struct WorkingOnSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var view: WorkingOnView?
    /// Part 3 slice 4: the ask popover and its field, and the last line (on it, copied, stopped).
    @State private var asking = false
    @State private var askText = ""
    @State private var said: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(WorkingOnRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = view {
                Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body)
                    .foregroundStyle(v.needCount > 0 ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    .staggeredAppear(0)
                Text(LoneWordGlue.shared.kept(line: WorkingOnRules.shared.INTRO)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        jobsSection(v)
                        if !v.cards.isEmpty {
                            label(WorkingOnRules.shared.WATCHING).padding(.top, MekaSpace.m)
                                .accessibilityAddTraits(.isHeader)
                        }
                        ForEach(Array(v.cards.enumerated()), id: \.element.errand.code) { i, c in
                            card(c).staggeredAppear(v.jobs.count + i + 2)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                        Text(WorkingOnRules.shared.SWITCHES.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.m)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(Array(v.switches.enumerated()), id: \.element.errand.code) { i, s in
                            switchRow(s).staggeredAppear(v.jobs.count + v.cards.count + 3 + i)
                        }
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.cards.count)
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.jobs.count)
                }
                .scrollIndicators(.never)
                .frame(maxHeight: 520)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .task {
            // The server works an errand a step a minute and sync brings each in: read again while the sheet is open.
            while !Task.isCancelled {
                if let next = await model.workingOnView() { view = next }
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    // MARK: Part 3 slice 4: the errands he asked for

    @ViewBuilder
    private func jobsSection(_ v: WorkingOnView) -> some View {
        label(ObiJobRules.shared.TITLE).padding(.top, MekaSpace.xs)
            .accessibilityAddTraits(.isHeader)
        Text(LoneWordGlue.shared.kept(line: ObiJobRules.shared.INTRO)).font(MekaType.caption)
            .foregroundStyle(palette.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        HStack {
            pill(ObiJobRules.shared.ASK_ROW, ink: palette.accent, fill: palette.surfaceRaised) {
                said = nil
                asking = true
            }
            .popover(isPresented: $asking, arrowEdge: .bottom) { askPanel() }
            Spacer()
        }
        if let said {
            Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .transition(.opacity)
        }
        if v.jobs.isEmpty {
            Text(LoneWordGlue.shared.kept(line: ObiJobRules.shared.EMPTY)).font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        ForEach(Array(v.jobs.enumerated()), id: \.element.id) { i, j in
            jobCard(j).staggeredAppear(i + 1)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
    }

    private func jobCard(_ j: ObiJobRow) -> some View {
        let id = j.id
        let draft = j.draft
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(LoneWordGlue.shared.kept(line: j.title)).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(LoneWordGlue.shared.kept(line: j.status)).font(MekaType.caption)
                .foregroundStyle(j.needsYou ? palette.accent : palette.textSecondary)
            label(WorkingOnRules.shared.DID).padding(.top, MekaSpace.xs)
            Text(LoneWordGlue.shared.kept(line: j.did)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let need = j.need {
                label(WorkingOnRules.shared.NEED).padding(.top, MekaSpace.xs)
                Text(LoneWordGlue.shared.kept(line: need)).font(MekaType.body).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let draft {
                label(ObiJobRules.shared.DRAFT_LABEL).padding(.top, MekaSpace.xs)
                Text(draft).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(MekaSpace.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                Text(LoneWordGlue.shared.kept(line: ObiJobRules.shared.DRAFT_FOOT)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: MekaSpace.xs) {
                Spacer()
                if let draft {
                    pill(ObiJobRules.shared.COPY, ink: palette.accent, fill: palette.surface) { copy(draft) }
                        .accessibilityLabel("\(ObiJobRules.shared.COPY) the draft: \(j.title)")
                }
                if j.canStop {
                    pill(ObiJobRules.shared.STOP, ink: palette.textSecondary, fill: palette.surface) { stop(id) }
                        .accessibilityLabel("\(ObiJobRules.shared.STOP): \(j.title)")
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(j.spoken)
    }

    private func askPanel() -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(ObiJobRules.shared.SHEET_TITLE).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(LoneWordGlue.shared.kept(line: ObiJobRules.shared.INTRO)).font(MekaType.caption)
                .foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            TextField(ObiJobRules.shared.HINT, text: $askText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...4)
                .onSubmit { ask() }
            HStack {
                Spacer()
                Button(ObiJobRules.shared.SHEET_CANCEL) { asking = false }
                Button(ObiJobRules.shared.SHEET_GO) { ask() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(askText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 360)
    }

    private func pill(_ title: String, ink: Color, fill: Color, action: @escaping () -> Void) -> some View {
        Button(title) {
            MekaHaptics.tick()
            action()
        }
        .buttonStyle(MekaPressStyle())
        .font(MekaType.itemMeta)
        .foregroundStyle(ink)
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .background(fill, in: Capsule())
    }

    private func ask() {
        let text = String(askText.prefix(Int(ObiJobRules.shared.MAX_ASK)))
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        Task {
            let asked = await model.askObiErrand(text)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                said = asked?.line ?? ObiJobRules.shared.BLANK
                if asked?.id != nil { asking = false; askText = "" }
                if let next { view = next }
            }
        }
    }

    private func stop(_ id: String) {
        Task {
            let stopped = await model.stopObiErrand(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                said = stopped ? ObiJobRules.shared.STOPPED_LINE : ObiJobRules.shared.STOP_GONE
                if let next { view = next }
            }
        }
    }

    /// Copy puts the draft on the clipboard for him to paste and send himself; Navilife never sends it.
    private func copy(_ draft: String) {
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(draft, forType: .string)
        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { said = ObiJobRules.shared.COPIED }
    }

    private func card(_ c: WorkingOnCard) -> some View {
        let place = c.errand.place
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(c.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            label(WorkingOnRules.shared.DID)
            Text(LoneWordGlue.shared.kept(line: c.did)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let need = c.need {
                label(WorkingOnRules.shared.NEED).padding(.top, MekaSpace.xs)
                Text(LoneWordGlue.shared.kept(line: need)).font(MekaType.body).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Spacer()
                Button(c.errand.openLabel) {
                    MekaHaptics.tick()
                    model.openFromWorkingOn(place, reduced: reduceMotion)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surface, in: Capsule())
                .accessibilityLabel("\(c.errand.openLabel): \(c.title)")
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(c.spoken)
    }

    private func switchRow(_ s: WorkingOnSwitch) -> some View {
        let code = s.errand.code
        let turnOn = !s.on
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(s.errand.title).font(MekaType.body).foregroundStyle(s.on ? palette.textPrimary : palette.textTertiary)
            Text(LoneWordGlue.shared.kept(line: s.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(s.action) { toggle(code, on: turnOn) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surfaceRaised, in: Capsule())
                .accessibilityLabel("\(s.action): \(s.errand.title)")
        }
        .padding(.top, MekaSpace.xs)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
    }

    private func toggle(_ code: String, on: Bool) {
        MekaHaptics.tick()
        Task {
            let next = await model.setStandingErrand(code, on: on)
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { if let next { view = next } }
        }
    }
}

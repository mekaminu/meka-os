@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Obi is working on, on the Mac (Ada plans with you, part 3 slices 1 and 4), like the Fold's
/// WorkingOnPane: first ERRANDS YOU ASKED FOR (Ask Obi for an errand in a popover, each errand a card with its status,
/// what he did and needs, what he said, the draft with Copy, Answer (a popover) or Carry on after the cap, and Stop), then the standing errands: what needs him first, each card with WHAT I DID and, when there is one, WHAT I NEED FROM YOU (in the accent) and the
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
    /// Part 3 slice 5: the errand whose question he is answering (its popover is open), and the answer field.
    @State private var answering: String?
    @State private var answerText = ""
    /// Obi on calls, slice 2a: the call card being edited (its popover is open), its three fields, and the line under CALLS FOR YOU.
    @State private var editingCall: String?
    @State private var callBusiness = ""
    @State private var callNumber = ""
    @State private var callNote = ""
    @State private var callSaid: String?
    /// Slice 3: the call whose words are open in Read the call (a popover).
    @State private var readingCall: String?

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
                        callsSection(v)
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
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.calls.count)
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
                // Every two seconds while Obi is on a call, so the live words keep up.
                let live = view?.calls.contains { $0.live } ?? false
                try? await Task.sleep(for: .milliseconds(live ? Int(BookingLiveRules.shared.LIVE_REFRESH_MS) : 15_000))
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
        let need = j.need ?? ""
        let canAnswer = j.canAnswer
        let canCarryOn = j.canCarryOn
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
            if let youSaid = j.youSaid {
                label(ObiJobRules.shared.YOU_SAID).padding(.top, MekaSpace.xs)
                Text(LoneWordGlue.shared.kept(line: youSaid)).font(MekaType.body).foregroundStyle(palette.textSecondary)
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
            // Week 3's walk-through (A19): a card's buttons start under its words, like the call card's Play.
            HStack(spacing: MekaSpace.xs) {
                if canCarryOn {
                    pill(ObiJobRules.shared.CARRY_ON, ink: palette.onAccent, fill: palette.accent) { carryOn(id) }
                        .accessibilityLabel("\(ObiJobRules.shared.CARRY_ON): \(j.title)")
                } else if canAnswer {
                    pill(ObiJobRules.shared.ANSWER, ink: palette.onAccent, fill: palette.accent) {
                        said = nil
                        answerText = ""
                        answering = id
                    }
                    .accessibilityLabel("\(ObiJobRules.shared.ANSWER): \(j.title)")
                    .popover(isPresented: Binding(get: { answering == id }, set: { if !$0 { answering = nil } }), arrowEdge: .bottom) {
                        answerPanel(id, need: need)
                    }
                }
                if let draft {
                    pill(ObiJobRules.shared.COPY, ink: palette.accent, fill: palette.surface) { copy(draft) }
                        .accessibilityLabel("\(ObiJobRules.shared.COPY) the draft: \(j.title)")
                }
                if j.canStop {
                    pill(ObiJobRules.shared.STOP, ink: palette.textSecondary, fill: palette.surface) { stop(id) }
                        .accessibilityLabel("\(ObiJobRules.shared.STOP): \(j.title)")
                }
                Spacer(minLength: 0)
            }
            .padding(.top, MekaSpace.xs)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(j.spoken)
    }

    // MARK: Obi on calls, slice 2a: CALLS FOR YOU

    @ViewBuilder
    private func callsSection(_ v: WorkingOnView) -> some View {
        if !v.calls.isEmpty {
            label(BookingCallRules.shared.TITLE).padding(.top, MekaSpace.m)
                .accessibilityAddTraits(.isHeader)
            Text(LoneWordGlue.shared.kept(line: BookingCallRules.shared.INTRO)).font(MekaType.caption)
                .foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let callSaid {
                Text(LoneWordGlue.shared.kept(line: callSaid)).font(MekaType.caption).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            ForEach(Array(v.calls.enumerated()), id: \.element.id) { i, r in
                callCard(r).staggeredAppear(v.jobs.count + i + 2)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// One call card (mockup Call card): its status and business, then the brief while it waits (slice 2a), the live
    /// view while Obi is on the call, or the result card once it's over (slice 3).
    private func callCard(_ r: BookingCallRow) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(r.status.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(r.needsYou ? palette.accent : palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            Text(r.business).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            if r.live {
                liveView(r)
            } else if !r.showBrief {
                resultView(r)
            } else {
                briefView(r)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: r.status)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(r.spoken)
    }

    /// Slice 3: the live view: the business · a clock ticking each second, LIVE WORDS (Obi's in the accent), Hang up.
    @ViewBuilder
    private func liveView(_ r: BookingCallRow) -> some View {
        let id = r.id
        let business = r.business
        let since = r.liveSinceMs
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let nowMs = Int64(ctx.date.timeIntervalSince1970 * 1000)
            Text(BookingLiveRules.shared.liveLine(business: business, sinceMs: since, nowMs: nowMs))
                .font(MekaType.caption).foregroundStyle(palette.textSecondary).monospacedDigit()
        }
        label(BookingLiveRules.shared.LIVE_WORDS).padding(.top, MekaSpace.xs)
        if r.liveWords.isEmpty {
            Text(LoneWordGlue.shared.kept(line: BookingLiveRules.shared.WAITING_WORDS)).font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
        }
        words(r.liveWords)
        if r.canHangUp {
            HStack(spacing: MekaSpace.xs) {
                Spacer()
                // Slice 3c: Listen (his mobile is in About you): his mobile rings, press 1 and he hears both sides, muted.
                if r.canListen {
                    pill(BookingLiveRules.shared.LISTEN, ink: palette.textPrimary, fill: palette.surface) { listen(id) }
                        .accessibilityLabel("\(BookingLiveRules.shared.LISTEN): \(business)")
                }
                // Slice 3b: Take over (his mobile is in About you): Obi puts them through to his mobile, press 1 to take it.
                if r.canTakeOver {
                    pill(BookingLiveRules.shared.TAKE_OVER, ink: palette.accent, fill: palette.surface) { takeOver(id) }
                        .accessibilityLabel("\(BookingLiveRules.shared.TAKE_OVER): \(business)")
                }
                pill(BookingLiveRules.shared.HANG_UP, ink: palette.onAccent, fill: palette.critical) { hangUp(id) }
                    .accessibilityLabel("\(BookingLiveRules.shared.HANG_UP): \(business)")
            }
            .padding(.top, MekaSpace.xs)
        }
    }

    /// The call's lines: who in a small label (Obi in the accent), what was said under it; each fades in.
    private func words(_ lines: [BookingWordLine]) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(l.who.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(l.obi ? palette.accent : palette.textTertiary)
                    Text(LoneWordGlue.shared.kept(line: l.text)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .transition(.opacity)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: lines.count)
    }

    /// Slice 3: the result card: how long, WHEN · PEOPLE · NAME, the result, In your calendar with Add, Read the call.
    @ViewBuilder
    private func resultView(_ r: BookingCallRow) -> some View {
        let id = r.id
        if let length = r.lengthLine {
            Text(LoneWordGlue.shared.kept(line: length)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
        }
        if !r.resultTiles.isEmpty {
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(r.resultTiles.enumerated()), id: \.offset) { _, t in tile(t.label, t.value) }
            }
            .padding(.top, MekaSpace.xs)
        }
        if let result = r.result {
            Text(LoneWordGlue.shared.kept(line: result)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
        }
        if let line = r.calendarLine {
            HStack(spacing: MekaSpace.xs) {
                Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                if r.addedToCalendar {
                    Text(BookingLiveRules.shared.ADDED).font(MekaType.caption).foregroundStyle(palette.success)
                } else if r.canAddToCalendar {
                    pill(BookingLiveRules.shared.ADD_TO_CALENDAR, ink: palette.onAccent, fill: palette.accent) { addToCalendar(id) }
                        .accessibilityLabel("\(BookingLiveRules.shared.ADD_TO_CALENDAR): \(r.business)")
                }
            }
            .padding(MekaSpace.xs)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
            .padding(.top, MekaSpace.xs)
        }
        // Recordings with both voices, slice 2: the whole call, Obi and them, played from memory.
        if r.canPlay {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(CallRecordingRules.shared.BOOKING_LABEL.uppercased())
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.textTertiary)
                VoiceMessagePlayerView(id: id, palette: palette, booking: true,
                                       bookingRow: r.transcriptAtMs.isEmpty ? nil : r)
                Text(LoneWordGlue.shared.kept(line: CallRecordingRules.shared.BOOKING_FOOT))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            .accessibilityIdentifier("call-recording")
            .padding(.top, MekaSpace.xs)
        }
        if let back = r.calledBackLine {
            Text(LoneWordGlue.shared.kept(line: back)).font(MekaType.caption).foregroundStyle(palette.accent)
                .padding(.top, MekaSpace.xs)
        }
        if let tried = r.triedAgainLine {
            Text(LoneWordGlue.shared.kept(line: tried)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xs)
        }
        if r.canRead || r.canTryAgain {
            HStack(spacing: MekaSpace.xs) {
                Spacer()
                if r.canRead {
                    pill(BookingLiveRules.shared.READ, ink: palette.accent, fill: palette.surface) { readingCall = id }
                        .accessibilityLabel("\(BookingLiveRules.shared.READ): \(r.business)")
                        .popover(isPresented: Binding(get: { readingCall == id }, set: { if !$0 { readingCall = nil } }), arrowEdge: .bottom) {
                            readPanel(r)
                        }
                }
                // Try again: a new card for the same brief, waiting for Call now (nothing rings by itself).
                if r.canTryAgain {
                    pill(BookingCallRules.shared.TRY_AGAIN, ink: palette.onAccent, fill: palette.accent) { tryAgain(id) }
                        .accessibilityLabel("\(BookingCallRules.shared.TRY_AGAIN): \(r.business)")
                }
            }
            .padding(.top, MekaSpace.xs)
        }
    }

    /// Read the call: the whole call in words and how long it's kept.
    private func readPanel(_ r: BookingCallRow) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(BookingLiveRules.shared.READ_TITLE).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(LoneWordGlue.shared.kept(line: [r.lengthLine ?? r.business, r.result].compactMap { $0 }.joined(separator: " · ")))
                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView { words(r.transcript) }
                .scrollIndicators(.never)
                .frame(maxHeight: 360)
            Text(LoneWordGlue.shared.kept(line: BookingLiveRules.shared.READ_FOOT)).font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(BookingLiveRules.shared.CLOSE) { readingCall = nil }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 380)
    }

    /// The brief while the card waits (slice 2a): number, what, the tiles, Party size, may say, won't, foot, buttons.
    @ViewBuilder
    private func briefView(_ r: BookingCallRow) -> some View {
        let id = r.id
        let people = Int(r.peopleTile) ?? 1
        let canCall = r.canCall
        Group {
            Text(LoneWordGlue.shared.kept(line: r.numberLine)).font(MekaType.caption)
                .foregroundStyle(r.numberOk ? palette.textSecondary : palette.textTertiary)
            // The number looked up: Obi's find goes on the card only by this click; Call now still comes after.
            if r.canUseFound {
                HStack {
                    pill(BookingNumberRules.shared.USE, ink: palette.accent, fill: palette.surface) { useFound(id) }
                        .accessibilityLabel("\(BookingNumberRules.shared.USE): \(r.business)")
                    Spacer(minLength: 0)
                }
            }
            Text(LoneWordGlue.shared.kept(line: r.what)).font(MekaType.body).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                tile(BookingCallRules.shared.WHEN, r.whenTile)
                tile(BookingCallRules.shared.WINDOW, r.windowTile)
                tile(BookingCallRules.shared.PEOPLE, r.peopleTile)
            }
            .padding(.top, MekaSpace.xs)
            if r.canChangePeople {
                HStack(spacing: MekaSpace.xs) {
                    Text(BookingCallRules.shared.PARTY).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Spacer()
                    pill("−", ink: palette.accent, fill: palette.surface) { if people > 1 { setPeople(id, people - 1) } }
                        .accessibilityLabel("One fewer person")
                    pill("+", ink: palette.accent, fill: palette.surface) { if people < Int(BookingCallRules.shared.MAX_PEOPLE) { setPeople(id, people + 1) } }
                        .accessibilityLabel("One more person")
                }
            }
            if let note = r.note {
                Text(LoneWordGlue.shared.kept(line: "Ask for: \(note)")).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            label(BookingCallRules.shared.MAY_SAY).padding(.top, MekaSpace.xs)
            ForEach(r.maySay, id: \.self) { s in Text("· \(s)").font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            label(BookingCallRules.shared.WONT).padding(.top, MekaSpace.xs)
            ForEach(r.wont, id: \.self) { s in Text("· \(s)").font(MekaType.caption).foregroundStyle(palette.textSecondary) }
            if let foot = r.foot {
                Text(LoneWordGlue.shared.kept(line: foot)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.xs)
            }
            if let result = r.result {
                Text(LoneWordGlue.shared.kept(line: result)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let blocked = r.blocked {
                Text(LoneWordGlue.shared.kept(line: blocked)).font(MekaType.caption).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: MekaSpace.xs) {
                Spacer()
                if r.canCancel {
                    pill(BookingCallRules.shared.CANCEL, ink: palette.textSecondary, fill: palette.surface) { cancelCall(id) }
                        .accessibilityLabel("\(BookingCallRules.shared.CANCEL): \(r.business)")
                }
                if r.canEdit {
                    pill(BookingCallRules.shared.EDIT, ink: palette.accent, fill: palette.surface) {
                        callSaid = nil
                        callBusiness = r.business
                        callNumber = r.numberOk ? String(r.numberLine.split(separator: "·").first ?? "").trimmingCharacters(in: .whitespaces) : ""
                        callNote = r.note ?? ""
                        editingCall = id
                    }
                    .accessibilityLabel("\(BookingCallRules.shared.EDIT): \(r.business)")
                    .popover(isPresented: Binding(get: { editingCall == id }, set: { if !$0 { editingCall = nil } }), arrowEdge: .bottom) {
                        callEditPanel(id)
                    }
                    pill(BookingCallRules.shared.CALL_NOW, ink: canCall ? palette.onAccent : palette.textTertiary, fill: canCall ? palette.accent : palette.surface) {
                        if canCall { callNow(id) }
                    }
                    .accessibilityLabel("\(BookingCallRules.shared.CALL_NOW): \(r.business)")
                }
            }
            .padding(.top, MekaSpace.xs)
        }
    }

    private func tile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            label(title)
            // One line, whole ("19:00–20:00"), shrinking a little in a narrow sheet rather than splitting.
            Text(value).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
    }

    /// Edit the call card: who to ring, their number and anything Obi should ask for; Save and Cancel.
    private func callEditPanel(_ id: String) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(BookingCallRules.shared.EDIT_TITLE).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(BookingCallRules.shared.EDIT_BUSINESS).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            TextField(BookingCallRules.shared.EDIT_BUSINESS, text: $callBusiness).textFieldStyle(.roundedBorder)
            Text(BookingCallRules.shared.EDIT_NUMBER).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            TextField("01234 567890", text: $callNumber).textFieldStyle(.roundedBorder)
            Text(BookingCallRules.shared.EDIT_NOTE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            TextField(BookingCallRules.shared.EDIT_NOTE_HINT, text: $callNote).textFieldStyle(.roundedBorder)
                .onSubmit { saveCall(id) }
            HStack {
                Spacer()
                Button(BookingCallRules.shared.CANCEL) { editingCall = nil }
                Button(BookingCallRules.shared.SAVE) { saveCall(id) }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 360)
    }

    private func setPeople(_ id: String, _ people: Int) {
        Task {
            let next = await model.setBookingCallPeople(id, people: people)
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { if let next { view = next } }
        }
    }

    private func saveCall(_ id: String) {
        let business = String(callBusiness.prefix(Int(BookingCallRules.shared.MAX_BUSINESS)))
        let number = String(callNumber.prefix(24))
        let note = String(callNote.prefix(Int(BookingCallRules.shared.MAX_NOTE)))
        Task {
            let ok = await model.editBookingCall(id, business: business, number: number, note: note)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = ok ? BookingCallRules.shared.SAVED : BookingCallRules.shared.GONE
                editingCall = nil
                if let next { view = next }
            }
        }
    }

    private func callNow(_ id: String) {
        Task {
            let line = await model.callNowBookingCall(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func hangUp(_ id: String) {
        Task {
            let line = await model.hangUpBookingCall(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func listen(_ id: String) {
        Task {
            let line = await model.listenToBookingCall(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func takeOver(_ id: String) {
        Task {
            let line = await model.takeOverBookingCall(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func addToCalendar(_ id: String) {
        Task {
            let line = await model.addBookingCallToCalendar(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func useFound(_ id: String) {
        Task {
            let line = await model.useFoundBookingNumber(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func tryAgain(_ id: String) {
        Task {
            let line = await model.tryAgainBookingCall(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = line
                if let next { view = next }
            }
        }
    }

    private func cancelCall(_ id: String) {
        Task {
            let ok = await model.cancelBookingCall(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                callSaid = ok ? BookingCallRules.shared.CANCELLED_LINE : BookingCallRules.shared.GONE
                if let next { view = next }
            }
        }
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

    /// The answer popover: "Answer Obi", what he needs, one field, Tell Obi and Cancel.
    private func answerPanel(_ id: String, need: String) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(ObiJobRules.shared.ANSWER_TITLE).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            if !need.isEmpty {
                Text(LoneWordGlue.shared.kept(line: need)).font(MekaType.caption)
                    .foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            TextField(ObiJobRules.shared.ANSWER_HINT, text: $answerText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...4)
                .onSubmit { answer(id) }
            HStack {
                Spacer()
                Button(ObiJobRules.shared.SHEET_CANCEL) { answering = nil }
                Button(ObiJobRules.shared.ANSWER_GO) { answer(id) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(answerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(MekaSpace.m)
        .frame(width: 360)
    }

    private func answer(_ id: String) {
        let text = String(answerText.prefix(Int(ObiJobRules.shared.MAX_ANSWER)))
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        Task {
            let ok = await model.answerObiErrand(id, text: text)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                said = ok ? ObiJobRules.shared.ANSWERED_LINE : ObiJobRules.shared.ANSWER_GONE
                answering = nil
                answerText = ""
                if let next { view = next }
            }
        }
    }

    private func carryOn(_ id: String) {
        Task {
            let ok = await model.carryOnObiErrand(id)
            let next = await model.workingOnView()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                said = ok ? ObiJobRules.shared.CARRY_ON_LINE : ObiJobRules.shared.ANSWER_GONE
                if let next { view = next }
            }
        }
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
                Spacer(minLength: 0)
            }
            .padding(.top, MekaSpace.xs)
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

@preconcurrency import MekaKit
import SwiftUI

/// Ask → School on the Mac in the page shape (every page under Ask in one shape, slice 14; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's SchoolPane: the title with its ⓘ, one gold + Add (A day off ·
/// A school date · Every week, each one typed line; The school's INSET days, pasted dates or a letter, with School's
/// website › from About you), three numbers (how soon the next day off is, the days off to cover or the dates ahead,
/// the weekly things), then Days off, Dates, Every week and Holidays from the council (five rows each, See all for
/// the rest). A row opens in place with ‹ School back: its day and who, the cover answer or reminder, where it came
/// from, I'll work from home / Covered while the question is open, To a day… for a date, and Remove; a council's row
/// opens the council card (Fill in or Remove all, Add them, each with Undo). Only Strings, Bools and Int64s cross to
/// the core; nothing is sent to the school.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and field and the ⓘ unfold on the
/// expand spring; a row opens and closes with a cross-fade and slide from the trailing edge; the answer line
/// cross-fades (a line nothing could be read from lit in the accent); an add gives a light haptic, Remove a tick.
/// Reduce Motion: cross-fades.
struct SchoolSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    @State private var text = ""
    /// What the last add said, while the sheet is open.
    @State private var said: String?
    /// The row that's open, if any (its key, "item:…" or "council:…").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?
    /// The council card's last action for Undo: the council's id and whether it was Fill in.
    @State private var termsDone: (id: String, filled: Bool)?
    @State private var termsSaid: String?
    /// What Add them added, for Undo (exactly those go again).
    @State private var addedIds: [String]?

    var body: some View {
        let rules = SchoolPageRules.shared
        let v = model.school ?? SchoolView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil; said = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Group {
                    if rules.isCouncil(key: key) {
                        if let card = v.terms { termsCard(card) } else { Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                    } else {
                        rowDetail(key, v)
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(rules.cardTitle(cardKey: card)).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card, today: model.todayEpochDay), id: \.key) { r in
                            let key = r.key
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil; said = nil } } label: {
                                HStack(spacing: MekaSpace.xs) {
                                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                    }
                                    Spacer(minLength: MekaSpace.xs)
                                    Text(r.whenText).font(MekaType.caption).foregroundStyle(r.lit ? palette.accent : palette.textTertiary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(MekaPressStyle())
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
                .frame(maxHeight: 400)
                .transition(.opacity)
            } else if let page = model.schoolPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page, v) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if !adding, let said {
                        Text(said).font(MekaType.caption)
                            .foregroundStyle(rules.isNotRead(line: said) ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                .staggeredAppear(1)
                LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(0..<Int(page.cardCount), id: \.self) { i in
                            let card = page.cardAt(index: Int32(i))
                            let cardKey = card.key
                            LifePageCardView(card: card, palette: palette, onMore: {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = cardKey }
                            }, onRow: { r in
                                let key = r.key
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; said = nil }
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(SchoolRules.shared.SHARED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxHeight: 420)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
    }

    /// An open row as it is now: its part, day and who, notes, the cover taps while asked, To a day… and Remove.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: SchoolView) -> some View {
        let rules = SchoolPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let row = rules.row(view: v, key: key) {
                let id = row.id
                let title = row.title
                Text(title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(rules.partLabel(view: v, row: row)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    if let note = row.note {
                        Text(note).font(MekaType.caption).foregroundStyle(palette.accent)
                            .contentTransition(.opacity)
                    }
                    if let source = row.source {
                        Text(source).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(row.spoken)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: row.note)
                if let cover = rules.cover(view: v, key: key) {
                    Text(cover.question).font(MekaType.body).foregroundStyle(palette.accent)
                    HStack(spacing: MekaSpace.xs) {
                        Button(cover.homeLabel) { model.answerSchoolCover(id, home: true) }
                            .buttonStyle(.borderedProminent)
                            .accessibilityLabel("\(cover.homeLabel): \(cover.title)")
                        Button(cover.coveredLabel) { model.answerSchoolCover(id, home: false) }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("\(cover.coveredLabel): \(cover.title)")
                    }
                    Text(cover.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                // Week board item (5) part 4: getting ready for a school date as a task on a day of the Board.
                if let date = row.day?.int64Value {
                    DateToDayView(kind: .school, id: id, title: title, date: date, palette: palette)
                }
                Button("Remove") {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                    model.removeSchool(id)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(title)")
            } else {
                // Removed (here or on the Fold) while it was open.
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView, _ v: SchoolView) -> some View {
        let rules = SchoolPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                    let c = page.addChoiceAt(index: Int32(i))
                    let key = c.key
                    chip(c.label, lit: addChoice == key) { addChoice = key; said = nil }
                        .help(c.hint)
                }
            }
            if let choice = addChoice {
                if rules.isInset(choice: choice), let link = v.inset {
                    // Where the INSET days are, and the school's website from About you.
                    HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                        Text(link.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        if let act = link.action, let u = link.url, let url = URL(string: u) {
                            Spacer(minLength: MekaSpace.xs)
                            Button("\(act) ›") { openURL(url) }
                                .buttonStyle(MekaPressStyle())
                                .font(MekaType.itemMeta)
                                .foregroundStyle(palette.accent)
                                .accessibilityLabel("\(act). \(link.line)")
                        }
                    }
                }
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(rules.spokenHint(choice: choice))
                    .onSubmit { submit(choice) }
                if let said, rules.isNotRead(line: said) {
                    Text(said).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .transition(.opacity)
    }

    private func submit(_ choice: String) {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let rules = SchoolPageRules.shared
        let inset = rules.isInset(choice: choice)
        let m = model
        Task {
            let line: String?
            if inset { line = await m.addSchoolInset(typed) } else { line = await m.addSchool(typed) }
            let shown = line ?? (inset ? SchoolTerms.shared.INSET_NONE : SchoolRules.shared.NOT_READ)
            said = shown
            // A line nothing could be read from keeps its words to fix.
            if !rules.isNotRead(line: shown) { text = ""; adding = false; addChoice = nil }
        }
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }

    /// The council card: councils to pick from with Fill in, or what's in with Remove all; then the last action's line
    /// with Undo. Only the council's id crosses to the core.
    @ViewBuilder
    private func termsCard(_ card: SchoolTermCard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(card.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: card.line)
            if let r = card.refresh {
                HStack(spacing: MekaSpace.xs) {
                    Text(r.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    if let act = r.action {
                        Button(act) {
                            if let id = r.councilId {
                                Task {
                                    if let a = await model.addNewSchoolTerms(id) {
                                        termsSaid = a.line
                                        addedIds = a.ids
                                        termsDone = nil
                                    }
                                }
                            } else if let u = r.url, let url = URL(string: u) {
                                openURL(url)
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("\(act). \(r.line)")
                    }
                }
                .transition(.opacity)
            }
            ForEach(card.choices, id: \.id) { c in
                HStack(spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(c.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    Spacer()
                    Button("Fill in") {
                        let id = c.id
                        Task {
                            termsSaid = await model.fillSchoolTerms(id)
                            termsDone = termsSaid == nil ? nil : (id, true)
                            addedIds = nil
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Fill in \(c.name)'s holidays")
                }
            }
            if let f = card.filled {
                Button("Remove all") {
                    let id = f.id
                    Task {
                        termsSaid = await model.removeSchoolTerms(id)
                        termsDone = termsSaid == nil ? nil : (id, false)
                        addedIds = nil
                    }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove all of \(f.name)'s holidays")
            } else if !card.choices.isEmpty {
                Text(SchoolTerms.shared.NOT_LISTED).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if let termsSaid {
                HStack(spacing: MekaSpace.xs) {
                    Text(termsSaid).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let ids = addedIds {
                        Button("Undo") {
                            Task {
                                await model.undoNewSchoolTerms(ids)
                                addedIds = nil
                                self.termsSaid = nil
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                    } else if let done = termsDone {
                        Button("Undo") {
                            let id = done.id
                            let filled = done.filled
                            Task {
                                if filled { _ = await model.removeSchoolTerms(id) } else { _ = await model.fillSchoolTerms(id) }
                                termsDone = nil
                                self.termsSaid = nil
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: termsSaid)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: card.refresh?.line)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: card.filled?.id)
    }

}

/// Week board item (5) part 4: **To a day…** under a school date (School) or a birthday (Gifts), like the Fold's
/// DateToDay. The click (tick) unfolds "Which day?" and the days from today up to the date (expand spring); a day's click
/// (medium haptic) folds them away and makes the task on that day of the Board; what it did fades in with Undo. Only the
/// thing's id (a String) and the day (an Int64) cross to the core. Reduce Motion: cross-fades.
struct DateToDayView: View {
    enum Kind { case school, gift }
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let kind: Kind
    let id: String
    let title: String
    let date: Int64
    let palette: MekaPalette
    @State private var choosing = false
    @State private var said: String?
    @State private var undoable = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(BoardFeedRules.shared.TO_DAY) { MekaHaptics.tick(); choosing.toggle() }
                .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent).font(MekaType.itemMeta)
                .accessibilityLabel(BoardFeedRules.shared.dateSpoken(title: title))
            if choosing {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(BoardFeedRules.shared.PICK).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(model.dateFeedDays(date), id: \.day) { d in
                            MailNeedsPill(label: d.label, palette: palette, spoken: "\(d.spoken): \(title)", action: {
                                choosing = false
                                let day = d.day
                                let thing = id
                                Task {
                                    let line = kind == .school
                                        ? await model.schoolDateToDay(thing, day: day)
                                        : await model.giftToDay(thing, day: day)
                                    said = line
                                    undoable = line?.hasPrefix("Task ") == true
                                }
                            })
                        }
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            if let line = said {
                HStack(spacing: MekaSpace.xs) {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    if undoable {
                        Button("Undo") {
                            MekaHaptics.tick()
                            undoable = false
                            said = nil
                            Task { await model.undoDateToDay() }
                        }
                        .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent).font(MekaType.itemMeta)
                    }
                }
                .transition(.opacity)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: choosing)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
    }
}

/// School rhythm, slice 1: the week-ahead cover questions in Needs you, above the requests, then date night's
/// week-before card (slice 2), then one card per appointment that has come due (AppointmentNudgeRow). In the approved
/// Needs you look (Mac TO DECIDE slice) a school day off and date night are compact NeedsYouDecideCardViews like the
/// Fold's: the icon, the title over its when and question on one line, then its own two taps (I'll work from home ·
/// Covered; Booked · Skip this one). A card folds away on the expand spring and the undo bar rises; Reduce Motion:
/// cross-fades. Nothing is sent to anyone.
struct SchoolCoversView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.schoolCovers.enumerated()), id: \.element.id) { i, card in
                // The approved look (TO DECIDE, the Fold's slice 4): one compact card, its own two taps.
                NeedsYouDecideCardView(card: NeedsYouDecideRules.shared.school(card: card), palette: palette,
                                       onYes: { model.answerSchoolCover(card.id, home: true) },
                                       onNo: { model.answerSchoolCover(card.id, home: false) })
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
            if let card = model.dateNightNudge {
                NeedsYouDecideCardView(card: NeedsYouDecideRules.shared.dateNight(card: card), palette: palette,
                                       onYes: { model.answerDateNight(day: card.day, booked: true) },
                                       onNo: { model.answerDateNight(day: card.day, booked: false) })
                    .id("date-night-\(card.day)")
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + model.schoolCovers.count)
            }
            // Appointments that have come due (appointment nudges, slice 1), after date night's card.
            ForEach(Array(model.appointmentNudges.enumerated()), id: \.element.id) { i, card in
                AppointmentNudgeRow(card: card, palette: palette)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + model.schoolCovers.count + (model.dateNightNudge == nil ? 0 : 1) + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.schoolCovers.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.dateNightNudge?.day)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.appointmentNudges.map(\.id))
    }
}

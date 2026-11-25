@preconcurrency import MekaKit
import SwiftUI

/// Ask → Appointments on the Mac in the page shape (every page under Ask in one shape, slice 10; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's AppointmentsPane: the title with its ⓘ, one gold + Add
/// (Something you keep up with · A visit you've booked, each a field; A common one, the usual ones in one click), three
/// numbers (due to book, the next booked visit, how many he keeps up with), then To book, Booked and Next due. A row
/// opens in place with ‹ Appointments back: its status, the gold Went (with Undo on the bar), a Booked for… field, a
/// When did you last go? field while no visit is noted, and Remove. Needs you asks when one comes due. Only Strings
/// cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's form and the ⓘ unfold on the expand spring; a
/// row opens and closes with a cross-fade and slide from the trailing edge; the summary, the answer line and a row's
/// status cross-fade (a refusal or a due status lit in the accent); Add, Went and a booking give a light haptic, a
/// refusal or Remove a tick. Reduce Motion: cross-fades.
struct AppointmentsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var infoOpen = false
    @State private var adding = false
    @State private var choice: String?
    @State private var text = ""
    @State private var bookText = ""
    @State private var lastText = ""
    /// What the last add or Last went… said, while the sheet is open.
    @State private var said: CoreModel.AppointmentLine?
    /// The row that's open, if any (its key: "appt:…").
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?

    var body: some View {
        let rules = AppointmentsPageRules.shared
        let v = model.appointments ?? AppointmentView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil; said = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                rowDetail(key, v)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(card).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card, today: model.todayEpochDay), id: \.key) { r in
                            let key = r.key
                            Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil } } label: {
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                    Text([r.line, r.whenText].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
            } else if let page = model.appointmentsPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); said = nil; choice = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page, v) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    summaryLine(v)
                    if !adding, let said {
                        Text(said.line).font(MekaType.caption)
                            .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                    }
                }
                .staggeredAppear(1)
                if page.tileCount > 0 {
                    LifeNumberTiles(page: page, palette: palette).staggeredAppear(2)
                }
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
                        if page.tileCount > 0 {
                            Text(AppointmentRules.shared.NOTE_SHOWN).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
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

    /// What an open row shows: the appointment as it is now, with Went, Booked for…, Last went… and Remove.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: AppointmentView) -> some View {
        let rules = AppointmentsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let row = rules.row(view: v, key: key) {
                let id = row.id
                let title = row.title
                Text(title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                Text(row.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(row.status).font(MekaType.caption)
                    .foregroundStyle(row.lit ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.status)
                Button(AppointmentRules.shared.WENT_LABEL) { model.appointmentWent(id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("Went to \(title) today")
                Text(rules.BOOKED_FOR).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                TextField(AppointmentRules.shared.WHEN_HINT, text: $bookText)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel("\(rules.BOOKED_FOR) \(title)")
                    .onSubmit {
                        model.bookAppointment(id, when: bookText)
                        bookText = ""
                    }
                if let refusal = model.appointmentRefusal, refusal.id == id {
                    Text(refusal.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if rules.asksLastWent(r: row) {
                    Text(rules.LAST_WENT).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    TextField(rules.LAST_WENT_HINT, text: $lastText)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("\(rules.LAST_WENT) \(title)")
                        .onSubmit {
                            let line = rules.lastWentLine(title: title, typed: lastText)
                            lastText = ""
                            let m = model
                            Task { if let r = await m.typeAppointment(line) { said = r } }
                        }
                }
                if let said {
                    Text(said.line).font(MekaType.caption)
                        .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
                Button("Remove") {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                    model.removeAppointment(id)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(title)")
            } else {
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    /// Under the header: the view's count, or on a blank page (walk-through 2026-11-22 A15) its one line and button in
    /// one raised card, which opens + Add on A common one.
    @ViewBuilder
    private func summaryLine(_ v: AppointmentView) -> some View {
        let rules = AppointmentsPageRules.shared
        if rules.blank(view: v) {
            if !adding {
                LifeEmptyCard(line: rules.summary(view: v), button: rules.ADD_FIRST, palette: palette) {
                    let first = rules.firstChoice(view: v)
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding = true; said = nil; choice = first }
                }
                .transition(.opacity)
            }
        } else {
            Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
        }
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView, _ v: AppointmentView) -> some View {
        let rules = AppointmentsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(page.addTitle).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            ForEach(0..<Int(page.addChoiceCount), id: \.self) { i in
                let c = page.addChoiceAt(index: Int32(i))
                let key = c.key
                Button { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { choice = key; said = nil } } label: {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(c.label).font(MekaType.body).foregroundStyle(choice == key ? palette.accent : palette.textPrimary)
                        Text(c.hint).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityElement(children: .combine)
            }
            if let key = choice, let hint = rules.hint(choice: key) {
                TextField(hint, text: $text)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submit() }
            } else if choice == rules.ADD_COMMON {
                ForEach(v.suggestions, id: \.id) { s in
                    let sid = s.id
                    Button {
                        let m = model
                        Task {
                            guard let r = await m.addAppointmentPreset(sid) else { return }
                            said = r
                            if !r.refused { adding = false; choice = nil }
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text("+ \(s.title)").font(MekaType.body).foregroundStyle(palette.accent)
                            Text(s.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel("Add \(s.title), \(s.line)")
                }
            }
            if let said, said.refused {
                Text(said.line).font(MekaType.caption).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .transition(.opacity)
    }

    private func submit() {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let m = model
        Task {
            guard let r = await m.typeAppointment(typed) else { return }
            said = r
            if !r.refused { text = ""; adding = false; choice = nil }
        }
    }
}

/// Appointment nudges, slice 1: Needs you's card for one appointment that has come due, like the Fold's
/// AppointmentCardView: "Due Mon 2 Nov · every 6 months" · "Dentist" · the question · Booked… · Went · Not yet. In the
/// approved Needs you look (Mac slice 3) it is a compact NeedsYouDecideCardView: the title over its when and question on
/// one line, Booked… · Went · Not yet, then Call to book · Copy email in a quieter row. Booked… opens its sheet (the
/// next week's days and the usual times as chips, a field for a later day; walk-through week 5, A17) rather than a box
/// inside the card; a typed day that can't be read opens it again with the reason in the accent. MEKA books nothing.
struct AppointmentNudgeRow: View {
    @Environment(CoreModel.self) private var model
    let card: AppointmentNudge
    let palette: MekaPalette
    @State private var asking = false

    private var refusal: String? {
        guard let r = model.appointmentRefusal, r.id == card.id else { return nil }
        return r.line
    }

    var body: some View {
        NeedsYouDecideCardView(card: NeedsYouDecideRules.shared.appointment(card: card), palette: palette,
                               onYes: { MekaHaptics.light(); asking = true },
                               onNo: { model.appointmentNotYet(card.id) },
                               onChange: { model.appointmentWent(card.id) },
                               onMore: { i in
                                   if i == 0 { model.appointmentCallToBook(card.id) } else { model.appointmentCopyEmail(card.id) }
                               },
                               below: nil)
            .onChange(of: refusal) { _, line in if line != nil { asking = true } }
            .sheet(isPresented: $asking) {
                if let sheet = model.appointmentBookSheet(card.title) {
                    AppointmentBookSheetView(sheet: sheet, refusal: refusal, palette: palette,
                                             onSave: { words in asking = false; model.bookAppointment(card.id, when: words) },
                                             onClose: { asking = false })
                }
            }
    }
}

/// Booked…'s sheet on the Mac, like the Fold's AppointmentBookSheetView: the name and "When is it booked?", the day and
/// time chips, a field for a later day (its example always ahead of today), the chosen line, Close and Save.
struct AppointmentBookSheetView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let sheet: AppointmentBookSheet
    let refusal: String?
    let palette: MekaPalette
    let onSave: (String) -> Void
    let onClose: () -> Void
    @State private var day: Int64?
    @State private var minute: Int32?
    @State private var typed = ""

    private var rules: AppointmentBookRules { AppointmentBookRules.shared }
    private var words: String? {
        rules.toSave(day: day.map { KotlinLong(value: $0) }, minute: minute.map { KotlinInt(value: $0) }, typed: typed)
    }
    private var chosen: String {
        let t = typed.trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? rules.chosenLine(day: day.map { KotlinLong(value: $0) }, minute: minute.map { KotlinInt(value: $0) }) : t
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sheet.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(sheet.question).font(MekaType.body).foregroundStyle(palette.textSecondary)
            Text(sheet.dayLabel).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.top, MekaSpace.xs)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: MekaSpace.xs, alignment: .leading)],
                      alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(sheet.days, id: \.day) { d in
                    let value = d.day
                    chip(d.label, lit: day == value) { day = day == value ? nil : value; typed = "" }
                }
            }
            Text(sheet.timeLabel).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.top, MekaSpace.xs)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: MekaSpace.xs, alignment: .leading)],
                      alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(sheet.times, id: \.minute) { t in
                    let value = t.minute
                    chip(t.label, lit: minute == value) { minute = minute == value ? nil : value }
                }
            }
            TextField(sheet.typeHint, text: $typed)
                .textFieldStyle(.roundedBorder)
                .onChange(of: typed) { _, t in if !t.trimmingCharacters(in: .whitespaces).isEmpty { day = nil } }
                .onSubmit(save)
            if let refusal {
                Text(refusal).font(MekaType.caption).foregroundStyle(palette.accent)
            }
            Text(chosen).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: chosen)
            Text(sheet.note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                Spacer()
                Button("Close", action: onClose).buttonStyle(MekaPressStyle())
                    .font(MekaType.metaStrong).foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
                Button(action: save) {
                    Text(sheet.saveLabel).font(MekaType.metaStrong)
                        .foregroundStyle(words != nil ? palette.onAccent : palette.textTertiary)
                        .padding(.horizontal, MekaSpace.l).padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(words != nil ? palette.accent : palette.surfaceRaised))
                }
                .buttonStyle(MekaPressStyle())
                .disabled(words == nil)
                .keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.xs)
        }
        .padding(MekaSpace.l)
        .frame(width: 460, alignment: .leading)
        .background(palette.background)
    }

    private func save() {
        guard let w = words else { return }
        MekaHaptics.light()
        onSave(w)
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: { MekaHaptics.tick(); action() }) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

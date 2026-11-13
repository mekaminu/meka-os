import AppKit
@preconcurrency import MekaKit
import SwiftUI

/// Ask → Trips on the Mac in the page shape (every page under Ask in one shape, slice 16; approved 2026-11-11,
/// docs/design/look/LifePage.dc.html), like the Fold's TripsPane: the title with its ⓘ, one gold + Add (A trip · A
/// booking, one typed line the Trips field reads: "Barcelona 14–18 Nov", "Flight BA478 14 Nov 07:40 ref ABC123"),
/// three numbers (how soon the next trip is or which day of it, what's booked for it, the travel documents to check),
/// then Your trips, Booked for the next trip, Travel documents and Past trips (five rows each, See all for the rest).
/// A row opens in place with ‹ Trips back: a trip's day-by-day timeline with × on each booking, Lists ›, its TRAVEL
/// DOCUMENTS from the Vault (Open copy behind Touch ID, offline too) and Remove trip; a booking's day, Remove and the
/// whole trip; a document's line and Open copy. × and Remove trip say what went with **Undo** for a few seconds.
/// Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); sections stagger in; + Add's choices and field and the ⓘ unfold on the
/// expand spring; a row opens and closes with a cross-fade and slide from the trailing edge; the lines cross-fade (a
/// refusal in the accent); Add gives a light haptic (a tick when refused), Remove and × a tick; the Undo line slides in
/// and fades, Undo a light haptic; a trip's Lists cross-fade in place of Trips. Reduce Motion: cross-fades.
struct TripsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said.
    @State private var said: CoreModel.TripSaid?
    @State private var infoOpen = false
    @State private var adding = false
    @State private var addChoice: String?
    /// The row that's open, if any (its key).
    @State private var open: String?
    /// The card whose rows are all shown (See all), if any.
    @State private var showAll: String?
    /// Travel documents: the copy shown, and what the last Open copy said (by document).
    @State private var viewing: ViewedCopy?
    @State private var docSaid: (id: String, said: CoreModel.TripSaid)?
    /// What the last × or Remove trip took, offered back for a few seconds.
    @State private var removed: CoreModel.TripUndo?
    /// Trip lists, slice 1: the trip whose Lists show in place of Trips.
    @State private var listsFor: String?

    var body: some View {
        if let id = listsFor {
            TripListsPanel(tripId: id, palette: palette, onBack: {
                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { listsFor = nil }
            })
            .padding(MekaSpace.l)
            .frame(width: 500, alignment: .topLeading)
            .background(palette.surface)
            .transition(.opacity)
        } else {
            home.transition(.opacity)
        }
    }

    private func openLists(_ id: String) {
        MekaHaptics.tick()
        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { listsFor = id }
    }

    private func openRow(_ key: String) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = key; showAll = nil; said = nil }
    }

    @ViewBuilder
    private var home: some View {
        let rules = TripsPageRules.shared
        let v = model.trips ?? TripsView.companion.EMPTY
        let today = model.todayEpochDay
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let key = open {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                undoLine
                ScrollView {
                    rowDetail(key, v, today)
                }
                .frame(maxHeight: 520)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .trailing)))
            } else if let card = showAll {
                Button("‹ \(rules.TITLE)") { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { showAll = nil } }
                    .buttonStyle(.link).font(MekaType.caption)
                Text(rules.cardTitle(view: v, cardKey: card, today: today)).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(rules.allRows(view: v, cardKey: card, today: today), id: \.key) { r in
                            let key = r.key
                            Button { openRow(key) } label: {
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
            } else if let page = model.tripsPage() {
                LifePageHeader(title: page.title, info: page.info, palette: palette, infoOpen: $infoOpen) {
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { adding.toggle(); said = nil }
                }
                .staggeredAppear(0)
                if adding { addForm(page) }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    if !adding, let said {
                        Text(said.line).font(MekaType.caption)
                            .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .contentTransition(.opacity)
                    }
                    undoLine
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
                                openRow(r.key)
                            })
                            .staggeredAppear(3 + i)
                        }
                        Text(TripRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
        .animation(MekaMotion.appear(reduced: reduceMotion), value: removed)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showAll)
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .sheet(item: $viewing) { copy in
            VaultCopyViewer(copy: copy, palette: palette) { viewing = nil }
        }
        // As in the Vault: closing Trips, the screen sleeping or the session going away locks the copies again.
        .onDisappear { model.lockVault() }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.screensDidSleepNotification)) { _ in
            viewing = nil; model.lockVault()
        }
        .onReceive(NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.sessionDidResignActiveNotification)) { _ in
            viewing = nil; model.lockVault()
        }
    }

    /// The Undo after × or Remove trip, for a few seconds.
    @ViewBuilder
    private var undoLine: some View {
        if let removed {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(removed.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button("Undo") {
                    let token = removed.token
                    self.removed = nil
                    model.undoTripRemoval(token)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("Undo: \(removed.line)")
            }
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            .task(id: removed) {
                try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.removed = nil } }
            }
        }
    }

    @ViewBuilder
    private func addForm(_ page: LifePageView) -> some View {
        let rules = TripsPageRules.shared
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
                TextField(rules.hint(choice: choice), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(rules.spokenHint(choice: choice))
                    .onSubmit { submit() }
                if let said, said.refused {
                    Text(said.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .transition(.opacity)
    }

    private func submit() {
        let typed = text.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        let m = model
        Task {
            guard let r = await m.addTrip(typed) else { return }
            said = r
            // A refusal keeps its words to fix.
            if !r.refused { text = ""; adding = false; addChoice = nil }
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

    /// An open row as it is now: a trip, a booking or a travel document.
    @ViewBuilder
    private func rowDetail(_ key: String, _ v: TripsView, _ today: Int64) -> some View {
        let rules = TripsPageRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(rules.sheetTitle(view: v, key: key, today: today)).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            if let trip = rules.trip(view: v, key: key) {
                TripDetail(
                    row: trip, palette: palette, docSaid: docSaid,
                    onOpenCopy: openCopy, onRemoveBooking: removeBooking, onRemove: removeTrip,
                    lists: model.tripLists.first { $0.tripId == trip.id }?.summary,
                    onOpenLists: openLists
                )
            } else if let line = rules.booking(view: v, key: key) {
                let inTrip = rules.bookingTrip(view: v, key: key)
                let day = rules.bookingDay(view: v, key: key)
                Text([day?.label, inTrip?.name].compactMap { $0 }.joined(separator: " · "))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(line.lead).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .frame(width: 48, alignment: .leading)
                    Text(line.text).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(line.spoken)
                HStack(spacing: MekaSpace.m) {
                    let bookingId = line.bookingId
                    Button("Remove") {
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
                        removeBooking(bookingId)
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(line.text)")
                    if let t = inTrip {
                        let tripKey = rules.tripKey(id: t.id)
                        Button("All of \(t.name) ›") { openRow(tripKey) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
            } else if let doc = rules.doc(view: v, key: key, today: today) {
                let id = doc.docId
                Text(doc.line).font(MekaType.body).foregroundStyle(doc.lit ? palette.accent : palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel(doc.spoken)
                if let s = docSaid, s.id == id {
                    Text(s.said.line).font(MekaType.caption).foregroundStyle(palette.accent)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if doc.hasCopy {
                    Button("Open copy") { openCopy(id) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                        .accessibilityLabel("Open the copy of \(doc.title)")
                    Text(TripDocRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                // Removed (here or on the Fold) while it was open.
                Text(rules.GONE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func openCopy(_ docId: String) {
        Task {
            let (copy, s) = await model.openVaultCopyUnlocking(docId)
            if let copy { docSaid = nil; viewing = copy } else if let s { docSaid = (docId, s) }
        }
    }

    private func removeBooking(_ id: String) {
        Task { if let r = await model.removeTripBookingForUndo(id) { removed = r } }
    }

    private func removeTrip(_ id: String) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open = nil }
        Task { if let r = await model.removeTripForUndo(id) { removed = r } }
    }
}

/// A trip opened from its row: its dates and status, Lists ›, the day-by-day timeline with ×, its travel documents and
/// Remove trip.
private struct TripDetail: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let row: TripRow
    let palette: MekaPalette
    let docSaid: (id: String, said: CoreModel.TripSaid)?
    let onOpenCopy: (String) -> Void
    let onRemoveBooking: (String) -> Void
    let onRemove: (String) -> Void
    let lists: String?
    let onOpenLists: (String) -> Void

    var body: some View {
        let days = row.timeline.filter { !$0.lines.isEmpty }
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(row.status).font(MekaType.caption).foregroundStyle(row.statusLit ? palette.accent : palette.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            // Trip lists, slice 1: To buy, To pack and Before we leave, one click away on every trip.
            Button { onOpenLists(row.id) } label: {
                HStack(spacing: MekaSpace.xs) {
                    Text(TripListRules.shared.OPEN_LISTS).font(MekaType.captionStrong).foregroundStyle(palette.accent)
                    Text(lists ?? TripListRules.shared.LISTS_EMPTY).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Spacer()
                    Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.accent)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("Open the lists for \(row.name): \(lists ?? TripListRules.shared.LISTS_EMPTY)")
            if days.isEmpty {
                Text(TripRules.shared.NO_BOOKINGS).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(days, id: \.epochDay) { day in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(day.label.uppercased())
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                    ForEach(day.lines, id: \.key) { line in
                        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                                Text(line.lead).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                    .frame(width: 48, alignment: .leading)
                                Text(line.text).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(line.spoken)
                            Spacer()
                            Button("×") { onRemoveBooking(line.bookingId) }
                                .buttonStyle(MekaPressStyle())
                                .foregroundStyle(palette.textTertiary)
                                .accessibilityLabel("Remove \(line.text)")
                        }
                    }
                }
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: days.map(\.epochDay))
            if let docs = row.documents {
                TripDocsView(docs: docs, palette: palette, docSaid: docSaid, onOpenCopy: onOpenCopy)
            }
            Button("Remove trip") { onRemove(row.id) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.name) and its bookings")
        }
    }
}

/// A trip's TRAVEL DOCUMENTS from the Vault: the summary (lit with one to check), each document's line (lit when it
/// doesn't last the trip) and Open copy for a kept copy.
private struct TripDocsView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let docs: TripDocs
    let palette: MekaPalette
    let docSaid: (id: String, said: CoreModel.TripSaid)?
    let onOpenCopy: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(TripDocRules.shared.LABEL)
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
            Text(docs.summary).font(MekaType.caption)
                .foregroundStyle(docs.summaryLit ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: docs.summaryLit)
            ForEach(docs.rows, id: \.docId) { d in
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(d.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(d.line).font(MekaType.caption).foregroundStyle(d.lit ? palette.accent : palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        if let s = docSaid, s.id == d.docId {
                            Text(s.said.line).font(MekaType.caption).foregroundStyle(palette.accent)
                                .fixedSize(horizontal: false, vertical: true)
                                .transition(.opacity)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(d.spoken)
                    Spacer()
                    if d.hasCopy {
                        let id = d.docId
                        Button("Open copy") { onOpenCopy(id) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.caption)
                            .foregroundStyle(palette.accent)
                            .accessibilityLabel("Open the copy of \(d.title)")
                    }
                }
            }
            if !docs.isEmpty && docs.rows.contains(where: { $0.hasCopy }) {
                Text(TripDocRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, MekaSpace.xs)
    }
}

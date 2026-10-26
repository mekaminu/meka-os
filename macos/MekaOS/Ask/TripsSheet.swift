import AppKit
@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Trips on the Mac (travel mode, slice 1), like the Fold's TripsPane: one field for trips and their
/// bookings ("Barcelona 14–18 Nov", "Flight BA478 Sat 14 Nov 07:40 ref ABC123", "Hotel Arts 14–18 Nov"), a row per
/// trip with its dates and status. Click a row to open its day-by-day timeline: each booking with its time (or its
/// kind) and × to remove it, and Remove trip. Under the timeline, TRAVEL DOCUMENTS from the Vault (TripDocRules):
/// each passport and travel card with whether it lasts the trip (lit when not) and Open copy behind Touch ID, offline
/// too, like the Vault's. Completeness slice 4: × and Remove trip say what went with **Undo** for a few seconds, and
/// a trip a week past its end moves under **PAST TRIPS · n** (folded; click to show, newest first, each still opening
/// its timeline). Only Strings cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line under the field cross-fade (a refused line lit in
/// the accent); rows stagger in and arrive or leave on the expand spring; an opened row grows on the expand spring;
/// Add gives a light haptic (a tick when refused), Remove and × a tick; the Undo line slides in and fades, Undo a light
/// haptic; past trips arrive and leave on the expand spring. Reduce Motion: cross-fades.
struct TripsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    /// What the last Add said, while the sheet is open.
    @State private var said: CoreModel.TripSaid?
    /// The row that's open, if any.
    @State private var open: String?
    /// Travel documents: the copy shown, and what the last Open copy said (by document).
    @State private var viewing: ViewedCopy?
    @State private var docSaid: (id: String, said: CoreModel.TripSaid)?
    /// What the last × or Remove trip took, offered back for a few seconds; past trips folded.
    @State private var removed: CoreModel.TripUndo?
    @State private var showPast = false
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

    @ViewBuilder
    private var home: some View {
        let v = model.trips
        let rows = v?.rows ?? []
        let past = v?.past ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Trips").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v?.summary ?? TripRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v?.summary)
                .staggeredAppear(1)
            TextField(TripRules.shared.addHint(hasTrips: !(v?.isEmpty ?? true)), text: $text)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(TripRules.shared.ADD_DESCRIPTION)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task { said = await model.addTrip(typed) }
                }
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(2)
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
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
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if !rows.isEmpty {
                        Text("YOUR TRIPS")
                            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.m)
                            .staggeredAppear(3)
                    }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        TripRowView(
                            row: row, palette: palette, isOpen: open == row.id,
                            docSaid: docSaid,
                            onToggle: { open = open == row.id ? nil : row.id },
                            onOpenCopy: openCopy,
                            onRemoveBooking: removeBooking,
                            onRemove: removeTrip,
                            lists: model.tripLists.first { $0.tripId == row.id }?.summary,
                            onOpenLists: openLists
                        )
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        .staggeredAppear(4 + i)
                    }
                    if !past.isEmpty, let label = v?.pastLabel {
                        Button {
                            MekaHaptics.tick()
                            showPast.toggle()
                        } label: {
                            Text(label + (showPast ? " ▴" : " ▾"))
                                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                .foregroundStyle(palette.textTertiary)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(MekaPressStyle())
                        .padding(.top, MekaSpace.m)
                        .accessibilityLabel(showPast ? "Hide past trips" : "Show \(label.lowercased())")
                        if showPast {
                            ForEach(past, id: \.id) { row in
                                TripRowView(
                                    row: row, palette: palette, isOpen: open == row.id,
                                    docSaid: docSaid,
                                    onToggle: { open = open == row.id ? nil : row.id },
                                    onOpenCopy: openCopy,
                                    onRemoveBooking: removeBooking,
                                    onRemove: removeTrip,
                                    lists: model.tripLists.first { $0.tripId == row.id }?.summary,
                                    onOpenLists: openLists
                                )
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                            }
                        }
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: rows.map(\.id))
                .animation(MekaMotion.expand(reduced: reduceMotion), value: past.map(\.id))
                .animation(MekaMotion.expand(reduced: reduceMotion), value: showPast)
                .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
            }
            .frame(maxHeight: 380)
            Text(TripRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: removed)
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
        open = nil
        Task { if let r = await model.removeTripForUndo(id) { removed = r } }
    }
}

private struct TripRowView: View {
    let row: TripRow
    let palette: MekaPalette
    let isOpen: Bool
    let docSaid: (id: String, said: CoreModel.TripSaid)?
    let onToggle: () -> Void
    let onOpenCopy: (String) -> Void
    let onRemoveBooking: (String) -> Void
    let onRemove: (String) -> Void
    let lists: String?
    let onOpenLists: (String) -> Void

    var body: some View {
        let days = row.timeline.filter { !$0.lines.isEmpty }
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(row.status).font(MekaType.caption).foregroundStyle(row.statusLit ? palette.accent : palette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
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
            if isOpen {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
                    if let docs = row.documents {
                        TripDocsView(docs: docs, palette: palette, docSaid: docSaid, onOpenCopy: onOpenCopy)
                    }
                    Button("Remove trip") { onRemove(row.id) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityLabel("Remove \(row.name) and its bookings")
                }
                .padding(.top, MekaSpace.xs)
                .transition(.opacity)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
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

@preconcurrency import MekaKit
import SwiftUI

/// Trips → a trip → Lists on the Mac (trip lists, slice 1), like the Fold's TripListsPane: the trip's dates and days to
/// go, Bought and Packed bars, To buy · To pack · Before we leave as tabs, one add field ("buy sun cream" → To buy,
/// "pack 2 chargers" → To pack × 2, else the open tab; the tab follows where it went), Everyone / Me / Kids on To pack,
/// and each list's groups with their count, folding on a click. A tick shows at once and is written after; ticked items
/// sink and fade. Right-click → Remove, with Undo for a few seconds. Synced with the Fold.
///
/// Motion: the header and bars stagger in; the bars fill on the appear spring; rows move on the expand spring (a tick
/// sinks the row) and the ring draws its check; groups fold on the expand spring with the chevron turning; the line
/// under the field cross-fades. Reduce Motion: cross-fades.
struct TripListsPanel: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let tripId: String
    let palette: MekaPalette
    let onBack: () -> Void
    @State private var tab: TripList = .pack
    @State private var who: TripWho = .everyone
    @State private var text = ""
    @State private var said: CoreModel.TripListSaid?
    @State private var removed: CoreModel.TripUndo?
    /// What this panel ticked, until the view says the same.
    @State private var pending: [String: Bool] = [:]
    @State private var folded: Set<String> = []

    var body: some View {
        let v = model.tripLists.first { $0.tripId == tripId }
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button("‹ Trips", action: onBack)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .keyboardShortcut(.cancelAction)
                .accessibilityLabel("Back to Trips")
            if let v {
                content(v)
            } else {
                Text(TripListRules.shared.GONE).font(MekaType.body).foregroundStyle(palette.textSecondary)
            }
        }
        .onChange(of: model.tripLists) { _, lists in
            guard let v = lists.first(where: { $0.tripId == tripId }) else { pending = [:]; return }
            var shown: [String: Bool] = [:]
            for t in v.tabs { for g in t.groups { for r in g.rows { shown[r.id] = r.done } } }
            pending = pending.filter { id, done in shown[id] != nil && shown[id] != done }
        }
    }

    @ViewBuilder
    private func content(_ v: TripListsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(v.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
            Text(v.dates).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            Text(v.countdown).font(MekaType.captionStrong).foregroundStyle(v.countdownLit ? palette.accent : palette.textSecondary)
        }
        .staggeredAppear(0)
        HStack(spacing: MekaSpace.m) {
            bar(v.bought)
            bar(v.packed)
        }
        .padding(.top, MekaSpace.xs)
        .staggeredAppear(1)
        HStack(spacing: 0) {
            ForEach(v.tabs, id: \.label) { t in
                let on = t.list == tab
                Button {
                    guard !on else { return }
                    MekaHaptics.tick()
                    tab = t.list
                    said = nil
                } label: {
                    Text(t.label).font(MekaType.captionStrong)
                        .foregroundStyle(on ? palette.accent : palette.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 28)
                        .background(on ? palette.surfaceRaised : Color.clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(t.left > 0 ? "\(t.label), \(t.left) to go" : t.label)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(MekaSpace.xxs)
        .background(palette.background, in: Capsule())
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tab)
        .padding(.top, MekaSpace.xs)
        .staggeredAppear(2)
        TextField(v.tab(list: tab).hint, text: $text)
            .textFieldStyle(.roundedBorder)
            .accessibilityLabel(TripListRules.shared.ADD_DESCRIPTION)
            .onSubmit {
                let typed = text
                let open = tab
                text = ""
                Task {
                    let r = await model.addTripItem(tripId, typed, open: open)
                    said = r
                    if let r, !r.refused { tab = r.list }
                }
            }
        Text(said?.line ?? TripListRules.shared.ADD_NOTE).font(MekaType.caption)
            .foregroundStyle(said?.refused == true ? palette.accent : (said == nil ? palette.textTertiary : palette.textSecondary))
            .fixedSize(horizontal: false, vertical: true)
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
        if let removed {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(removed.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Button("Undo") {
                    let token = removed.token
                    self.removed = nil
                    model.undoTripItemRemoval(token)
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
        if tab == .pack {
            HStack(spacing: MekaSpace.xs) {
                ForEach([TripWho.everyone, TripWho.me, TripWho.kids], id: \.self) { w in
                    let on = w == who
                    Button {
                        MekaHaptics.tick()
                        who = w
                    } label: {
                        Text(w.label).font(MekaType.caption)
                            .foregroundStyle(on ? palette.onAccent : palette.textPrimary)
                            .padding(.horizontal, MekaSpace.m)
                            .padding(.vertical, MekaSpace.xxs)
                            .background(on ? palette.accent : palette.background, in: Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
        let groups = v.groups(list: tab, who: who)
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                if groups.isEmpty {
                    Text(v.tab(list: tab).empty).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.m)
                }
                ForEach(groups, id: \.key) { g in
                    group(g)
                }
                Text(TripListRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.m)
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: pending)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: folded)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: groups.map { g in g.rows.map { "\($0.id)\($0.done)" } })
        }
        .frame(maxHeight: 380)
    }

    private func bar(_ p: TripProgress) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack {
                Text(p.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(p.line).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.hairline)
                    Capsule().fill(palette.accent).frame(width: geo.size.width * CGFloat(p.fraction))
                }
            }
            .frame(height: 4)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: p.fraction)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(p.label) \(p.line)")
    }

    private func isDone(_ r: TripListRow) -> Bool { pending[r.id] ?? r.done }

    @ViewBuilder
    private func group(_ g: TripListGroup) -> some View {
        let open = !folded.contains(g.key)
        let done = g.rows.filter { isDone($0) }.count
        let allDone = !g.rows.isEmpty && done == g.rows.count
        Button {
            MekaHaptics.tick()
            if open { folded.insert(g.key) } else { folded.remove(g.key) }
        } label: {
            HStack(spacing: MekaSpace.xs) {
                Text(g.name.uppercased())
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                    .foregroundStyle(palette.textTertiary)
                Spacer()
                Text(allDone ? "All done" : "\(done) of \(g.rows.count)").font(MekaType.caption)
                    .foregroundStyle(allDone ? palette.success : palette.textTertiary)
                Image(systemName: "chevron.down").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .rotationEffect(.degrees(open ? 180 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .padding(.top, MekaSpace.xs)
        .accessibilityLabel("\(g.name), \(allDone ? "all done" : "\(done) of \(g.rows.count)")")
        if open {
            // A row ticked here sinks at once (a stable sort keeps the rest in their order).
            let rows = g.rows.enumerated().sorted { a, b in
                let da = isDone(a.element), db = isDone(b.element)
                return da != db ? !da : a.offset < b.offset
            }.map(\.element)
            ForEach(rows, id: \.id) { r in
                row(r)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func row(_ r: TripListRow) -> some View {
        let done = isDone(r)
        let id = r.id
        return Button {
            pending[id] = !done
            Task {
                if !(await model.tickTripItem(id, done: !done)) { pending[id] = nil }
            }
        } label: {
            HStack(spacing: MekaSpace.xs) {
                TickRingView(done: done, palette: palette).frame(width: 18, height: 18)
                VStack(alignment: .leading, spacing: 0) {
                    Text(r.text).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                        .strikethrough(done)
                    if !r.sub.isEmpty {
                        Text(r.sub).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                Spacer()
                if !r.qty.isEmpty {
                    Text(r.qty).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            .opacity(done ? 0.5 : 1)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: done)
            .padding(.vertical, MekaSpace.xxs)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(r.text)
        .accessibilityValue(done ? "Ticked" : "Not ticked")
        .contextMenu {
            Button("Remove") {
                Task { if let u = await model.removeTripItem(id) { removed = u } }
            }
        }
    }
}

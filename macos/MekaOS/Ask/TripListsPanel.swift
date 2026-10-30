@preconcurrency import MekaKit
import SwiftUI

/// Trips → a trip → Lists on the Mac (trip lists, slice 1), like the Fold's TripListsPane: the trip's dates and days to
/// go, Bought and Packed bars, To buy · To pack · Before we leave as tabs, one add field ("buy sun cream" → To buy,
/// "pack 2 chargers" → To pack × 2, else the open tab; the tab follows where it went), Everyone / Me / Kids on To pack,
/// and each list's groups with their count, folding on a click. A tick shows at once and is written after; ticked items
/// sink and fade. Right-click → Remove, with Undo for a few seconds. Slice 2b: an empty trip offers "Start from <last
/// trip>", copying its lists unticked, with Undo; slice 2e adds "Save as a template for next time" at the foot and
/// offers saved templates first (right-click → Forget this template). Slice 2d: a click on the header (or "Add who's going and when you
/// leave") opens who's going (chips lit at once, written after) and Leave home at; the dates line counts the people and
/// the countdown says when he leaves. Synced with the Fold. Slice 3b: Obi's suggestions above the field, one card per
/// tab: his line, chips (a click adds that one), Add all and Not now; nothing is added without a click.
///
/// Motion: the header and bars stagger in; the bars fill on the appear spring; rows move on the expand spring (a tick
/// sinks the row) and the ring draws its check; groups fold on the expand spring with the chevron turning; the line
/// under the field cross-fades; a clicked suggestion leaves at once and the card's height follows on the expand spring,
/// the card folding away once empty or put away, "Added 3 things to To pack" fading in with Undo. Reduce Motion: cross-fades.
///
/// Editing slice 4a (Meka 2026-10-26): the groups are his. Right-click a thing → Change… (a popover: name, how many,
/// whose, the group, Remove) or Move to ▸ any group or New group…; right-click a group's head → Rename…, Move up, Move
/// down, Delete group (its things go to Other, with Undo); "+ Group" at the foot adds one. Each change shows at once
/// (the core's TripGroupRules.preview) and is written after; a thing that matched no group offers "Put it in:" for a few
/// seconds. Motion: rows move to their new group on the expand spring; menus and fields fade in.
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
    /// Slice 2b: what "Start from last trip" copied (Undo for a few seconds); `copying` hides the offer at once.
    @State private var copied: CoreModel.TripListCopy?
    @State private var copying = false
    /// What this panel ticked, until the view says the same.
    @State private var pending: [String: Bool] = [:]
    @State private var folded: Set<String> = []
    /// Slice 2d: the who's-going editor, its chips lit at once until the view agrees, and the leave time.
    @State private var editing = false
    @State private var goingPending: [String: Bool] = [:]
    @State private var leaveText = ""
    @State private var leaveSaid: CoreModel.TripLeaveSaid?
    /// Slice 2e: what "Save as a template for next time" said.
    @State private var templateSaid: CoreModel.TripTemplateSaid?
    /// Slice 3b: chips this panel added or put away, hidden at once until the view agrees; what adding said.
    @State private var suggestGone: Set<String> = []
    @State private var suggestSaid: CoreModel.TripListCopy?
    /// Editing slice 4a: what this panel changed and is still writing, drawn before the core's writes come back.
    @State private var moves: [String: String] = [:]
    @State private var renames: [String: String] = [:]
    @State private var deletedGroups: Set<String> = []
    @State private var groupOrder: [String]?
    @State private var addedGroups: [String] = []
    @State private var rowText: [String: String] = [:]
    @State private var rowQty: [String: Int] = [:]
    @State private var rowWho: [String: String] = [:]
    @State private var writing = 0
    @State private var viewTicks = 0
    @State private var groupSaid: CoreModel.TripGroupSaid?
    @State private var renaming: String?
    @State private var renameText = ""
    @State private var addingGroup = false
    @State private var newGroupText = ""
    @State private var sheetFor: String?
    @State private var askFor: CoreModel.TripListSaid?
    /// Slice 4b: the group head a drag is over (lit).
    @State private var dropTarget: String?

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
            viewTicks += 1
            if writing == 0 { clearEdits() }
            guard let v = lists.first(where: { $0.tripId == tripId }) else { pending = [:]; return }
            if v.startFrom == nil { copying = false }
            let lit = Dictionary(uniqueKeysWithValues: v.going.map { ($0.wire, $0.on) })
            goingPending = goingPending.filter { wire, on in lit[wire] != on }
            var shown: [String: Bool] = [:]
            for t in v.tabs { for g in t.groups { for r in g.rows { shown[r.id] = r.done } } }
            pending = pending.filter { id, done in shown[id] != nil && shown[id] != done }
            var offered: Set<String> = []
            for c in v.suggestions { for chip in c.chips { offered.insert(chip.key) } }
            suggestGone = suggestGone.intersection(offered)
        }
    }

    /// Slice 2b/2e: one way to start empty lists ("Start from your Lisbon template", "Start from Porto trip"); a
    /// template's offer has Forget this template on right-click.
    private func startOption(_ from: TripStartFrom) -> some View {
        let templateId = from.templateId
        let source = from.sourceTripId
        return Button {
            copying = true
            Task {
                let r: CoreModel.TripListCopy?
                if let templateId {
                    r = await model.startTripFromTemplate(templateId, to: tripId)
                } else {
                    r = await model.copyTripLists(from: source, to: tripId)
                }
                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { copied = r }
                if r == nil || r?.refused == true { copying = false }
            }
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(from.line).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                Text(from.sub).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(MekaSpace.m)
            .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(from.spoken)
        .contextMenu {
            if let templateId {
                Button(TripListRules.shared.FORGET_TEMPLATE) { model.forgetTripTemplate(templateId) }
            }
        }
        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
    }

    /// Slice 2d: who's going (Me, his partner, the kids) and Leave home at.
    private func goingEditor(_ v: TripListsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(TripListRules.shared.GOING_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(v.going, id: \.wire) { c in
                    let on = goingPending[c.wire] ?? c.on
                    Button {
                        MekaHaptics.light()
                        let wire = c.wire
                        goingPending[wire] = !on
                        Task { if !(await model.setTripGoing(tripId, goer: wire, on: !on)) { goingPending[wire] = nil } }
                    } label: {
                        Text(c.label).font(MekaType.caption).lineLimit(1)
                            .foregroundStyle(on ? palette.onAccent : palette.textPrimary)
                            .padding(.horizontal, MekaSpace.m)
                            .padding(.vertical, MekaSpace.xxs)
                            .background(on ? palette.accent : palette.surfaceRaised, in: Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
                    .accessibilityLabel(c.label)
                    .accessibilityValue(on ? "Going" : "Not going")
                }
            }
            Text(TripListRules.shared.LEAVE_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xxs)
            HStack(spacing: MekaSpace.xs) {
                TextField(v.leave.isEmpty ? TripListRules.shared.LEAVE_HINT : v.leave, text: $leaveText)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 120)
                    .accessibilityLabel(TripListRules.shared.LEAVE_DESCRIPTION)
                    .onSubmit {
                        let typed = leaveText
                        leaveText = ""
                        guard !typed.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                        Task { leaveSaid = await model.setTripLeave(tripId, typed) }
                    }
                if !v.leave.isEmpty {
                    Button("Clear") { Task { leaveSaid = await model.setTripLeave(tripId, "") } }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textSecondary)
                        .accessibilityLabel("Clear the leave time")
                }
                Spacer()
                Button(TripListRules.shared.DONE) {
                    MekaHaptics.tick()
                    leaveSaid = nil
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { editing = false }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
            }
            Text(leaveSaid?.line ?? TripListRules.shared.LEAVE_DESCRIPTION).font(MekaType.caption)
                .foregroundStyle(leaveSaid?.refused == true ? palette.accent : (leaveSaid == nil ? palette.textTertiary : palette.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: leaveSaid)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    @ViewBuilder
    private func content(_ v: TripListsView) -> some View {
        Button {
            MekaHaptics.tick()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { editing.toggle() }
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(v.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                Text(v.dates).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
                Text(v.countdown).font(MekaType.captionStrong).foregroundStyle(v.countdownLit ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                if v.needsSetUp && !editing {
                    Text(TripListRules.shared.SET_UP).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityHint(TripListRules.shared.EDIT)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.dates)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: v.countdown)
        .staggeredAppear(0)
        if editing {
            goingEditor(v)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
        HStack(spacing: MekaSpace.xs) {
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
        suggestCard(v)
        // Beside its mockup (2026-10-30): one pill ending on a gold Add; Return adds too.
        HStack(spacing: MekaSpace.xs) {
            TextField(v.tab(list: tab).hint, text: $text)
                .textFieldStyle(.plain)
                .font(MekaType.itemMeta)
                .accessibilityLabel(TripListRules.shared.ADD_DESCRIPTION)
                .onSubmit { addTyped() }
            Button(TripBesideRules.shared.ADD) { addTyped() }
                .buttonStyle(.plain)
                .font(MekaType.captionStrong)
                .foregroundStyle(palette.onAccent)
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.xxs)
                .background(palette.accent, in: Capsule())
                .accessibilityLabel(TripBesideRules.shared.ADD_DESCRIPTION)
                .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.leading, MekaSpace.m)
        .padding(.trailing, MekaSpace.xxs)
        .padding(.vertical, MekaSpace.xxs)
        .background(palette.background, in: Capsule())
        Text(said?.line ?? TripListRules.shared.ADD_NOTE).font(MekaType.caption)
            .foregroundStyle(said?.refused == true ? palette.accent : (said == nil ? palette.textTertiary : palette.textSecondary))
            .fixedSize(horizontal: false, vertical: true)
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
        // Editing slice 4a: it matched no group, so the groups to put it in, for a few seconds.
        if let askFor, let newId = askFor.id {
            HStack(spacing: MekaSpace.xs) {
                Text(TripGroupRules.shared.PUT_IN).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                ForEach(askFor.askGroups, id: \.self) { name in
                    Button {
                        self.askFor = nil
                        moveItem(newId, to: name)
                    } label: {
                        Text(name).font(MekaType.caption).foregroundStyle(palette.textPrimary).lineLimit(1)
                            .padding(.horizontal, MekaSpace.m)
                            .padding(.vertical, MekaSpace.xxs)
                            .background(palette.surfaceRaised, in: Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel("Put it in \(name)")
                }
            }
            .transition(.opacity)
            .task(id: askFor) {
                try? await Task.sleep(for: .milliseconds(TripGroupRules.shared.ASK_MS))
                if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.askFor = nil } }
            }
        }
        if let groupSaid {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(groupSaid.line).font(MekaType.caption).foregroundStyle(groupSaid.refused ? palette.accent : palette.textSecondary)
                Spacer()
                if !groupSaid.token.isEmpty {
                    Button("Undo") {
                        let token = groupSaid.token
                        self.groupSaid = nil
                        model.undoTripGroupDelete(tripId, token: token)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Undo: \(groupSaid.line)")
                }
            }
            .transition(.opacity)
            .task(id: groupSaid) {
                try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.groupSaid = nil } }
            }
        }
        // Slice 2e: every way to start empty lists: saved templates (newest first), then the last trip.
        if !copying {
            ForEach(v.startOptions, id: \.line) { from in
                startOption(from)
            }
        }
        if let copied {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(copied.line).font(MekaType.caption).foregroundStyle(copied.refused ? palette.accent : palette.textSecondary)
                Spacer()
                if !copied.refused {
                    Button("Undo") {
                        let token = copied.token
                        self.copied = nil
                        copying = false
                        model.undoTripListsCopy(token)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Undo: \(copied.line)")
                }
            }
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            .task(id: copied) {
                try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.copied = nil } }
            }
        }
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
        // Slice 2d: Kids goes from the filters when the kids aren't going (and nothing is theirs).
        let whoShown = v.whoFilters.contains(who) ? who : TripWho.everyone
        if tab == .pack {
            HStack(spacing: MekaSpace.xs) {
                ForEach(v.whoFilters, id: \.self) { w in
                    let on = w == whoShown
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
        let groups = shown(v.groups(list: tab, who: whoShown))
        let everyGroup = shown(v.tab(list: tab).groups)
        let allNames = everyGroup.map(\.name) + (everyGroup.contains { $0.name == TripListRules.shared.ADDED } ? [] : [TripListRules.shared.ADDED])
        let editable = everyGroup.filter(\.editable).map(\.name)
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                if groups.isEmpty {
                    Text(v.tab(list: tab).empty).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.m)
                }
                ForEach(groups, id: \.key) { g in
                    group(g, editable: editable, allNames: allNames)
                }
                // Editing slice 4a: "+ Group" under the groups.
                if addingGroup {
                    TextField(TripGroupRules.shared.ADD_GROUP_HINT, text: $newGroupText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { addGroup(newGroupText, allNames: allNames) }
                        .onExitCommand { addingGroup = false }
                } else {
                    Button(TripGroupRules.shared.ADD_GROUP) { newGroupText = ""; addingGroup = true }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                        .padding(.top, MekaSpace.xs)
                        .accessibilityLabel("Add a group")
                }
                // Slice 2e: "Save as a template for next time" (or "Update the Lisbon template").
                if let save = v.saveTemplate {
                    Button {
                        Task {
                            let r = await model.saveTripTemplate(tripId)
                            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { templateSaid = r }
                        }
                    } label: {
                        Text(save).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.hairline, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .padding(.top, MekaSpace.m)
                    .accessibilityHint("Saves these lists, unticked, for your next trip")
                }
                if let templateSaid {
                    Text(templateSaid.line).font(MekaType.caption)
                        .foregroundStyle(templateSaid.refused ? palette.accent : palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .transition(.opacity)
                        .task(id: templateSaid) {
                            try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                            if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.templateSaid = nil } }
                        }
                }
                Text(TripListRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.m)
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: pending)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: folded)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: groups.map { g in g.name + g.rows.map { "\($0.id)\($0.done)" }.joined() })
        }
        .frame(maxHeight: 380)
    }

    /// Slice 3b: Obi's card for the open tab, and what adding said with Undo.
    @ViewBuilder
    private func suggestCard(_ v: TripListsView) -> some View {
        let card = v.suggestion(list: tab)
        let chips = (card?.chips ?? []).filter { !suggestGone.contains($0.key) }
        if let card, !chips.isEmpty {
            let keys = chips.map { $0.key }
            let list = card.list
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(alignment: .top, spacing: MekaSpace.xs) {
                    Circle()
                        .fill(RadialGradient(colors: [palette.accent.opacity(0.95), palette.accent.opacity(0.35)], center: .topLeading, startRadius: 0, endRadius: 16))
                        .frame(width: 16, height: 16)
                        .accessibilityHidden(true)
                    Text(card.lead).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(TripSuggestRules.shared.TITLE). \(card.lead)")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: MekaSpace.xs, alignment: .leading)], alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(chips, id: \.key) { c in
                        let key = c.key
                        Button { addSuggestions(list, [key]) } label: {
                            Text(c.label).font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(1)
                                .padding(.horizontal, MekaSpace.m)
                                .padding(.vertical, MekaSpace.xxs)
                                .background(palette.surfaceRaised, in: Capsule())
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel("Add \(c.spoken)")
                        .transition(.opacity)
                    }
                }
                HStack(spacing: MekaSpace.xs) {
                    Button { addSuggestions(list, keys) } label: {
                        Text(TripSuggestRules.shared.ADD_ALL).font(MekaType.captionStrong).foregroundStyle(palette.onAccent)
                            .padding(.horizontal, MekaSpace.m)
                            .padding(.vertical, MekaSpace.xxs)
                            .background(palette.accent, in: Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    Button {
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { suggestGone.formUnion(keys) }
                        model.dismissTripSuggestions(tripId, keys: keys)
                    } label: {
                        Text(TripSuggestRules.shared.NOT_NOW).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary)
                            .padding(.horizontal, MekaSpace.m)
                            .padding(.vertical, MekaSpace.xxs)
                            .background(palette.surfaceRaised, in: Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                }
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.accent.opacity(0.28), lineWidth: 1))
            .padding(.top, MekaSpace.xs)
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
        if let suggestSaid {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(suggestSaid.line).font(MekaType.caption).foregroundStyle(suggestSaid.refused ? palette.accent : palette.textSecondary)
                Spacer()
                if !suggestSaid.refused {
                    Button("Undo") {
                        let token = suggestSaid.token
                        self.suggestSaid = nil
                        suggestGone = []
                        model.undoTripListsCopy(token)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Undo: \(suggestSaid.line)")
                }
            }
            .transition(.opacity)
            .task(id: suggestSaid) {
                try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.suggestSaid = nil } }
            }
        }
    }

    /// Slice 3b: adds the chips `keys` (hidden at once); only Strings and the tab cross.
    private func addSuggestions(_ list: TripList, _ keys: [String]) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { suggestGone.formUnion(keys) }
        Task {
            let r = await model.addTripSuggestions(tripId, list: list, keys: keys)
            if r == nil || r?.refused == true { suggestGone.subtract(keys) }
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { suggestSaid = r }
        }
    }

    /// What's typed in the add field goes onto its list (the open tab unless the words say another).
    private func addTyped() {
        let typed = text
        guard !typed.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let open = tab
        text = ""
        Task {
            let r = await model.addTripItem(tripId, typed, open: open)
            said = r
            if let r, !r.refused { tab = r.list }
            askFor = (r?.askGroups.isEmpty == false && r?.id != nil) ? r : nil
        }
    }

    /// Beside its mockup (2026-10-30): a small card, "Bought" · "3 of 9" over a thin bar.
    private func bar(_ p: TripProgress) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Text(p.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(p.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(p.label) \(p.line)")
    }

    private func isDone(_ r: TripListRow) -> Bool { pending[r.id] ?? r.done }

    /// Beside its mockup (2026-10-30): each group is a card, its name in title case, a faint line between rows.
    private func group(_ g: TripListGroup, editable: [String], allNames: [String]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            groupInside(g, editable: editable, allNames: allNames)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xxs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .padding(.top, MekaSpace.xs)
    }

    @ViewBuilder
    private func groupInside(_ g: TripListGroup, editable: [String], allNames: [String]) -> some View {
        let open = !folded.contains(g.key)
        let done = g.rows.filter { isDone($0) }.count
        let allDone = !g.rows.isEmpty && done == g.rows.count
        Button {
            MekaHaptics.tick()
            if open { folded.insert(g.key) } else { folded.remove(g.key) }
        } label: {
            HStack(spacing: MekaSpace.xs) {
                Text(g.name)
                    .font(MekaType.upNextCardTitle)
                    .foregroundStyle(palette.textPrimary)
                Spacer()
                Text(g.rows.isEmpty ? TripGroupRules.shared.EMPTY : (allDone ? "All done" : "\(done) of \(g.rows.count)")).font(MekaType.caption)
                    .foregroundStyle(allDone ? palette.success : palette.textSecondary)
                Image(systemName: "chevron.down").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .rotationEffect(.degrees(open ? 180 : 0))
            }
            .contentShape(Rectangle())
            .padding(.horizontal, MekaSpace.xxs)
            .background(dropTarget == g.name ? palette.accent.opacity(0.18) : Color.clear, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .animation(MekaMotion.appear(reduced: reduceMotion), value: dropTarget)
        }
        .buttonStyle(MekaPressStyle())
        .padding(.vertical, MekaSpace.xs)
        .accessibilityLabel("\(g.name), \(allDone ? "all done" : "\(done) of \(g.rows.count)")")
        // Slice 4b: drag a thing onto a group's head to move it there; drag a head onto another to take its place.
        .modifier(GroupHeadDrag(name: g.name, editable: g.editable))
        .dropDestination(for: String.self) { items, _ in
            drop(items, on: g.name, editable: editable)
        } isTargeted: { on in
            if on { dropTarget = g.name } else if dropTarget == g.name { dropTarget = nil }
        }
        .contextMenu {
            if g.editable {
                let name = g.name
                Button(TripGroupRules.shared.RENAME + "…") { renameText = name; renaming = name }
                if editable.first != name { Button(TripGroupRules.shared.MOVE_UP) { moveGroup(name, by: -1, editable: editable) } }
                if editable.last != name { Button(TripGroupRules.shared.MOVE_DOWN) { moveGroup(name, by: 1, editable: editable) } }
                Divider()
                Button(TripGroupRules.shared.DELETE, role: .destructive) { deleteGroup(name) }
            }
        }
        if renaming == g.name {
            TextField(TripGroupRules.shared.RENAME_HINT, text: $renameText)
                .textFieldStyle(.roundedBorder)
                .onSubmit { renameGroup(g.name, to: renameText) }
                .onExitCommand { renaming = nil }
                .transition(.opacity)
        }
        if open {
            // A row ticked here sinks at once (a stable sort keeps the rest in their order).
            let rows = g.rows.enumerated().sorted { a, b in
                let da = isDone(a.element), db = isDone(b.element)
                return da != db ? !da : a.offset < b.offset
            }.map(\.element)
            ForEach(rows, id: \.id) { r in
                VStack(spacing: 0) {
                    Rectangle().fill(palette.hairline).frame(height: 1)
                    row(r, group: g.name, allNames: allNames)
                }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func row(_ r: TripListRow, group: String, allNames: [String]) -> some View {
        let done = isDone(r)
        let id = r.id
        let text = rowText[id] ?? r.text
        let qty = rowQty[id].map { TripListRules.shared.qtyLabel(qty: KotlinInt(int: Int32($0))) } ?? r.qty
        let sub = rowWho[id] ?? r.sub
        return Button {
            pending[id] = !done
            Task {
                if !(await model.tickTripItem(id, done: !done)) { pending[id] = nil }
            }
        } label: {
            HStack(spacing: MekaSpace.xs) {
                TickRingView(done: done, palette: palette).frame(width: 18, height: 18)
                VStack(alignment: .leading, spacing: 0) {
                    Text(text).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                        .strikethrough(done)
                    if !sub.isEmpty {
                        Text(sub).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                Spacer()
                if !qty.isEmpty {
                    Text(qty).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
        .draggable(id)
        .contextMenu {
            Button("Change…") { sheetFor = id }
            Menu("Move to") {
                ForEach(allNames.filter { $0 != group }, id: \.self) { name in
                    Button(name) { moveItem(id, to: name) }
                }
            }
            Divider()
            Button("Remove") {
                Task { if let u = await model.removeTripItem(id) { removed = u } }
            }
        }
        .popover(isPresented: Binding(get: { sheetFor == id }, set: { if !$0 { sheetFor = nil } })) {
            TripItemSheetMac(
                text: text, qty: rowQty[id] ?? (Int(r.qty.replacingOccurrences(of: "× ", with: "")) ?? 1), who: sub,
                pack: tab == .pack, group: group, groups: allNames, palette: palette,
                onName: { new in
                    rowText[id] = new
                    edit { await model.editTripItem(id, text: new)?.refused == false }
                },
                onQty: { n in
                    rowQty[id] = n
                    edit { await model.editTripItem(id, qty: n)?.refused == false }
                },
                onWho: { w in
                    rowWho[id] = w.label
                    edit { await model.editTripItem(id, who: w)?.refused == false }
                },
                onGroup: { name in moveItem(id, to: name) },
                onRemove: {
                    sheetFor = nil
                    Task { if let u = await model.removeTripItem(id) { removed = u } }
                }
            )
        }
    }

    // MARK: Editing slice 4a

    private func shown(_ groups: [TripListGroup]) -> [TripListGroup] {
        TripGroupRules.shared.preview(
            groups: groups, moves: moves, renames: renames, deleted: deletedGroups, order: groupOrder, added: addedGroups, listWire: tab.wire
        )
    }

    private func clearEdits() {
        moves = [:]; renames = [:]; deletedGroups = []; groupOrder = nil; addedGroups = []
        rowText = [:]; rowQty = [:]; rowWho = [:]
    }

    /// Runs `write` after the panel already shows the change; a refused write, or one the view has caught up with, clears it.
    private func edit(_ write: @escaping @MainActor () async -> Bool) {
        writing += 1
        let at = viewTicks
        Task { @MainActor in
            let ok = await write()
            writing -= 1
            if !ok || (writing == 0 && viewTicks != at) { clearEdits() }
        }
    }

    /// Slice 4b: a drop on the group `group`'s head: a thing moves into it; another group's head takes its place.
    private func drop(_ items: [String], on group: String, editable: [String]) -> Bool {
        dropTarget = nil
        guard let first = items.first else { return false }
        let prefix = "group:"
        if first.hasPrefix(prefix) {
            let name = String(first.dropFirst(prefix.count))
            let next = TripGroupRules.shared.dropOn(names: editable, dragged: name, target: group)
            guard next != editable else { return false }
            MekaHaptics.light()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { groupOrder = next }
            let list = tab
            edit { await model.setTripGroupOrder(tripId, list: list, names: next) }
            return true
        }
        moveItem(first, to: group)
        return true
    }

    private func moveItem(_ id: String, to typed: String) {
        guard let name = TripGroupRules.shared.tidy(typed: typed) else { MekaHaptics.tick(); return }
        MekaHaptics.light()
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { moves[id] = name }
        edit { await model.moveTripItem(id, to: typed)?.refused == false }
    }

    private func renameGroup(_ old: String, to typed: String) {
        renaming = nil
        guard let name = TripGroupRules.shared.tidy(typed: typed) else { MekaHaptics.tick(); return }
        MekaHaptics.light()
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { renames[old] = name }
        let list = tab
        edit {
            let r = await model.renameTripGroup(tripId, list: list, old: old, name: typed)
            if r?.refused == true { groupSaid = r }
            return r?.refused == false
        }
    }

    private func deleteGroup(_ name: String) {
        MekaHaptics.light()
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { _ = deletedGroups.insert(name) }
        let list = tab
        edit {
            let r = await model.deleteTripGroup(tripId, list: list, name: name)
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { groupSaid = r }
            return r?.refused == false
        }
    }

    private func moveGroup(_ name: String, by delta: Int, editable: [String]) {
        guard let at = editable.firstIndex(of: name), editable.indices.contains(at + delta) else { return }
        MekaHaptics.light()
        var next = editable
        next.swapAt(at, at + delta)
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { groupOrder = next }
        let list = tab
        edit { await model.setTripGroupOrder(tripId, list: list, names: next) }
    }

    private func addGroup(_ typed: String, allNames: [String]) {
        addingGroup = false
        guard let name = TripGroupRules.shared.tidy(typed: typed) else { MekaHaptics.tick(); return }
        if allNames.contains(where: { $0.lowercased() == name.lowercased() }) {
            groupSaid = CoreModel.TripGroupSaid(line: TripGroupRules.shared.exists(name: name), refused: true, token: "")
            return
        }
        MekaHaptics.light()
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { addedGroups.append(name) }
        let list = tab
        edit {
            let r = await model.addTripGroup(tripId, list: list, name: typed)
            if r?.refused == true { groupSaid = r }
            return r?.refused == false
        }
    }
}

/// Editing slice 4a: a thing's sheet on the Mac (Change…): its name, how many, whose (To pack), the group, Remove.
private struct TripItemSheetMac: View {
    let text: String
    let qty: Int
    let who: String
    let pack: Bool
    let group: String
    let groups: [String]
    let palette: MekaPalette
    let onName: (String) -> Void
    let onQty: (Int) -> Void
    let onWho: (TripWho) -> Void
    let onGroup: (String) -> Void
    let onRemove: () -> Void
    @State private var name = ""
    @State private var count = 1
    @State private var newGroup = ""

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(TripGroupRules.shared.NAME_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
            TextField(text, text: $name)
                .textFieldStyle(.roundedBorder)
                .onSubmit { if !name.trimmingCharacters(in: .whitespaces).isEmpty { onName(name) } }
            Stepper(value: $count, in: 1...Int(TripListRules.shared.MAX_QTY)) {
                Text("\(TripGroupRules.shared.QTY_LABEL)  \(count)").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            .onChange(of: count) { _, n in if n != qty { onQty(n) } }
            if pack {
                Text(TripGroupRules.shared.WHO_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                HStack(spacing: MekaSpace.xs) {
                    ForEach([TripWho.everyone, TripWho.me, TripWho.kids], id: \.self) { w in
                        Button(w.label) { onWho(w) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.caption)
                            .foregroundStyle(w.label == who ? palette.accent : palette.textPrimary)
                    }
                }
            }
            Text(TripGroupRules.shared.GROUP_LABEL).font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: MekaSpace.xs, alignment: .leading)], alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(groups, id: \.self) { g in
                    Button(g) { if g != group { onGroup(g) } }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(g == group ? palette.accent : palette.textPrimary)
                        .accessibilityAddTraits(g == group ? .isSelected : [])
                }
            }
            TextField(TripGroupRules.shared.NEW_GROUP, text: $newGroup)
                .textFieldStyle(.roundedBorder)
                .onSubmit { if !newGroup.trimmingCharacters(in: .whitespaces).isEmpty { onGroup(newGroup); newGroup = "" } }
            Button(TripGroupRules.shared.REMOVE, role: .destructive, action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
        }
        .padding(MekaSpace.m)
        .frame(width: 300)
        .onAppear { name = text; count = qty }
    }
}

/// Slice 4b: a group's head can be dragged (as "group:<name>") onto another head to reorder; Other can't.
private struct GroupHeadDrag: ViewModifier {
    let name: String
    let editable: Bool

    func body(content: Content) -> some View {
        if editable {
            content.draggable("group:\(name)")
        } else {
            content
        }
    }
}

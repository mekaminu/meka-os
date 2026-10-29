@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Renewals keep their email, slice 2, on the Mac (Meka 2026-10-28, the "Renewals" mockup approved 22:20): the
/// Renewals list as cards. Three tiles, Coming up · All · Done (the lit pill cross-fades), and a card per renewal with
/// its date tile, cost and "cancel in N days" badge. A click unfolds it (opacity + move; Reduce Motion: opacity) into
/// Renews · Cancel by · Repeats tiles, the From the email box and Renewed · Cancelled it · Edit; Edit opens a sheet of
/// rows (menus, a date field and fields: the Mac's simpler layout). Only Strings and numbers cross to the core.
struct RenewalBoardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let board: RenewalBoard
    @Binding var tab: Int
    let palette: MekaPalette
    @Binding var open: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                ForEach(board.tiles, id: \.label) { t in
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(t.label.uppercased()).font(MekaType.cardLabel).tracking(MekaType.cardLabelTracking)
                            .foregroundStyle(palette.textTertiary).lineLimit(1)
                        Text(t.value).font(MekaType.nowTitle).foregroundStyle(t.warn ? palette.accent : palette.textPrimary)
                            .contentTransition(.opacity)
                    }
                    .padding(MekaSpace.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
                    .accessibilityElement(children: .combine)
                }
            }
            HStack(spacing: MekaSpace.xxs) {
                ForEach(0..<Int(RenewalBoardRules.shared.tabCount), id: \.self) { i in
                    let label = RenewalBoardRules.shared.tabAt(index: Int32(i)).label
                    Button {
                        guard i != tab else { return }
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { tab = i; open = nil }
                    } label: {
                        Text(label).font(MekaType.captionStrong)
                            .foregroundStyle(i == tab ? palette.textPrimary : palette.textSecondary)
                            .frame(maxWidth: .infinity).padding(.vertical, MekaSpace.xs)
                            .background(Capsule().fill(i == tab ? palette.surfaceRaised : .clear))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityAddTraits(i == tab ? .isSelected : [])
                }
            }
            .padding(MekaSpace.xxs)
            .background(Capsule().fill(palette.surface))
            if let empty = board.empty {
                Text(empty).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.vertical, MekaSpace.xs)
            }
            ForEach(board.cards, id: \.id) { c in
                RenewalCardView(card: c, palette: palette, open: $open)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: tab)
    }
}

private struct RenewalCardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: RenewalCard
    let palette: MekaPalette
    @Binding var open: String?
    @State private var editing = false

    private var expanded: Bool { open == card.id }

    var body: some View {
        let item = card.item
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button {
                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { open = expanded ? nil : card.id }
            } label: {
                HStack(spacing: MekaSpace.m) {
                    VStack(spacing: 0) {
                        Text(card.month).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                        Text(card.day).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                    }
                    .frame(width: 44, height: 48)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(card.title).font(MekaType.upNextCardTitle).foregroundStyle(palette.textPrimary).lineLimit(2)
                        Text(card.sub).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    VStack(alignment: .trailing, spacing: MekaSpace.xxs) {
                        if let cost = card.cost { Text(cost).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary) }
                        Text(card.badge).font(MekaType.micro)
                            .foregroundStyle(card.warn ? palette.accent : palette.textSecondary)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(Capsule().fill(card.warn ? palette.accent.opacity(0.16) : palette.surfaceRaised))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .combine)
            .accessibilityLabel(card.spoken)
            .accessibilityHint(expanded ? "Hides actions" : "Shows actions")
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(card.tiles, id: \.label) { t in
                            VStack(alignment: .leading, spacing: 0) {
                                Text(t.label.uppercased()).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                                Text(t.value).font(MekaType.metaStrong).foregroundStyle(t.warn ? palette.accent : palette.textPrimary).lineLimit(1)
                            }
                            .padding(MekaSpace.xs)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                        }
                    }
                    if let notes = item.notes { Text(notes).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary) }
                    if let box = item.fromEmail { RenewalEmailBox(id: card.id, box: box, palette: palette) }
                    if card.closed {
                        let id = card.id
                        cardButton(RenewalBoardRules.shared.PUT_BACK, primary: true) { open = nil; model.reopenRenewal(id) }
                    } else {
                        if let link = item.payLink, let url = URL(string: link) {
                            // Bills due: opens the page Meka saved as where he pays it; Navilife never pays.
                            cardButton(BillRules.shared.payLabel(link: link), primary: false) { MekaHaptics.light(); NSWorkspace.shared.open(url) }
                        }
                        HStack(spacing: MekaSpace.xs) {
                            let id = card.id
                            let oneOff = item.repeats == RenewalRules.shared.repeatAt(index: 0)
                            cardButton(item.doneLabel, primary: true) {
                                if oneOff { open = nil } // a one-off leaves the list
                                model.renewalDone(id)
                            }
                            cardButton(item.stopLabel, primary: false) { open = nil; model.stopRenewal(id) }
                            cardButton(RenewalBoardRules.shared.EDIT, primary: false) { editing = true }
                        }
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surface))
        .sheet(isPresented: $editing) {
            RenewalEditSheet(id: card.id, palette: palette) { editing = false } onDelete: {
                let id = card.id
                editing = false; open = nil
                model.deleteRenewal(id)
            }
        }
    }

    private func cardButton(_ label: String, primary: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.captionStrong)
                .foregroundStyle(primary ? palette.onAccent : palette.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: .infinity).padding(.vertical, MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(primary ? palette.accent : palette.surfaceRaised))
                .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// Edit: a sheet with a row per setting (Renews, Repeats, Remind me, Cancel by, Kind, Cost, Pay link), each showing what
/// it is now beside its control. Done closes it; Delete takes it off the radar.
private struct RenewalEditSheet: View {
    @Environment(CoreModel.self) private var model
    let id: String
    let palette: MekaPalette
    let onClose: () -> Void
    let onDelete: () -> Void
    @State private var cost = ""
    @State private var costProblem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let item = model.renewalItem(id) {
                Text("\(RenewalBoardRules.shared.EDIT) \(item.title)").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    .padding(.bottom, MekaSpace.xs)
                ForEach(model.renewalEditRows(item), id: \.label) { row in
                    HStack(spacing: MekaSpace.m) {
                        Text(row.label).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).frame(width: 110, alignment: .leading)
                        control(row.key.name, item: item, value: row.value)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                }
                if let costProblem { Text(costProblem).font(MekaType.caption).foregroundStyle(palette.critical) }
            }
            HStack(spacing: MekaSpace.m) {
                Button(RenewalBoardRules.shared.DELETE, role: .destructive, action: onDelete)
                Spacer(minLength: 0)
                Button(RenewalBoardRules.shared.SAVE, action: onClose).keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.xs)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    @ViewBuilder private func control(_ key: String, item: RenewalItem, value: String) -> some View {
        let id = item.id
        switch key {
        case "DUE":
            DueDateField(day: item.dueDay) { model.setRenewalDue(id, day: $0) }
        case "REPEATS":
            Menu(value) {
                ForEach(RenewalChoices.repeats, id: \.self) { rp in
                    Button(RenewalRules.shared.repeatLabel(r: rp)) { model.setRenewalRepeat(id, rp) }
                }
            }
            .menuStyle(.button).fixedSize()
        case "REMIND":
            Menu(value) {
                ForEach(RenewalRules.shared.LEAD_CHOICES, id: \.label) { c in
                    Button(c.label) { if let d = c.days?.intValue { model.setRenewalLead(id, days: d) } }
                }
            }
            .menuStyle(.button).fixedSize()
        case "CANCEL_BY":
            Menu(value) {
                ForEach(RenewalRules.shared.CANCEL_CHOICES, id: \.label) { c in
                    Button(c.label) { model.setRenewalCancelBy(id, daysBefore: c.days?.intValue) }
                }
            }
            .menuStyle(.button).fixedSize()
        case "KIND":
            Menu(value) {
                ForEach(RenewalChoices.kinds, id: \.self) { k in
                    Button(RenewalRules.shared.kindLabel(k: k)) { model.setRenewalKind(id, k) }
                }
            }
            .menuStyle(.button).fixedSize()
        case "COST":
            TextField(value == "Not set" ? "Cost, e.g. 9.99" : "\(value) (type a new one)", text: $cost)
                .textFieldStyle(.roundedBorder).frame(maxWidth: 220)
                .onSubmit {
                    costProblem = model.setRenewalCost(id, cost)
                    if costProblem == nil { cost = "" }
                }
        case "PAY_LINK":
            PayLinkField(item: item, palette: palette)
        default:
            Text(value).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
        }
    }
}

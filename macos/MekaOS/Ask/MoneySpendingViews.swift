@preconcurrency import MekaKit
import SwiftUI

/// Money redesign (B2) on the Mac, like the Fold's MoneySpendingBlocks: Spending's ring with the month's total in the
/// middle (click a category and the middle shows it, the other slices fade), the three tiles, the category rows (an icon
/// chip in the category's colour, the share, a thin bar against the biggest, the note against usual) and the latest
/// payments by day. Everything it says is the core's `MoneySpendingView`; these views only draw it.
///
/// Motion: the ring sweeps round from the top when it first shows (snaps with Reduce Motion); slices fade and the middle
/// cross-fades as a category is picked; bars grow to their length.

extension MekaPalette {
    /// A `MoneySpending` tone: 0–6 the money palette, the rest (and Other) quiet.
    func moneyTone(_ tone: Int32) -> Color {
        switch tone {
        case 0: return money1
        case 1: return money2
        case 2: return money3
        case 3: return money4
        case 4: return money5
        case 5: return money6
        case 6: return money7
        default: return textTertiary
        }
    }

    func moneyLine(_ tone: MoneyTone) -> Color {
        if tone == MoneyTone.good { return success }
        if tone == MoneyTone.over { return accent }
        return textSecondary
    }
}

/// The nearest system symbol for the mockup's category icons.
func moneySymbol(_ icon: MoneyIcon) -> String {
    switch icon.name {
    case "CART": return "cart"
    case "FORK": return "fork.knife"
    case "CUP": return "cup.and.saucer"
    case "TAKEAWAY": return "takeoutbag.and.cup.and.straw"
    case "TRAIN": return "tram"
    case "FUEL": return "fuelpump"
    case "BILL": return "doc.text"
    case "REPEAT": return "repeat"
    case "BAG": return "bag"
    case "SHIRT": return "tshirt"
    case "HOME": return "house"
    case "HEART": return "heart"
    case "TICKET": return "ticket"
    case "PLANE": return "airplane"
    case "KID": return "figure.and.child.holdinghands"
    case "GIFT": return "gift"
    case "CASH": return "banknote"
    case "PERCENT": return "percent"
    case "IN": return "arrow.up"
    case "MOVE": return "arrow.left.arrow.right"
    default: return "ellipsis"
    }
}

/// A 32 pt rounded chip in the category's colour (13% wash) with its symbol in that colour.
struct MoneyIconChip: View {
    let icon: MoneyIcon
    let tone: Int32
    let palette: MekaPalette

    var body: some View {
        let c = palette.moneyTone(tone)
        RoundedRectangle(cornerRadius: MekaRadius.s, style: .continuous)
            .fill(c.opacity(0.13))
            .frame(width: 32, height: 32)
            .overlay(Image(systemName: moneySymbol(icon)).font(MekaType.caption).foregroundStyle(c))
            .accessibilityHidden(true)
    }
}

/// The ring (200 pt, a 16 pt band) and its middle; a click on the middle lets go of a picked category.
struct MoneyRingView: View {
    let spending: MoneySpendingView
    let focus: String?
    let palette: MekaPalette
    let reduced: Bool
    let onClear: () -> Void
    @State private var sweep: Double = 0

    var body: some View {
        let centre = MoneySpending.shared.focus(v: spending, key: focus)
        let gap = spending.slices.count > 1 ? 0.0035 : 0
        ZStack {
            Circle().stroke(palette.surfaceRaised, lineWidth: 16)
            ForEach(spending.slices, id: \.key) { s in
                let from = Double(s.start) * sweep
                let to = max(from + 0.001, (Double(s.start) + Double(s.sweep)) * sweep - gap)
                Circle()
                    .trim(from: from, to: to)
                    .stroke(palette.moneyTone(s.tone), lineWidth: 16)
                    .rotationEffect(.degrees(-90))
                    .opacity(MoneySpending.shared.lit(s: s, focus: focus, v: spending) ? 1 : 0.25)
            }
            VStack(spacing: MekaSpace.xxs) {
                Text(centre.top).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(centre.big).font(MekaType.greeting).foregroundStyle(palette.textPrimary)
                if let sub = centre.sub {
                    Text(sub).font(MekaType.caption).foregroundStyle(palette.moneyLine(centre.tone))
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, MekaSpace.l)
            .id(centre.top + centre.big)
            .transition(.opacity)
        }
        .frame(width: 200, height: 200)
        .padding(MekaSpace.xs)
        .contentShape(Circle())
        .onTapGesture { if focus != nil { onClear() } }
        .animation(MekaMotion.appear(reduced: reduced), value: focus)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(centre.spoken)
        .onAppear {
            if reduced { sweep = 1 } else { withAnimation(MekaMotion.appear(reduced: false)) { sweep = 1 } }
        }
    }
}

/// Came in · Bills to come · Left in raised tiles.
struct MoneyTilesView: View {
    let tiles: [MoneyTile]
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            ForEach(tiles, id: \.label) { t in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(t.label).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                    Text(t.value).font(MekaType.metaStrong).foregroundStyle(t.lit ? palette.accent : palette.textPrimary).lineLimit(1)
                        .contentTransition(.opacity)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous).fill(palette.surfaceRaised))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// A category row: chip, name and share, amount, the bar in its colour and the note; a click picks it on the ring.
struct MoneySpendRowView: View {
    let row: MoneySpendRow
    let picked: Bool
    let palette: MekaPalette
    let reduced: Bool
    let onPick: () -> Void
    @State private var shown: Double = 0

    var body: some View {
        Button(action: onPick) {
            HStack(spacing: MekaSpace.m) {
                MoneyIconChip(icon: row.icon, tone: row.tone, palette: palette)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack(spacing: MekaSpace.xs) {
                        Text(row.label).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary).lineLimit(1)
                        Text(row.pct).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        Spacer(minLength: 0)
                        Text(row.amount).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                    }
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.surfaceRaised)
                            Capsule().fill(palette.moneyTone(row.tone)).frame(width: g.size.width * min(max(shown, 0), 1))
                        }
                    }
                    .frame(height: 4)
                    Text(row.note).font(MekaType.caption).foregroundStyle(palette.moneyLine(row.noteTone))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, MekaSpace.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(row.spoken)
        .accessibilityAddTraits(picked ? [.isSelected] : [])
        .onAppear { if reduced { shown = Double(row.bar) } else { withAnimation(MekaMotion.appear(reduced: false)) { shown = Double(row.bar) } } }
        .onChange(of: row.bar) { _, b in withAnimation(reduced ? nil : MekaMotion.appear(reduced: false)) { shown = Double(b) } }
    }
}

/// A section's head in caps with an accent link on the right ("All 10 ›", "See all 42 ›").
struct MoneyHeadWithLink: View {
    let head: String
    let link: String?
    let palette: MekaPalette
    let onLink: () -> Void

    var body: some View {
        HStack {
            Text(head).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Spacer()
            if let l = link {
                Button(l, action: onLink).buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
            }
        }
        .padding(.top, MekaSpace.xs)
    }
}

/// A day's line over its payments: "Today" and the day's money out.
struct MoneyDayHead: View {
    let day: MoneyPayDay
    let palette: MekaPalette

    var body: some View {
        HStack {
            Text(day.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Spacer()
            if let t = day.total { Text(t).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
        }
        .padding(.top, MekaSpace.xs)
    }
}

/// Money's layout rule (a) (Meka 2026-10-26 22:18): a section is a card, rounded and raised on the sheet, 16 pt in.
extension View {
    func moneyCard(_ palette: MekaPalette) -> some View {
        self.padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous))
    }
}

/// Money's top as the mockup (core MoneyHeader): the Updated pill on the title row, a refresh glyph and "Updated 06:10"
/// on the raised surface; a click asks the banks for a sync (the words cross-fade to "Updating…" and back; reduced motion
/// swaps them). VoiceOver hears "Updated 06:10, refresh".
struct MoneyUpdatedPill: View {
    let text: String
    let palette: MekaPalette
    let reduced: Bool
    let onRefresh: () -> Void

    var body: some View {
        Button(action: onRefresh) {
            HStack(spacing: MekaSpace.xxs) {
                Image(systemName: "arrow.clockwise").font(MekaType.caption)
                Text(text).font(MekaType.caption).lineLimit(1).contentTransition(.opacity)
            }
            .foregroundStyle(palette.textSecondary)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surfaceRaised, in: Capsule())
        }
        .buttonStyle(MekaPressStyle())
        .animation(MekaMotion.appear(reduced: reduced), value: text)
        .accessibilityLabel(MoneyHeader.shared.spoken(pill: text))
    }
}

@preconcurrency import MekaKit
import SwiftUI

// The board's shared pieces used by the Day-list spread and Calendar → Week. The old week board that first held them
// (WeekBoardScreen) was removed 2026-11-15 (Meka's answer to Needs Meka #54: the Day-list board stays as it is).

struct RoundStep: View {
    let systemName: String
    let palette: MekaPalette

    var body: some View {
        let size = CGFloat(BoardDenseHeadRules.shared.BUTTON)
        Image(systemName: systemName).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
            .frame(width: size, height: size)
            .background(Circle().fill(palette.surface))
            .overlay(Circle().strokeBorder(palette.hairline, lineWidth: 1))
            .contentShape(Circle())
    }
}

extension MekaPalette {
    /// A Board card's area dot (Option B slice 3, the approved mockup's colours): Family the warm orange, Work the blue,
    /// Side hustle the brass, Money the green, House the violet.
    func areaTone(_ area: BoardArea) -> Color {
        if area == BoardArea.family { return calendar3 }
        if area == BoardArea.work { return calendar4 }
        if area == BoardArea.sideHustle { return accent }
        if area == BoardArea.money { return success }
        return calendar2
    }
}


/// Option B match: the mockup's sizes. The open Fold's alignment pass (Meka 2026-10-29 02:12 (e)): one typeface, as the
/// folded layout, no serif or mono; times and figures keep even widths with monospaced digits.
enum BoardFont {
    /// The density pass (BoardDenseRules): times, lengths and the budget's figures at the mockup's 10.5 in mono.
    static var mono: Font { .system(size: CGFloat(BoardDenseRules.shared.CHIP_SP), design: .default).monospacedDigit() } // type: ok (the density pass: the mockup's sizes)
    /// A day's date, 19 (was the now-title token).
    static var date: Font { .system(size: CGFloat(BoardDenseRules.shared.DATE_SP), weight: .semibold) } // type: ok (the density pass: the mockup's sizes)
    /// The weekday beside it, 11.
    static var weekday: Font { .system(size: CGFloat(BoardDenseRules.shared.WEEKDAY_SP)) } // type: ok (the density pass: the mockup's sizes)
    /// A card's title, 13.5.
    static var title: Font { .system(size: CGFloat(BoardDenseRules.shared.TITLE_SP)) } // type: ok (the density pass: the mockup's sizes)
    /// The area's name in a card's foot, 11.
    static var foot: Font { .system(size: CGFloat(BoardDenseRules.shared.FOOT_SP)) } // type: ok (the density pass: the mockup's sizes)
    /// Density pass slice 2 (BoardDenseHeadRules): "October 2026", 28 semibold.
    static var month: Font { .system(size: CGFloat(BoardDenseHeadRules.shared.MONTH_SP), weight: .semibold) } // type: ok (density pass slice 2: the mockup's header)
    /// "W44" beside it, 12 mono.
    static var weekTag: Font { .system(size: CGFloat(BoardDenseHeadRules.shared.WEEK_SP), design: .default).monospacedDigit() } // type: ok (density pass slice 2: the mockup's header)
    /// Today, Hide timeline, Review, Plan your week and Obi's name, 13.
    static var label: Font { .system(size: CGFloat(BoardDenseHeadRules.shared.LABEL_SP)) } // type: ok (density pass slice 2: the mockup's header)
    /// Obi's line and a goal's title in the header, 12.
    static var nudge: Font { .system(size: CGFloat(BoardDenseHeadRules.shared.NUDGE_SP)) } // type: ok (density pass slice 2: the mockup's header)
    /// The lists' headings under the weekend (Someday, Side hustle…), 16 semibold (BoardBesideRules).
    static var listHead: Font { .system(size: CGFloat(BoardBesideRules.shared.LIST_HEAD_SP), weight: .semibold) } // type: ok (the shots beside the mockups: the mockup's list heading)
    /// "+ add", 11.
    static var add: Font { .system(size: CGFloat(BoardDenseHeadRules.shared.ADD_SP)) } // type: ok (density pass slice 2: the mockup's "+ add")
}

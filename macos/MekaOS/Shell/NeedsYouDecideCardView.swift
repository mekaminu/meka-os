import SwiftUI
@preconcurrency import MekaKit

/// A TO DECIDE card in the approved Needs you look on the Mac (`docs/design/needs-you/needs-you.dc.html`; the Fold's
/// NeedsYouDecideShell): a 34 pt icon tile, what it is over who it's from (one line, cut at the end), then the card's
/// own yes · change · no pills. The words and taps come from the core (NeedsYouDecideRules); nothing here sends or adds
/// anything. Pills press in (MekaPressStyle); the card folds away with its list's expand spring and the undo bar rises.
/// Hover lifts it a little; reduced motion keeps only the cross-fades. VoiceOver reads the card's one sentence.
/// Mac slice 3: an appointment's quieter second row (Call to book · Copy email, `card.more`) and its When? field
/// (`below`, shown inside the card under the one line); an approval's yes carries the Touch ID mark (`yesSymbol`).
struct NeedsYouDecideCardView: View {
    let card: NeedsYouDecideCard
    let palette: MekaPalette
    let onYes: () -> Void
    let onNo: () -> Void
    var onChange: () -> Void = {}
    /// The quieter row's taps, by their index in `card.more`.
    var onMore: (Int) -> Void = { _ in }
    /// The system symbol drawn before the yes pill's words (Touch ID on an approval).
    var yesSymbol: String? = nil
    /// The yes pill's extra words for VoiceOver ("with Touch ID").
    var yesSpokenSuffix: String = ""
    /// Something shown inside the card under its one line (an appointment's When? field once Booked… is clicked).
    var below: AnyView? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: card.details.isEmpty ? .center : .top, spacing: MekaSpace.xs) {
                Image(systemName: Self.symbol(card.icon))
                    .font(MekaType.itemMeta)
                    .foregroundStyle(Self.ink(card.icon, palette))
                    .frame(width: 34, height: 34)
                    .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(card.what).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary).lineLimit(2)
                    Text(card.from).font(MekaType.caption)
                        .foregroundStyle(card.urgent ? palette.critical : palette.textSecondary)
                        .lineLimit(1).truncationMode(.tail)
                    // An approval's detail lines (what would happen) stay whole, never cut.
                    ForEach(Array(card.details.enumerated()), id: \.offset) { _, line in
                        Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(card.spoken)
            if let below { below.padding(.leading, 34 + MekaSpace.xs) }
            HStack(spacing: MekaSpace.xs) {
                pill(card.yesLabel, tone: .filled, symbol: yesSymbol, spokenSuffix: yesSpokenSuffix, action: onYes)
                if let change = card.changeLabel { pill(change, tone: .plain, action: onChange) }
                pill(card.noLabel, tone: .quiet, action: onNo)
            }
            if !card.more.isEmpty {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(Array(card.more.enumerated()), id: \.offset) { i, label in
                        pill(label, tone: .quiet) { onMore(i) }
                    }
                }
            }
            if let note = card.note {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .mekaHoverLift()
    }

    enum Tone { case filled, plain, quiet }

    private func pill(_ label: String, tone: Tone, symbol: String? = nil, spokenSuffix: String = "",
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if let symbol { Label(label, systemImage: symbol) } else { Text(label) }
        }
            .buttonStyle(MekaPressStyle())
            .font(tone == .filled ? MekaType.captionStrong : MekaType.caption)
            .foregroundStyle(tone == .filled ? palette.onAccent : (tone == .plain ? palette.textPrimary : palette.textSecondary))
            .padding(.horizontal, tone == .quiet ? MekaSpace.xs : MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(tone == .filled ? palette.accent : (tone == .plain ? palette.surfaceRaised : Color.clear), in: Capsule())
            .accessibilityLabel("\(label): \(card.what)\(spokenSuffix)")
    }

    /// The nearest system symbol for each of the mockup's stroke icons (the Fold draws the core's paths).
    static func symbol(_ icon: NeedsYouDecideIcon) -> String {
        switch icon.name {
        case "HOME": return "house"
        case "TASK": return "checkmark"
        case "EVENT": return "calendar"
        case "REMINDER": return "bell"
        case "RENEWAL": return "arrow.triangle.2.circlepath"
        case "TRIP": return "airplane"
        case "IDEA": return "lightbulb"
        case "REPLY": return "bubble.left"
        case "SCHOOL": return "graduationcap"
        case "NIGHT": return "moon"
        case "APPOINTMENT": return "clock"
        case "TRADE": return "chart.line.uptrend.xyaxis"
        case "PAYMENT": return "creditcard"
        case "SHARE": return "doc.text"
        default: return "circle"
        }
    }

    /// Each icon's ink, as on the Fold.
    static func ink(_ icon: NeedsYouDecideIcon, _ palette: MekaPalette) -> Color {
        switch icon.name {
        case "HOME", "SCHOOL": return palette.success
        case "TASK", "IDEA", "NIGHT": return palette.accent
        case "EVENT", "TRIP", "APPOINTMENT", "SHARE": return palette.calendar1
        case "REMINDER", "RENEWAL", "TRADE", "PAYMENT": return palette.approval
        default: return palette.textSecondary
        }
    }
}

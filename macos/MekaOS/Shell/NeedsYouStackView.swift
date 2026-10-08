import SwiftUI
@preconcurrency import MekaKit

/// Needs you as a stack of decisions (four tabs, slice 2), Mac. The top card drags like the Fold's (→ yes/do,
/// ← later, ↑ open; the label pops at the threshold with a tick), and the buttons under it and the ← → ↑ keys do the
/// same (Return opens). Done, Tomorrow and Later fly the card off the way it went; Open and Choose spring it back and
/// select the task beside the stack. Two cards peek out behind. Reduced motion: no tilt or flight, cross-fades.
struct NeedsYouStackView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The Needs you tab takes the keyboard on appear; the command centre beside Today doesn't (click a card first).
    var autofocus: Bool = true

    @State private var drag: CGSize = .zero
    @State private var armed: DecisionMove?
    @State private var gone: Set<String> = []
    @State private var busy = false
    @FocusState private var focused: Bool

    private let thresholdX: CGFloat = 110
    private let thresholdY: CGFloat = 100

    var body: some View {
        let cards = model.needsYouCards
        let shown = cards.filter { !gone.contains($0.id) }
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let top = shown.first {
                ZStack(alignment: .top) {
                    let behind = Array(shown.dropFirst().prefix(2))
                    ForEach(Array(behind.enumerated().reversed()), id: \.element.id) { i, card in
                        let depth = CGFloat(i + 1)
                        DecisionCardFace(card: card, armed: nil, progress: 0, palette: palette)
                            .scaleEffect(1 - 0.05 * depth, anchor: .top)
                            .offset(y: 12 * depth)
                            .opacity(1 - 0.3 * Double(depth))
                            .allowsHitTesting(false)
                    }
                    DecisionCardFace(card: top, armed: armed ?? lean, progress: progress, palette: palette)
                        .offset(drag)
                        .rotationEffect(.degrees(reduceMotion ? 0 : Double(drag.width / 40)))
                        .gesture(dragGesture(top))
                        .id(top.id)
                        .transition(.opacity)
                        .accessibilityAction(named: Text(top.yesLabel)) { perform(top, .yes) }
                        .accessibilityAction(named: Text(top.laterLabel)) { perform(top, .later) }
                        .accessibilityAction(named: Text(top.openLabel)) { perform(top, .open) }
                }
                .padding(.bottom, 24)
                .animation(MekaMotion.approve(reduced: reduceMotion), value: shown.map(\.id))

                HStack(spacing: MekaSpace.s) {
                    Button("← \(top.laterLabel)") { perform(top, .later) }
                        .help("Left arrow")
                    Button("↑ \(top.openLabel)") { perform(top, .open) }
                        .help("Up arrow or Return")
                    Spacer()
                    Button("\(top.yesLabel) →") { perform(top, .yes) }
                        .buttonStyle(.borderedProminent)
                        .tint(palette.accent)
                        .help("Right arrow")
                }
                .controlSize(.large)

                if let more = NeedsYouStackRules.shared.moreLine(shown: Int32(shown.count)) {
                    Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
            }
        }
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onAppear { if autofocus { focused = true } }
        .onKeyPress(.rightArrow) { key(.yes, shown.first) }
        .onKeyPress(.leftArrow) { key(.later, shown.first) }
        .onKeyPress(.upArrow) { key(.open, shown.first) }
        .onKeyPress(.return) { key(.open, shown.first) }
        .onChange(of: cards.map(\.id)) { _, ids in gone = gone.filter { ids.contains($0) } }
    }

    private func key(_ move: DecisionMove, _ card: DecisionCard?) -> KeyPress.Result {
        guard let card else { return .ignored }
        perform(card, move)
        return .handled
    }

    /// How far the card is towards its threshold, 0…1.
    private var progress: Double {
        Double(min(1, max(abs(drag.width) / thresholdX, -drag.height / thresholdY)))
    }

    /// Which move the card leans to before it's armed.
    private var lean: DecisionMove? {
        if drag.height < 0 && -drag.height > abs(drag.width) { return .open }
        if drag.width > 0 { return .yes }
        if drag.width < 0 { return .later }
        return nil
    }

    private func armedFor(_ t: CGSize) -> DecisionMove? {
        if -t.height >= thresholdY && -t.height > abs(t.width) { return .open }
        if t.width >= thresholdX { return .yes }
        if t.width <= -thresholdX { return .later }
        return nil
    }

    private func dragGesture(_ card: DecisionCard) -> some Gesture {
        DragGesture(minimumDistance: 4)
            .onChanged { v in
                guard !busy else { return }
                drag = CGSize(width: v.translation.width, height: min(v.translation.height, thresholdY * 0.3))
                let now = armedFor(drag)
                if now != armed {
                    if now != nil { MekaHaptics.tick() }
                    armed = now
                }
            }
            .onEnded { _ in
                if let move = armed { perform(card, move) } else {
                    withAnimation(MekaMotion.complete(reduced: reduceMotion)) { drag = .zero }
                }
            }
    }

    /// Flies the card off when it leaves the stack (Done, Tomorrow, Later), else springs it back, then tells the model.
    private func perform(_ card: DecisionCard, _ move: DecisionMove) {
        guard !busy else { return }
        let effect = card.effect(move: move)
        let leaves = effect == .completeTask || effect == .snoozeTask || effect == .setAside
        let id = card.id
        if leaves {
            busy = true
            let width: CGFloat = 700
            let to = CGSize(width: move == .later ? -width : move == .yes ? width : drag.width, height: drag.height + 40)
            withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .easeIn(duration: 0.2)) {
                if reduceMotion { gone.insert(id) } else { drag = to }
            } completion: {
                if effect != .setAside { gone.insert(id) } else { gone.remove(id) }
                drag = .zero
                armed = nil
                busy = false
                model.decide(card, move, reduced: reduceMotion)
            }
        } else {
            withAnimation(MekaMotion.complete(reduced: reduceMotion)) { drag = .zero }
            armed = nil
            model.decide(card, move, reduced: reduceMotion)
        }
    }
}

/// One card: why (lit when urgent), the title, the hint; the label of the move it leans to fades in and pops.
private struct DecisionCardFace: View {
    let card: DecisionCard
    let armed: DecisionMove?
    let progress: Double
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text(card.why).font(MekaType.itemMeta).foregroundStyle(card.urgent ? palette.critical : palette.accent)
            Text(card.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary).lineLimit(3)
            Text(NeedsYouStackRules.shared.hint(card: card)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: 180, alignment: .topLeading)
        .padding(MekaSpace.l)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l))
        .overlay(alignment: alignment) {
            if let armed, progress > 0 {
                let yes = armed == .yes
                Text(card.label(move: armed))
                    .font(MekaType.itemTitle)
                    .foregroundStyle(yes ? palette.onAccent : palette.textPrimary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(yes ? palette.accent : palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                    .scaleEffect(progress >= 1 && !reduceMotion ? 1.15 : 1)
                    .opacity(progress)
                    .padding(MekaSpace.m)
                    .animation(MekaMotion.approve(reduced: reduceMotion), value: progress >= 1)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var alignment: Alignment {
        switch armed {
        case .yes: .topTrailing
        case .later: .topLeading
        case .open: .bottom
        default: .center
        }
    }
}

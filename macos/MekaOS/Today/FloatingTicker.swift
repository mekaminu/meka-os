@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// The Mac's floating ticker (build plan M1, news ticker slice 2b): View → Floating Ticker (off by default) puts the
/// news ticker's cards in a thin always-on-top strip at the top or bottom of the screen, over every app. Drag it by its
/// grip and let go: it settles onto the nearer edge, keeping where it was across the screen. It always drifts, hover
/// holds it, clicking a story brings MEKA forward with the News sheet on that story (the match opens its detail).
/// It never shows in full-screen apps (the panel doesn't join full-screen spaces) and hides while there is nothing to
/// show. The choice and the placement stay on this Mac (`FloatingTickerRules` in core decides the frame and the edge).
@MainActor
final class FloatingTicker {
    static let shared = FloatingTicker()

    static let enabledKey = "meka.floatingTicker"
    static let edgeKey = "meka.floatingTicker.edge"
    static let centreKey = "meka.floatingTicker.centre"

    /// Opens MEKA's main window when it was closed; set by a view that lives as long as the app (the menu-bar label).
    var openMainWindow: (() -> Void)?

    private weak var model: CoreModel?
    private var panel: FloatingTickerPanel?
    private var dragStart: TickerRect?
    private var screenObserver: NSObjectProtocol?

    var enabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    /// Called once the model exists; shows the strip if it was left on.
    func attach(_ model: CoreModel) {
        guard self.model !== model else { return }
        self.model = model
        observe()
        screenObserver = NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil,
                                               queue: .main) { _ in
            MainActor.assumeIsolated { FloatingTicker.shared.settle(animated: false) }
        }
        refresh()
    }

    /// View → Floating Ticker, and the switch in Ask → More → Appearance.
    func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: Self.enabledKey)
        MekaHaptics.tick()
        refresh()
    }

    /// Re-reads what there is to show whenever the News place changes.
    private func observe() {
        guard let model else { return }
        withObservationTracking {
            _ = model.newsPlace
        } onChange: {
            Task { @MainActor in
                FloatingTicker.shared.refresh()
                FloatingTicker.shared.observe()
            }
        }
    }

    /// Turned on and something to show (`FloatingTickerRules.shown`).
    private var wanted: Bool {
        let on = enabled
        return model?.newsPlace.map {
            FloatingTickerRules.shared.shown(enabled: on, ticker: TickerRules.shared.ticker(place: $0))
        } ?? false
    }

    /// Shows or hides the panel: turned on and something to show (`FloatingTickerRules.shown`).
    func refresh() {
        if wanted, let model {
            show(model)
        } else {
            hide()
        }
    }

    private func show(_ model: CoreModel) {
        let reduced = MotionSetting.reduced
        if panel == nil {
            let p = FloatingTickerPanel()
            let root = FloatingTickerView(
                open: { [weak self] card in self?.open(card) },
                dragChanged: { [weak self] in self?.dragChanged() },
                dragEnded: { [weak self] in self?.dragEnded() }
            ).environment(model).mekaMotion()
            p.contentView = NSHostingView(rootView: root)
            panel = p
        }
        guard let panel, !panel.isVisible else { return }
        settle(animated: false)
        // Fades in (no movement, so Reduce Motion looks the same, only quicker).
        panel.alphaValue = 0
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = reduced ? 0.12 : 0.24
            panel.animator().alphaValue = 1
        }
    }

    private func hide() {
        guard let panel, panel.isVisible else { return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.18
            panel.animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated { FloatingTicker.shared.finishHide() }
        })
    }

    /// After the fade: off the screen, unless it was turned on again meanwhile.
    private func finishHide() {
        guard let panel else { return }
        if !wanted { panel.orderOut(nil) }
        panel.alphaValue = 1
    }

    // MARK: Placement

    private var placement: FloatingPlacement {
        let d = UserDefaults.standard
        return FloatingTickerRules.shared.placement(
            edgeId: d.string(forKey: Self.edgeKey),
            centre: d.object(forKey: Self.centreKey) == nil ? Double.nan : d.double(forKey: Self.centreKey)
        )
    }

    private func visibleRect(_ screen: NSScreen?) -> TickerRect? {
        guard let s = screen ?? NSScreen.main else { return nil }
        let v = s.visibleFrame
        return TickerRect(x: v.minX, y: v.minY, width: v.width, height: v.height)
    }

    /// Puts the panel on its edge (after a drag it springs there; Reduce Motion: it is placed at once).
    func settle(animated: Bool) {
        guard let panel, let visible = visibleRect(panel.screen) else { return }
        let f = FloatingTickerRules.shared.frame(visible: visible, placement: placement)
        let rect = NSRect(x: f.x, y: f.y, width: f.width, height: f.height)
        if animated && !MotionSetting.reduced {
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.32
                ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.9, 0.3, 1.0)
                panel.animator().setFrame(rect, display: true)
            }
        } else {
            panel.setFrame(rect, display: true)
        }
    }

    /// The grip follows the pointer (screen coordinates, so the moving window doesn't feed back into the drag).
    private func dragChanged() {
        guard let panel, let visible = visibleRect(panel.screen) else { return }
        let mouse = NSEvent.mouseLocation
        if dragStart == nil {
            let f = panel.frame
            dragStart = TickerRect(x: f.minX - mouse.x, y: f.minY - mouse.y, width: f.width, height: f.height)
        }
        guard let start = dragStart else { return }
        let origin = TickerRect(x: mouse.x + start.x, y: mouse.y + start.y, width: start.width, height: start.height)
        let f = FloatingTickerRules.shared.dragged(start: origin, dx: 0, dy: 0, visible: visible)
        panel.setFrameOrigin(NSPoint(x: f.x, y: f.y))
    }

    /// Let go: the nearer edge, the same place across; remembered on this Mac.
    private func dragEnded() {
        dragStart = nil
        guard let panel, let visible = visibleRect(panel.screen) else { return }
        let f = panel.frame
        let p = FloatingTickerRules.shared.dropped(
            visible: visible, panel: TickerRect(x: f.minX, y: f.minY, width: f.width, height: f.height))
        UserDefaults.standard.set(p.edge.id, forKey: Self.edgeKey)
        UserDefaults.standard.set(p.centre, forKey: Self.centreKey)
        MekaHaptics.light()
        settle(animated: true)
    }

    // MARK: Opening a story

    private func open(_ card: TickerCardItem) {
        guard let model else { return }
        MekaHaptics.tick()
        NSApp.activate()
        if !NSApp.windows.contains(where: { $0.isVisible && $0.canBecomeMain }) { openMainWindow?() }
        switch card {
        case .match(let m): model.openEvent = m.event
        case .story(let s):
            model.newsStoryId = s.id
            model.showNews = true
        }
    }
}

/// A borderless, non-activating strip above other windows: clicking it doesn't take the focus from the app in front,
/// it follows you across desktops, and it stays out of full-screen apps (no `.fullScreenAuxiliary`).
final class FloatingTickerPanel: NSPanel {
    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: FloatingTickerRules.shared.MAX_WIDTH,
                                       height: FloatingTickerRules.shared.HEIGHT),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        isFloatingPanel = true
        level = .floating
        collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenNone]
        hidesOnDeactivate = false
        isMovable = false // the grip moves it, so a click on a card never drags the strip
        backgroundColor = .clear
        isOpaque = false
        hasShadow = true
        isReleasedWhenClosed = false
        becomesKeyOnlyIfNeeded = true
        title = "MEKA news ticker"
        setAccessibilityLabel("MEKA news ticker")
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

/// What the panel shows: a grip, then the drifting cards (Reduce Motion: one still card with ‹ › paging), on a raised
/// rounded surface in MEKA's palette, following the app's appearance (colours blend on a change).
struct FloatingTickerView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var systemScheme
    @AppStorage(MekaAppearance.key) private var appearance = MekaAppearance.dark.rawValue
    let open: (TickerCardItem) -> Void
    let dragChanged: () -> Void
    let dragEnded: () -> Void
    @State private var gripHover = false

    var body: some View {
        let scheme = (MekaAppearance(rawValue: appearance) ?? .dark).scheme ?? systemScheme
        let palette: MekaPalette = scheme == .dark ? .dark : .light
        HStack(spacing: MekaSpace.xs) {
            Image(systemName: "line.3.horizontal")
                .rotationEffect(.degrees(90))
                .font(.caption)
                .foregroundStyle(gripHover ? palette.accent : palette.textTertiary)
                .frame(width: 18, height: 44)
                .contentShape(Rectangle())
                .onHover { inside in
                    gripHover = inside
                    if inside { NSCursor.openHand.push() } else { NSCursor.pop() }
                }
                .gesture(
                    DragGesture(minimumDistance: 2, coordinateSpace: .global)
                        .onChanged { _ in dragChanged() }
                        .onEnded { _ in dragEnded() }
                )
                .accessibilityLabel("Move the news ticker")
                .accessibilityHint("Drag to the top or bottom of the screen")
            if let place = model.newsPlace {
                let cards = TickerCardItem.cards(TickerRules.shared.ticker(place: place))
                if reduceMotion {
                    StillTicker(cards: cards, palette: palette, open: open)
                } else {
                    DriftingTicker(cards: cards, mode: FloatingTickerRules.shared.MODE, palette: palette, open: open)
                }
            }
        }
        .padding(.leading, MekaSpace.xs)
        .padding(.trailing, MekaSpace.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.l).strokeBorder(palette.hairline, lineWidth: 1))
        .environment(\.colorScheme, scheme)
        .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: appearance)
    }
}

@preconcurrency import MekaKit
import SwiftUI

/// Mac presentation pass, slice 1 (Meka, 2026-10-15: "I'm not a fan of scroll-bar pagination"): no scroll bars, and a
/// soft fade at a scrolling column's edge instead, so it's clear there's more. The top fades (8 pt) once the column
/// has scrolled, the foot (16 pt) while more lies below (core MacCommandCentreRules.edgeFades). The fades cross in and
/// out with the appear curve; Reduce Motion switches them.
private struct EdgeFadesModifier: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var fades = Fades(top: false, bottom: false)

    private nonisolated struct Fades: Equatable { var top: Bool; var bottom: Bool }
    // Built in the scroll-geometry transform, which may run off the main actor.
    private nonisolated struct Geo: Equatable { var offset: CGFloat; var content: CGFloat; var viewport: CGFloat }

    func body(content: Content) -> some View {
        content
            .scrollIndicators(.never)
            // Only plain numbers leave the scroll geometry; the core rule runs on the main actor in the action.
            .onScrollGeometryChange(for: Geo.self) { g in
                Geo(offset: g.contentOffset.y + g.contentInsets.top, content: g.contentSize.height,
                    viewport: g.containerSize.height - g.contentInsets.top - g.contentInsets.bottom)
            } action: { _, g in
                let f = MacCommandCentreRules.shared.edgeFades(
                    offsetPt: Float(g.offset), contentHeightPt: Float(g.content), viewportHeightPt: Float(g.viewport))
                let now = Fades(top: f.top, bottom: f.bottom)
                guard now != fades else { return }
                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { fades = now }
            }
            .mask {
                VStack(spacing: 0) {
                    LinearGradient(colors: [.black.opacity(fades.top ? 0 : 1), .black], startPoint: .top, endPoint: .bottom)
                        .frame(height: CGFloat(MacCommandCentreRules.shared.TOP_FADE_PT))
                    Rectangle()
                    LinearGradient(colors: [.black, .black.opacity(fades.bottom ? 0 : 1)], startPoint: .top, endPoint: .bottom)
                        .frame(height: CGFloat(MacCommandCentreRules.shared.BOTTOM_FADE_PT))
                }
            }
    }
}

extension View {
    /// A scrolling column with no scroll bar and soft fades at the edges with more beyond them.
    func edgeFades() -> some View { modifier(EdgeFadesModifier()) }
}

/// A column's heading that stays put while the column scrolls under it (Mac presentation pass, item 2), sitting
/// below the title bar; a new count cross-fades in.
struct StickyColumnHeader: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let text: String
    let palette: MekaPalette

    init(_ text: String, palette: MekaPalette) {
        self.text = text
        self.palette = palette
    }

    var body: some View {
        SectionLabel(text, palette)
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: text)
            .padding(.horizontal, MekaSpace.gutterWide)
            .padding(.top, MekaSpace.m)
            .padding(.bottom, MekaSpace.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.background)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Mac presentation pass, item 9 (core MacCommandCentreRules.entrance): with Today's opening, a column beside Today
/// fades up and slides in from the right one stagger step after the column before it; its cards stagger in under it.
/// Outside the opening it is simply there (and its cards don't replay), so a Close or a return cross-fades it whole.
/// Reduce Motion: a cross-fade, no slide.
private struct ColumnEntranceModifier: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.mekaExpressiveMotion) private var expressive
    let step: Int
    let slide: CGFloat
    let play: Bool
    @State private var shown = false

    func body(content: Content) -> some View {
        let visible = shown || !play
        content
            .environment(\.mekaStaggerPlays, play)
            .opacity(visible ? 1 : 0)
            .offset(x: visible || reduceMotion ? 0 : slide)
            .onAppear {
                guard play, !shown else { return }
                let delay = MotionMath.staggerDelay(index: step, reduced: reduceMotion, expressive: expressive)
                withAnimation(MekaMotion.appear(reduced: reduceMotion, expressive: expressive).delay(delay)) {
                    shown = true
                }
            }
    }
}

extension View {
    /// How a Today column enters (plain values from core `ColumnEntrance`: its step, slide and whether it staggers).
    func columnEntrance(step: Int, slide: CGFloat, play: Bool) -> some View {
        modifier(ColumnEntranceModifier(step: step, slide: slide, play: play))
    }
}

extension AnyTransition {
    /// A column arriving or leaving as the window crosses a breakpoint: it slides `slide` pt from the right as it
    /// fades; Reduce Motion (slide 0): a cross-fade.
    static func column(slide: CGFloat) -> AnyTransition {
        slide == 0 ? .opacity : .opacity.combined(with: .offset(x: slide))
    }
}

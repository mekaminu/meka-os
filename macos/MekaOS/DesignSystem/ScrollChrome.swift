@preconcurrency import MekaKit
import SwiftUI

/// Mac presentation pass, slice 1 (Meka, 2026-10-15: "I'm not a fan of scroll-bar pagination"): no scroll bars, and a
/// soft fade at a scrolling column's edge instead, so it's clear there's more. The top fades (8 pt) once the column
/// has scrolled, the foot (16 pt) while more lies below (core MacCommandCentreRules.edgeFades). The fades cross in and
/// out with the appear curve; Reduce Motion switches them.
private struct EdgeFadesModifier: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var fades = Fades(top: false, bottom: false)

    private struct Fades: Equatable { var top: Bool; var bottom: Bool }
    private struct Geo: Equatable { var offset: CGFloat; var content: CGFloat; var viewport: CGFloat }

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

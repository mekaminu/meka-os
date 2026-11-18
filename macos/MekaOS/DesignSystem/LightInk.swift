import SwiftUI

/// Walk-through 2026-11-08, A31 (Light contrast): the quiet inks that read in Dark but fade on Light's off-white page.
/// Dark keeps its approved look; Light gets the next ink up (the Fold's `LightInk`, measured in LightInkTest).
extension MekaPalette {
    /// Small grey words (a caption's "34%"): tertiary is 3.9:1 on Light's page, under the 4.5:1 small text needs.
    func smallInk(dark: Bool) -> Color { dark ? textTertiary : textSecondary }
    /// A thin bar's empty track: surfaceRaised is 1.1:1 on Light's page, so Light uses the `track` token.
    func barTrack(dark: Bool) -> Color { dark ? surfaceRaised : track }
    /// A tile or card filled with a surface on Light's near-same page (1.1:1) gets a 1 pt edge in the `track` ink;
    /// none in Dark.
    func pillEdge(dark: Bool) -> Color? { dark ? nil : track }
}

/// Walk-through 2026-11-15, A21 part 2: a card on Light's near-same sheet gets a 1 pt edge in the track ink (the Fold's
/// `Modifier.lightCardEdge`); none in Dark.
struct LightCardEdge: ViewModifier {
    let palette: MekaPalette
    var radius: CGFloat = MekaRadius.m
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content.overlay {
            if let edge = palette.pillEdge(dark: scheme == .dark) {
                RoundedRectangle(cornerRadius: radius).strokeBorder(edge, lineWidth: 1)
            }
        }
    }
}

extension View {
    func lightCardEdge(_ palette: MekaPalette, radius: CGFloat = MekaRadius.m) -> some View {
        modifier(LightCardEdge(palette: palette, radius: radius))
    }
}

import SwiftUI

/// Walk-through 2026-11-08, A31 (Light contrast): the quiet inks that read in Dark but fade on Light's off-white page.
/// Dark keeps its approved look; Light gets the next ink up (the Fold's `LightInk`, measured in LightInkTest).
extension MekaPalette {
    /// Small grey words (a caption's "34%"): tertiary is 3.9:1 on Light's page, under the 4.5:1 small text needs.
    func smallInk(dark: Bool) -> Color { dark ? textTertiary : textSecondary }
    /// A thin bar's empty track: surfaceRaised is 1.1:1 on Light's page, so Light uses the `track` token.
    func barTrack(dark: Bool) -> Color { dark ? surfaceRaised : track }
}

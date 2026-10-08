@preconcurrency import MekaKit
import SwiftUI

/// The voice orb on the Mac (catalogue "Talk to MEKA"), number for number the Fold's: a brass ring with a microphone
/// that breathes while idle or thinking (the empty states' breath), swells with Meka's voice while listening
/// (`TalkOrb.scale`, up to 1.22×) with a brass fill, and sends three rings rippling out while MEKA speaks
/// (`TalkOrb.ripple`, 1.4 s each). Reduced motion: a still ring with a level bar under it. Drawing only; the caller
/// gives it its click and VoiceOver label.
struct VoiceOrbView: View {
    let phase: TalkPhase
    let level: Float
    let palette: MekaPalette
    var size: CGFloat = 76
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var began = Date()

    private var lit: Bool { phase == .listening || phase == .speaking }
    private var moving: Bool { !reduceMotion && phase != .ended }

    var body: some View {
        VStack(spacing: 6) {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !moving)) { context in
                let elapsed = context.date.timeIntervalSince(began)
                let breath = MotionMath.breath(elapsed: elapsed, reduced: reduceMotion)
                let shownLevel = phase == .listening ? level : 0
                let scale = reduceMotion ? 1 : CGFloat(TalkOrb.shared.scale(phase: phase, level: shownLevel, breath: Float(breath)))
                let swell = CGFloat(TalkOrb.shared.SWELL)
                let base = size / (1 + swell) // room to swell and ripple inside the frame
                let glow = phase == .listening ? 0.35 + 0.65 * Double(shownLevel) : MotionMath.breathGlow(breath)
                ZStack {
                    if phase == .speaking && !reduceMotion {
                        ForEach(0..<Int(TalkOrb.shared.RIPPLES), id: \.self) { i in
                            let p = CGFloat(TalkOrb.shared.ripple(i: Int32(i), elapsedMs: Int64(elapsed * 1000)))
                            Circle()
                                .stroke(palette.accent.opacity(0.45 * (1 - p)), lineWidth: 2 * (1 - 0.5 * p))
                                .frame(width: base * (1 + swell * p), height: base * (1 + swell * p))
                        }
                    }
                    Group {
                        Circle().fill(palette.accent.opacity(0.18 * glow))
                        if lit { Circle().fill(palette.accent.opacity(0.9)).padding(2) }
                        Circle().strokeBorder(palette.accent.opacity(0.6 + 0.4 * glow), lineWidth: 2)
                        Image(systemName: "mic.fill")
                            .font(.system(size: base * 0.34, weight: .medium))
                            .foregroundStyle(lit ? palette.onAccent : palette.accent)
                    }
                    .frame(width: base, height: base)
                    .scaleEffect(scale)
                }
                .frame(width: size, height: size)
            }
            if reduceMotion && phase == .listening {
                // Reduced motion: the voice level as a still bar instead of the swell.
                Capsule().fill(palette.surfaceRaised)
                    .frame(width: size, height: 3)
                    .overlay(alignment: .leading) {
                        Capsule().fill(palette.accent).frame(width: size * CGFloat(min(max(level, 0), 1)), height: 3)
                    }
            }
        }
        .onAppear { began = Date() }
        .accessibilityHidden(true)
    }
}

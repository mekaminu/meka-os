@preconcurrency import MekaKit
import SwiftUI

/// The Hello orb on the Mac ("Hello, Navilife"; motion catalogue "Hello, Navilife"), number for number the Fold's: a
/// brass orb with a soft glowing core. Idle it breathes; while something needs him a faint warm halo breathes round it
/// (`HelloOrb.pulse`); listening it swells with his voice; thinking two arcs turn round the rim (`HelloOrb.swirl`);
/// speaking rings ripple out (`TalkOrb.ripple`). One Canvas, redrawn only while it moves. Reduced motion: a still glow
/// (the halo held), with a level bar under it while listening. Drawing only; the caller gives it its click and label.
struct HelloOrbView: View {
    let state: OrbState
    let level: Float
    let palette: MekaPalette
    var size: CGFloat = CGFloat(HelloOrb.shared.SIZE)
    /// Frames a second while it moves: the sidebar's and the menu bar's small orbs breathe at a calmer rate (slice 2).
    var frameRate: Double = 30
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var began = Date()

    var body: some View {
        VStack(spacing: MekaSpace.xs) {
            TimelineView(.animation(minimumInterval: 1.0 / frameRate, paused: reduceMotion)) { context in
                let elapsedMs = Int64(context.date.timeIntervalSince(began) * 1000)
                Canvas { g, box in draw(g, box: box, elapsedMs: elapsedMs) }
                    .frame(width: size, height: size)
            }
            if reduceMotion && state == .listening {
                // Reduced motion: the voice level as a still bar instead of the swell.
                Capsule().fill(palette.surfaceRaised)
                    .frame(width: size / 2, height: 3)
                    .overlay(alignment: .leading) {
                        Capsule().fill(palette.accent).frame(width: size / 2 * CGFloat(min(max(level, 0), 1)), height: 3)
                    }
            }
        }
        .onAppear { began = Date() }
        .accessibilityHidden(true)
    }

    private func draw(_ g: GraphicsContext, box: CGSize, elapsedMs: Int64) {
        let orb = HelloOrb.shared
        let talkOrb = TalkOrb.shared
        let c = CGPoint(x: box.width / 2, y: box.height / 2)
        let breath = MotionMath.breath(elapsed: Double(elapsedMs) / 1000, reduced: reduceMotion)
        let shownLevel: Float = state == .listening ? level : 0
        let scale = reduceMotion ? 1 : CGFloat(orb.scale(state: state, level: shownLevel, breath: Float(breath)))
        let swell = CGFloat(talkOrb.SWELL)
        let base = min(box.width, box.height) / 2 / (1 + swell) // room to swell and ripple inside the frame
        let r = base * scale
        let ring: CGFloat = 2
        func circle(_ radius: CGFloat) -> Path {
            Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: radius * 2, height: radius * 2))
        }
        func a(_ x: Double) -> Color { palette.accent.opacity(min(max(x, 0), 1)) }
        // Needs you: a faint warm halo breathing outside the rim.
        if state == .needsYou {
            let p = Double(orb.pulse(elapsedMs: elapsedMs, reduced: reduceMotion))
            let outer = base * (1 + swell)
            g.fill(circle(outer), with: .radialGradient(Gradient(colors: [a(0.22 * (0.4 + 0.6 * p)), .clear]),
                                                         center: c, startRadius: 0, endRadius: outer))
        }
        // Speaking: rings ripple out from the rim.
        if state == .speaking && !reduceMotion {
            for i in 0..<Int(talkOrb.RIPPLES) {
                let p = CGFloat(talkOrb.ripple(i: Int32(i), elapsedMs: elapsedMs))
                g.stroke(circle(base * (1 + swell * p)), with: .color(a(0.5 * Double(1 - p))), lineWidth: ring * (1 - 0.5 * p))
            }
        }
        // The core: a glow brighter at the centre, lit while he talks or it speaks.
        let lit = state == .listening || state == .speaking
        let glow: Double = switch state {
        case .listening: 0.45 + 0.55 * Double(shownLevel)
        case .speaking: 0.85
        default: MotionMath.breathGlow(breath)
        }
        g.fill(circle(r), with: .radialGradient(Gradient(colors: [a(lit ? 0.75 : 0.35 * glow + 0.15), a(0.06)]),
                                                center: c, startRadius: 0, endRadius: r))
        g.stroke(circle(r - ring / 2), with: .color(a(0.6 + 0.4 * glow)), lineWidth: ring)
        // A fine inner hairline for depth.
        g.stroke(circle(r * 0.72), with: .color(a(0.25)), lineWidth: ring / 2)
        // Thinking: two arcs turning round the rim.
        if state == .thinking {
            let turn = Double(orb.swirl(elapsedMs: elapsedMs, reduced: reduceMotion)) * 360
            let arcR = r + ring * 2.5
            for k in 0..<2 {
                var arc = Path()
                let start = turn + 180 * Double(k) - 90
                arc.addArc(center: c, radius: arcR, startAngle: .degrees(start),
                           endAngle: .degrees(start + Double(orb.ARC_DEG)), clockwise: false)
                g.stroke(arc, with: .color(a(0.9)), style: StrokeStyle(lineWidth: ring * 1.5, lineCap: .round))
            }
        }
    }
}

/// "Hello, Navilife" slice 2: the Hello orb at the foot of the sidebar (and in the menu bar's window): a small breathing
/// orb, warm while Needs you has cards, with "Say hello" and the ⌥Space hint beside it; a click brings Ask forward and
/// greets him (`CoreModel.requestHello`), then listens. Press style; Reduce Motion: a still glow.
struct SayHelloButton: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    /// Called after the request (the menu bar's window brings the main window forward with it).
    var then: () -> Void = {}

    var body: some View {
        let count = model.needsYouCount
        let line = HelloOrb.shared.restingLine(needsYou: Int32(count), mac: true)
        Button {
            MekaHaptics.light()
            model.requestHello(reduced: MotionSetting.reduced)
            then()
        } label: {
            HStack(spacing: MekaSpace.xs) {
                HelloOrbView(state: HelloOrb.shared.state(phase: .ended, needsYou: Int32(count)), level: 0, palette: palette,
                             size: CGFloat(HelloOrb.shared.SIDEBAR_SIZE), frameRate: 12)
                VStack(alignment: .leading, spacing: 0) {
                    Text("Say hello").font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(count > 0 ? line : "⌥Space").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .help(line)
        .accessibilityLabel("Navilife · \(line)")
        .accessibilityIdentifier("say-hello")
    }
}

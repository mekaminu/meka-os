@preconcurrency import MekaKit
import SwiftUI

/// The Hello orb on the Mac ("Hello, Navilife"; motion catalogue "Hello, Navilife"), drawn as the **gold particle
/// constellation** (Meka 2026-10-19 19:19), number for number the Fold's (`Constellation` works every frame out): ~360
/// points on a slowly turning sphere joined to their nearest neighbours by faint lines, depth-shaded, a soft gold core
/// and drifting dust, with additive blending in Dark. Idle it turns and breathes; listening the points shiver with his
/// voice; thinking it tightens and spins, the lines brighten and three tilted orbit rings carry bright comets; speaking
/// the points push out, the dust streams, a gold ring lights round the equator and a soft shockwave goes out on each
/// beat; needs-you a warm halo and two amber comets. One Canvas, redrawn only while it moves. Reduced motion: the
/// constellation held still with a calm glow, and a level bar under it while listening. Drawing only; the caller gives
/// it its click and label.
struct HelloOrbView: View {
    let state: OrbState
    let level: Float
    let palette: MekaPalette
    var size: CGFloat
    /// Frames a second while it moves: the sidebar's and the menu bar's small orbs turn at a calmer rate (slice 2).
    var frameRate: Double
    /// Slice 7, the entrance: when it began; the points scatter to the edge of the orb's box, gather and send one shockwave.
    var entranceAt: Date?
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @State private var began = Date()
    @State private var orb: OrbConstellation

    init(state: OrbState, level: Float, palette: MekaPalette, size: CGFloat = CGFloat(HelloOrb.shared.SIZE), frameRate: Double = 30,
         entranceAt: Date? = nil) {
        self.entranceAt = entranceAt
        self.state = state
        self.level = level
        self.palette = palette
        self.size = size
        self.frameRate = frameRate
        _orb = State(initialValue: OrbConstellation(size: Int32(size)))
    }

    var body: some View {
        VStack(spacing: MekaSpace.xs) {
            TimelineView(.animation(minimumInterval: 1.0 / frameRate, paused: reduceMotion)) { context in
                let elapsedMs = Int64(context.date.timeIntervalSince(began) * 1000)
                let entranceMs: Int64 = entranceAt.map { Int64(context.date.timeIntervalSince($0) * 1000) } ?? -1
                Canvas { g, box in draw(&g, box: box, elapsedMs: elapsedMs, entranceMs: entranceMs) }
                    .frame(width: size, height: size)
            }
            if reduceMotion && state == .listening {
                // Reduced motion: the voice level as a still bar instead of the shiver.
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

    private func draw(_ g: inout GraphicsContext, box: CGSize, elapsedMs: Int64, entranceMs: Int64) {
        let k = Constellation.shared
        let dark = colorScheme == .dark
        if dark { g.blendMode = .plusLighter }
        let accent = palette.accent
        let amber = palette.calendar3
        // Dark: a brighter gold for the near points and the comets (the watch face's bright brass); Light: the accent.
        let bright = dark ? Color(.sRGB, red: 0.925, green: 0.843, blue: 0.620, opacity: 1) : accent
        let shownLevel: Float = (state == .listening || state == .speaking) ? level : 0
        orb.advance(elapsedMs: elapsedMs, state: state, reduced: reduceMotion)
        // Slice 7: during the entrance the points are flung out to the box's edge and gather again (none afterwards).
        let entering = entranceMs >= 0 && entranceMs < HelloEntrance.shared.BIG_MS && !reduceMotion
        let spread: Float = entering ? HelloEntrance.shared.spread(kind: .big, t: entranceMs, reduced: false) : 0
        k.frame(geo: orb.geo, out: orb.frame, state: state, level: shownLevel, angle: orb.angle, elapsedMs: elapsedMs, reduced: reduceMotion,
                spread: spread, reach: k.EXTENT * 0.97)
        let f = orb.frame
        let n = Int(orb.geo.n)
        let c = CGPoint(x: box.width / 2, y: box.height / 2)
        let half = min(box.width, box.height) / 2
        let r = half / CGFloat(k.EXTENT)
        let hair: CGFloat = n <= Int(k.POINTS_SMALL) ? 0.5 : 0.6
        func gold(_ x: Double) -> Color { accent.opacity(min(max(x, 0), 1)) }
        func circle(_ p: CGPoint, _ radius: CGFloat) -> Path {
            Path(ellipseIn: CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2))
        }
        func at(_ x: Float, _ y: Float) -> CGPoint { CGPoint(x: c.x + CGFloat(x) * r, y: c.y + CGFloat(y) * r) }
        func ringPoint(_ phi: Float, _ squash: Float, _ theta: Float, _ radius: Float) -> CGPoint {
            let p = k.ringPoint(phi: phi, squash: squash, theta: theta, radius: radius)
            return at(p.first?.floatValue ?? 0, p.second?.floatValue ?? 0)
        }
        func ellipse(_ phi: Float, _ squash: Float, _ radius: Float) -> Path {
            var path = Path()
            for s in 0...48 {
                let p = ringPoint(phi, squash, Float(2 * Double.pi * Double(s) / 48), radius)
                if s == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            return path
        }
        func comet(_ phi: Float, _ squash: Float, _ radius: Float, _ theta: Float, _ color: Color, _ head: CGFloat) {
            for t in 0..<9 { // the head, then a fading tail behind it
                let fade = 1 - Double(t) / 9
                g.fill(circle(ringPoint(phi, squash, theta - Float(t) * 0.07, radius), head * CGFloat(0.45 + 0.55 * fade)),
                       with: .color(color.opacity(0.95 * fade * fade)))
            }
        }
        func pair(_ p: KotlinPair<KotlinFloat, KotlinFloat>) -> (Float, Float) { (p.first?.floatValue ?? 0, p.second?.floatValue ?? 0) }
        // Needs you: a warm amber halo breathing outside the sphere.
        if state == .needsYou {
            let p = Double(HelloOrb.shared.pulse(elapsedMs: elapsedMs, reduced: reduceMotion))
            g.fill(circle(c, half), with: .radialGradient(Gradient(colors: [amber.opacity(0.20 * (0.4 + 0.6 * p)), .clear]),
                                                          center: c, startRadius: 0, endRadius: half))
        }
        // The core: a soft gold glow, brighter at the centre.
        let core = Double(f.core)
        g.fill(circle(c, r * 0.9), with: .radialGradient(Gradient(colors: [bright.opacity(0.55 * core), gold(0.18 * core), .clear]),
                                                         center: c, startRadius: 0, endRadius: r * 0.9))
        // Speaking: the soft shockwave on the beat.
        if let shock = k.shock(state: state, elapsedMs: elapsedMs, reduced: reduceMotion) {
            let (reach, a) = pair(shock)
            g.stroke(circle(c, r * CGFloat(reach)), with: .color(gold(Double(a))), lineWidth: hair * 3 * CGFloat(1 - (reach - 1)))
        }
        // The entrance: one gold shockwave as the points re-form.
        if entering, let wave = HelloEntrance.shared.shock(kind: .big, t: entranceMs, reduced: false) {
            let (reach, a) = pair(wave)
            g.stroke(circle(c, min(r * CGFloat(reach), half)), with: .color(bright.opacity(Double(a))), lineWidth: hair * 3)
        }
        // The lines, in four bands of brightness (one path each).
        var bands = [Path](repeating: Path(), count: 4)
        let links = orb.geo.links
        for i in 0..<Int(orb.geo.linkCount) {
            let a = links.get(index: Int32(2 * i)), b = links.get(index: Int32(2 * i + 1))
            let la = k.linkAlpha(depthA: f.depth.get(index: a), depthB: f.depth.get(index: b), boost: f.linkBoost)
            if la < 0.02 { continue }
            let band = min(max(Int(la / 0.15), 0), 3)
            bands[band].move(to: at(f.px.get(index: a), f.py.get(index: a)))
            bands[band].addLine(to: at(f.px.get(index: b), f.py.get(index: b)))
        }
        for (i, path) in bands.enumerated() { g.stroke(path, with: .color(gold(0.075 + 0.15 * Double(i))), lineWidth: hair) }
        // The points; the nearest catch the bright gold.
        for i in 0..<n {
            let idx = Int32(i)
            let d = f.depth.get(index: idx)
            g.fill(circle(at(f.px.get(index: idx), f.py.get(index: idx)), CGFloat(k.dotRadius(depth: d, n: Int32(n))) * r),
                   with: .color((d > 0.7 ? bright : accent).opacity(Double(f.alpha.get(index: idx)))))
        }
        // The dust drifting out.
        for i in 0..<Int(f.dustN) {
            let idx = Int32(i)
            g.fill(circle(at(f.dx.get(index: idx), f.dy.get(index: idx)), hair * 1.2), with: .color(gold(Double(f.da.get(index: idx)))))
        }
        // Thinking: three tilted orbit rings, each with a bright comet.
        if state == .thinking {
            for (i, ring) in k.RINGS.enumerated() {
                let (phi, squash) = pair(ring)
                g.stroke(ellipse(phi, squash, k.RING_R), with: .color(gold(0.22)), lineWidth: hair)
                if !reduceMotion {
                    comet(phi, squash, k.RING_R, k.comet(k: Int32(i), elapsedMs: elapsedMs, lapMs: k.COMET_MS), bright, hair * 3.2)
                }
            }
        }
        // Speaking: a voice-reactive gold ring round the equator.
        if state == .speaking {
            let (w, a) = pair(k.equator(amp: f.amp))
            g.stroke(ellipse(0, k.EQUATOR_SQUASH, k.EQUATOR_R), with: .color(gold(Double(a))), lineWidth: hair * CGFloat(w) * 1.6)
        }
        // Needs you: two amber comets on faint rings.
        if state == .needsYou {
            for (i, ring) in k.AMBER_RINGS.enumerated() {
                let (phi, squash) = pair(ring)
                g.stroke(ellipse(phi, squash, k.AMBER_R), with: .color(amber.opacity(0.12)), lineWidth: hair)
                let theta = reduceMotion ? Float(i) * Float.pi : k.comet(k: Int32(i), elapsedMs: elapsedMs, lapMs: k.AMBER_COMET_MS)
                comet(phi, squash, k.AMBER_R, theta, amber, hair * 2.6)
            }
        }
    }
}

/// The constellation's sphere and its last frame for one orb, kept across the timeline's frames (the turn's angle
/// carries over, so a change of state speeds the turn up or down without a jump). Main actor only: the Kotlin objects
/// it holds never leave it.
@MainActor
final class OrbConstellation {
    let geo: ConstellationGeometry
    let frame: ConstellationFrame
    private(set) var angle: Float = 0.6
    private var lastMs: Int64 = -1

    init(size: Int32) {
        let k = Constellation.shared
        let n = k.points(sizeDp: size, mac: true)
        geo = k.geometry(n: n)
        frame = ConstellationFrame(n: n, dustN: k.dust(sizeDp: size))
    }

    func advance(elapsedMs: Int64, state: OrbState, reduced: Bool) {
        let dt = lastMs < 0 || elapsedMs < lastMs ? 0 : elapsedMs - lastMs
        lastMs = elapsedMs
        angle = Constellation.shared.advance(angle: angle, dtMs: dt, state: state, reduced: reduced)
    }
}
/// "Hello, Navilife" slice 2: the Hello orb at the foot of the sidebar (and in the menu bar's window): a small breathing
/// orb, warm while Needs you has cards, with "Say hello" and the ⌥Space hint beside it; a click brings Ask forward and
/// greets him (`CoreModel.requestHello`), then listens. Press style; Reduce Motion: a still glow.
struct SayHelloButton: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private let wake = MacHelloWake.shared
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
                    // Slice 3: while it listens for the phrase, a brass dot and the line say so (the visible indicator).
                    if wake.listening {
                        HStack(spacing: MekaSpace.xxs) {
                            Circle().fill(palette.accent).frame(width: 5, height: 5)
                            Text(HelloWakeRules.shared.LISTENING_LINE).font(MekaType.caption)
                                .foregroundStyle(palette.textTertiary).lineLimit(1)
                        }
                        .transition(.opacity)
                    } else {
                        Text(count > 0 ? line : "⌥Space").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .lineLimit(1)
                            .transition(.opacity)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wake.listening)
        .help(line)
        .accessibilityLabel(wake.listening ? "Navilife · \(line) · \(HelloWakeRules.shared.LISTENING_LINE)" : "Navilife · \(line)")
        .accessibilityIdentifier("say-hello")
    }
}

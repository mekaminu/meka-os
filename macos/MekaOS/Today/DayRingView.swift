@preconcurrency import MekaKit
import SwiftUI

/// The opening moment's Day ring (motion pass 2, slice 7; catalogue "Opening moment"), the Mac twin of the Fold's
/// `DayRingHero`: a 24-hour dial at the top of Today with midnight at the top. The brass mark (the track) draws itself
/// round, the day's events, planned tasks and booked sessions draw in as brass arcs (free time stays dark, finished
/// things dimmer), the now needle sweeps from midnight to now, and the centre counts up to "3 h 45 free" · "4 to do".
/// `play` is decided as Today appears (`DayRingOpen.claim`); `played` runs once the opening has landed so it isn't
/// replayed while Today stays up. VoiceOver reads one line.
struct DayRingView: View {
    let ring: DayRing
    let play: DayRingPlayback
    let played: () -> Void
    let palette: MekaPalette
    var size: CGFloat = 184
    @Environment(\.mekaExpressiveMotion) private var expressive
    @State private var began = Date()

    var body: some View {
        let total = MotionMath.dayRingTotal(arcs: ring.arcs.count, play: play, expressive: expressive)
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: play == .still)) { context in
            let elapsed = play == .still ? total : context.date.timeIntervalSince(began)
            dial(elapsed: elapsed)
        }
        .frame(width: size, height: size)
        .frame(maxWidth: .infinity)
        .padding(.vertical, MekaSpace.s)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ring.spokenLine)
        .onAppear { began = Date() }
        .task(id: play) {
            guard play != .still else { return }
            began = Date()
            try? await Task.sleep(for: .seconds(total))
            played()
        }
    }

    private func dial(elapsed: Double) -> some View {
        let mark = MotionMath.dayRingMark(elapsed: elapsed, play: play)
        let needle = MotionMath.dayRingNeedle(elapsed: elapsed, play: play)
        let count = MotionMath.dayRingCount(elapsed: elapsed, play: play, expressive: expressive)
        let free = MotionMath.countUpValue(from: 0, to: Int(ring.freeMinutes), fraction: count)
        let toDo = MotionMath.countUpValue(from: 0, to: Int(ring.toDo), fraction: count)
        let arcs = ring.arcs
        return ZStack {
            Canvas { ctx, canvasSize in
                let stroke: CGFloat = 10
                let radius = min(canvasSize.width, canvasSize.height) / 2 - stroke / 2 - 2
                let c = CGPoint(x: canvasSize.width / 2, y: canvasSize.height / 2)
                func point(_ degrees: Double, _ r: CGFloat) -> CGPoint {
                    let a = (degrees - 90) * .pi / 180
                    return CGPoint(x: c.x + cos(a) * r, y: c.y + sin(a) * r)
                }
                func arcPath(from: Double, sweep: Double) -> Path {
                    var p = Path()
                    p.addArc(center: c, radius: radius, startAngle: .degrees(from - 90), endAngle: .degrees(from - 90 + sweep), clockwise: false)
                    return p
                }
                // The mark: the dial's track, drawing itself round from the top.
                if mark > 0 {
                    ctx.stroke(arcPath(from: 0, sweep: 360 * mark), with: .color(palette.textTertiary.opacity(0.28)), lineWidth: 1.5)
                }
                // Quarter ticks (00 · 06 · 12 · 18) once the mark has passed them.
                for q in 0..<4 where mark >= Double(q) / 4 {
                    var tick = Path()
                    tick.move(to: point(Double(q) * 90, radius - 4))
                    tick.addLine(to: point(Double(q) * 90, radius + 4))
                    ctx.stroke(tick, with: .color(palette.textTertiary.opacity(0.5)), lineWidth: 1)
                }
                // The day's arcs, growing clockwise from their starts.
                for (i, arc) in arcs.enumerated() {
                    let grow = MotionMath.dayRingArc(elapsed: elapsed, index: i, play: play, expressive: expressive)
                    guard grow > 0 else { continue }
                    let alpha: Double = arc.past ? 0.38 : (arc.kind == .task ? 0.7 : 1)
                    ctx.stroke(arcPath(from: Double(arc.startDegrees), sweep: Double(arc.sweepDegrees) * grow),
                               with: .color(palette.accent.opacity(alpha)), style: StrokeStyle(lineWidth: stroke, lineCap: .butt))
                }
                // The now needle, with a brass dot at its tip.
                if needle > 0 {
                    let deg = Double(ring.nowDegrees) * needle
                    let tip = point(deg, radius + stroke * 0.7)
                    var line = Path()
                    line.move(to: point(deg, radius - stroke * 1.6))
                    line.addLine(to: tip)
                    ctx.stroke(line, with: .color(palette.textPrimary.opacity(0.8)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 3.5, y: tip.y - 3.5, width: 7, height: 7)), with: .color(palette.accent))
                }
            }
            VStack(spacing: MekaSpace.xxs) {
                Text(DayRingRules.shared.freeLine(freeMinutes: Int32(free), nowMinute: ring.nowMinute))
                    .font(MekaType.body).monospacedDigit().foregroundStyle(palette.textPrimary)
                Text(DayRingRules.shared.toDoLine(toDo: Int32(toDo)))
                    .font(MekaType.caption).monospacedDigit().foregroundStyle(palette.textSecondary)
            }
            .multilineTextAlignment(.center)
            .frame(width: size * 0.62)
        }
    }
}

/// Which day the Day ring last played in full on this Mac, so later opens that day play quickly.
enum DayRingOpen {
    private static let key = "dayRingFullDay"

    /// How the ring plays on this open; a full play is marked at once.
    static func claim(reduced: Bool, now: Date = Date(), defaults: UserDefaults = .standard) -> DayRingPlayback {
        let today = Int64((now.timeIntervalSince1970 + Double(TimeZone.current.secondsFromGMT(for: now))) / 86_400.0)
        let last: KotlinLong? = defaults.object(forKey: key) == nil ? nil : KotlinLong(longLong: Int64(defaults.integer(forKey: key)))
        let play = DayRingRules.shared.play(lastFullEpochDay: last, todayEpochDay: today, reduced: reduced)
        switch play {
        case .full:
            defaults.set(Int(today), forKey: key)
            return .full
        case .quick: return .quick
        default: return .still
        }
    }
}

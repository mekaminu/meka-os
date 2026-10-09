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
    /// The live tiles under the dial (the opening moment, part 2): next event, fast, habits, renewals.
    var tiles: [DayTile] = []
    /// Clicking an arc on the ring opens it (Living Today, slice 3); nil: the ring doesn't answer clicks.
    var onOpenArc: ((DayArc) -> Void)? = nil
    @Environment(\.mekaExpressiveMotion) private var expressive
    @State private var began = Date()

    var body: some View {
        let total = MotionMath.dayRingTotal(arcs: ring.arcs.count, play: play, expressive: expressive, tiles: tiles.count)
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: play == .still)) { context in
            let elapsed = play == .still ? total : context.date.timeIntervalSince(began)
            VStack(spacing: MekaSpace.m) {
                dial(elapsed: elapsed)
                    // Living Today: once the opening has landed the ring stays alive on a layer of its own.
                    .overlay(DayRingLiveLayer(ring: ring, landed: play == .still, palette: palette))
                    .frame(width: size, height: size)
                    .contentShape(Circle())
                    .onTapGesture(coordinateSpace: .local) { p in openArc(at: p) }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(ring.spokenLine)
                if !tiles.isEmpty {
                    DayTilesRow(tiles: tiles, count: MotionMath.dayRingCount(elapsed: elapsed, play: play, expressive: expressive),
                                palette: palette) { i in
                        MotionMath.dayTile(elapsed: elapsed, index: i, play: play, expressive: expressive)
                    }
                }
            }
        }
        .padding(.vertical, MekaSpace.s)
        .onAppear { began = Date() }
        .task(id: play) {
            guard play != .still else { return }
            began = Date()
            try? await Task.sleep(for: .seconds(total))
            played()
        }
    }

    /// A click on the ring opens the arc under it (the angle from the centre picks it; free time opens nothing).
    private func openArc(at p: CGPoint) {
        guard let onOpenArc else { return }
        let radius = Float(size / 2 - 10 / 2 - 2) // the track, as the dial draws it
        guard let deg = DayRingRules.shared.tapDegrees(dx: Float(p.x - size / 2), dy: Float(p.y - size / 2), radius: radius),
              let arc = DayRingRules.shared.arcAt(ring: ring, degrees: deg.floatValue) else { return }
        MekaHaptics.tick()
        onOpenArc(arc)
    }

    private func dial(elapsed: Double) -> some View {
        let mark = MotionMath.dayRingMark(elapsed: elapsed, play: play, expressive: expressive)
        let needle = MotionMath.dayRingNeedle(elapsed: elapsed, play: play, expressive: expressive)
        let count = MotionMath.dayRingCount(elapsed: elapsed, play: play, expressive: expressive)
        let free = MotionMath.countUpValue(from: 0, to: Int(ring.freeMinutes), fraction: count)
        let toDo = MotionMath.countUpValue(from: 0, to: Int(ring.toDo), fraction: count)
        let arcs = ring.arcs
        let work = ring.work
        let rain = ring.rain
        let fast = ring.fast
        // After Shut down: tomorrow's first thing (Living Today, item 1).
        let tomorrowDegrees: Double? = ring.tomorrow?.degrees.map { Double($0.floatValue) }
        let centreLine = ring.centreLine(free: Int32(free))
        let centreCaption = ring.centreCaption(toDo: Int32(toDo))
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
                // The hour marks: a tick at 00 · 06 · 12 · 18, a fine dot just inside the track at every other hour. On
                // the first open they fade in one by one behind the drawing mark; the quick draw brings them up with it.
                for h in 0..<24 {
                    let show = MotionMath.dayRingHour(mark: mark, hour: h, play: play)
                    guard show > 0 else { continue }
                    if h % 6 == 0 {
                        var tick = Path()
                        tick.move(to: point(Double(h) * 15, radius - 4))
                        tick.addLine(to: point(Double(h) * 15, radius + 4))
                        ctx.stroke(tick, with: .color(palette.textTertiary.opacity(0.5 * show)), lineWidth: 1)
                    } else {
                        let p = point(Double(h) * 15, radius - stroke / 2 - 3)
                        ctx.fill(Path(ellipseIn: CGRect(x: p.x - 0.9, y: p.y - 0.9, width: 1.8, height: 1.8)),
                                 with: .color(palette.textTertiary.opacity(0.4 * show)))
                    }
                }
                // Work hours: a faint band along the track (not booked, so not an arc), coming up with the mark.
                for band in work {
                    ctx.stroke(arcPath(from: Double(band.startDegrees), sweep: Double(band.sweepDegrees)),
                               with: .color(palette.textTertiary.opacity((band.current ? 0.26 : 0.16) * mark)),
                               style: StrokeStyle(lineWidth: 4, lineCap: .butt))
                }
                // Rain still to come today (Weather slice 2): a faint blue tint along the track, a shade brighter while
                // it's raining now, coming up with the mark.
                for band in rain {
                    ctx.stroke(arcPath(from: Double(band.startDegrees), sweep: Double(band.sweepDegrees)),
                               with: .color(palette.rain.opacity((band.current ? 0.42 : 0.26) * mark)),
                               style: StrokeStyle(lineWidth: 3, lineCap: .butt))
                }
                // A running fast: an inner arc from when it began round to its goal, filling as it counts up.
                if let f = fast {
                    let r = radius - stroke * 1.9
                    func inner(_ sweep: Double) -> Path {
                        var p = Path()
                        p.addArc(center: c, radius: r, startAngle: .degrees(Double(f.startDegrees) - 90),
                                 endAngle: .degrees(Double(f.startDegrees) - 90 + sweep), clockwise: false)
                        return p
                    }
                    ctx.stroke(inner(Double(f.sweepDegrees) * mark), with: .color(palette.accent.opacity(0.16)),
                               style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    if f.filledDegrees > 0 {
                        ctx.stroke(inner(Double(f.filledDegrees) * count), with: .color(palette.accent.opacity(f.reachedGoal ? 1 : 0.85)),
                                   style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    }
                }
                // The day's arcs, growing clockwise from their starts; the gym's booked sessions wider, to stand out.
                for (i, arc) in arcs.enumerated() {
                    let grow = MotionMath.dayRingArc(elapsed: elapsed, index: i, play: play, expressive: expressive)
                    guard grow > 0 else { continue }
                    let alpha: Double = arc.past ? 0.38 : (arc.kind == .task ? 0.7 : 1)
                    ctx.stroke(arcPath(from: Double(arc.startDegrees), sweep: Double(arc.sweepDegrees) * grow),
                               with: .color(palette.accent.opacity(alpha)),
                               style: StrokeStyle(lineWidth: arc.highlighted && !arc.past ? stroke * 1.4 : stroke, lineCap: .butt))
                }
                // After Shut down: tomorrow's first thing as a hollow brass mark on the track, coming up with the mark.
                if let deg = tomorrowDegrees, mark > 0 {
                    let p = point(deg, radius)
                    let r = stroke * 0.55 * mark
                    let dot = Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2))
                    ctx.fill(dot, with: .color(palette.background))
                    ctx.stroke(dot, with: .color(palette.accent.opacity(mark)), lineWidth: 2)
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
                // "3 h 45 free" · "4 to do"; once the day is shut down, tomorrow's first thing ("Tomorrow 09:30" · "Standup").
                Text(centreLine)
                    .font(MekaType.body).monospacedDigit().foregroundStyle(palette.textPrimary)
                Text(centreCaption)
                    .font(MekaType.caption).monospacedDigit().foregroundStyle(palette.textSecondary)
                    .lineLimit(2)
            }
            .multilineTextAlignment(.center)
            .frame(width: size * 0.62)
        }
    }
}

/// The living Day ring (Living Today, slice 1), the Mac twin of the Fold's `DayRingLiveLayer`: drawn over the dial
/// once the opening has landed, with the same geometry and every number from the core's `DayRingLive` — a fine brass
/// second hand with a comet tail sweeping round once a minute (smoothly, never ticking) and fading in, the brass edge
/// breathing 60 % → 100 % over 5 s, a band of light running once round at the top of each hour, and the now dot
/// popping on its spring as each minute turns. Its own `TimelineView` runs at 60 fps only while sweeping; Low Power
/// Mode redraws it once a minute with no hand or breath; Motion → Off is a still edge with no hand. SwiftUI stops the
/// timeline while the window is hidden.
struct DayRingLiveLayer: View {
    let ring: DayRing
    let landed: Bool
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var landedAt: Date? = nil

    private var mode: DayRingLiveMode {
        DayRingLive.shared.mode(reduced: reduceMotion, powerSave: ProcessInfo.processInfo.isLowPowerModeEnabled)
    }

    var body: some View {
        let mode = self.mode
        Group {
            if landed {
                if mode == .sweep {
                    TimelineView(.animation(minimumInterval: 1.0 / 60)) { context in layer(now: context.date, mode: mode) }
                } else {
                    TimelineView(.everyMinute) { context in layer(now: context.date, mode: mode) }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { if landed { landedAt = Date() } }
        .onChange(of: landed) { _, now in landedAt = now ? Date() : nil }
    }

    private func layer(now: Date, mode: DayRingLiveMode) -> some View {
        let live = DayRingLive.shared
        let ms = Int64((now.timeIntervalSince1970 * 1000).rounded(.down))
        let sweeping = mode == .sweep
        let sinceLanded = Int64(now.timeIntervalSince(landedAt ?? now) * 1000)
        let fadeIn = sweeping ? Double(live.handFade(sinceLandedMs: sinceLanded)) : 1
        let glow = sweeping ? Double(live.glow(epochMs: ms)) : 0.8
        let offsetMs = Int64(TimeZone.current.secondsFromGMT(for: now)) * 1000
        let shimmer = sweeping ? live.shimmer(epochMs: ms, offsetMs: offsetMs)?.doubleValue : nil
        let hand = Double(live.handDegrees(epochMs: ms))
        let pop = sweeping ? Double(live.nowPop(epochMs: ms)) : 1
        let nowDegrees = Double(ring.nowDegrees)
        let onNow = ring.arcs.filter { $0.current }.map { (Double($0.startDegrees), Double($0.sweepDegrees)) }
        let accent = palette.accent
        return Canvas { ctx, canvasSize in
            let stroke: CGFloat = 10
            let radius = min(canvasSize.width, canvasSize.height) / 2 - stroke / 2 - 2
            let c = CGPoint(x: canvasSize.width / 2, y: canvasSize.height / 2)
            func point(_ degrees: Double, _ r: CGFloat) -> CGPoint {
                let a = (degrees - 90) * .pi / 180
                return CGPoint(x: c.x + cos(a) * r, y: c.y + sin(a) * r)
            }
            func arc(_ r: CGFloat, from: Double, sweep: Double) -> Path {
                var p = Path()
                p.addArc(center: c, radius: r, startAngle: .degrees(from - 90), endAngle: .degrees(from - 90 + sweep), clockwise: false)
                return p
            }
            let edge = radius + stroke / 2 + 1
            // The brass edge and its soft halo, breathing.
            ctx.stroke(arc(edge + 1.5, from: 0, sweep: 360), with: .color(accent.opacity(0.10 * glow * fadeIn)), lineWidth: 4)
            ctx.stroke(arc(edge, from: 0, sweep: 360), with: .color(accent.opacity(0.55 * glow * fadeIn)), lineWidth: 1)
            // The arc on now glows with the ring's breath (Living Today, slice 3): a soft wider halo over it.
            for (from, sweep) in onNow {
                ctx.stroke(arc(radius, from: from, sweep: sweep), with: .color(accent.opacity(0.30 * glow * fadeIn)),
                           style: StrokeStyle(lineWidth: stroke + 8, lineCap: .round))
            }
            guard sweeping else { return }
            // The hour's shimmer: a band of light running once round the edge.
            if let s = shimmer {
                let head = 360 * s
                let fade = min(max((1 - s) * 4, 0), 1)
                let parts = 10
                let step = Double(live.SHIMMER_BAND_DEGREES) / Double(parts)
                for j in 0..<parts {
                    let f = 1 - Double(j) / Double(parts)
                    ctx.stroke(arc(edge, from: head - step * Double(j + 1), sweep: step),
                               with: .color(accent.opacity(f * f * 0.8 * fade)), lineWidth: 2.5)
                }
            }
            // The second hand: a comet tail along the track, a fine brass hand across it and a bead where they meet.
            let segments = Int(live.TAIL_SEGMENTS)
            let step = Double(live.TAIL_DEGREES) / Double(segments)
            for i in 0..<segments {
                ctx.stroke(arc(radius, from: hand - step * Double(i + 1), sweep: step + 0.4),
                           with: .color(accent.opacity(Double(live.tailAlpha(i: Int32(i))) * fadeIn)), lineWidth: 3)
            }
            var line = Path()
            line.move(to: point(hand, radius - stroke * 1.2))
            line.addLine(to: point(hand, radius + stroke * 0.9))
            ctx.stroke(line, with: .color(accent.opacity(fadeIn)), style: StrokeStyle(lineWidth: 1.25, lineCap: .round))
            let bead = point(hand, radius)
            ctx.fill(Path(ellipseIn: CGRect(x: bead.x - 2.5, y: bead.y - 2.5, width: 5, height: 5)), with: .color(accent.opacity(fadeIn)))
            // The now dot pops as the minute turns.
            if pop > 1 {
                let tip = point(nowDegrees, radius + stroke * 0.7)
                let r = 3.5 * pop
                ctx.fill(Path(ellipseIn: CGRect(x: tip.x - r, y: tip.y - r, width: r * 2, height: r * 2)), with: .color(accent))
            }
        }
    }
}

/// The live tiles under the Day ring (the opening moment, part 2), the Mac twin of the Fold's `DayTilesRow`: up to
/// four small tiles side by side — next event countdown, a running fast, habits done today, renewals due
/// (`DayTileRules`). Each fades and rises in after the mark closes while its number counts up with the centre;
/// afterwards they follow Today's minute refresh. VoiceOver reads each as one line.
struct DayTilesRow: View {
    let tiles: [DayTile]
    let count: Double
    let palette: MekaPalette
    let appear: (Int) -> Double
    @Environment(\.mekaExpressiveMotion) private var expressive

    var body: some View {
        HStack(alignment: .top, spacing: MekaSpace.xs) {
            ForEach(Array(tiles.enumerated()), id: \.offset) { i, tile in
                let a = appear(i)
                let shown = MotionMath.countUpValue(from: 0, to: Int(tile.value), fraction: count)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tile.text(shown: Int32(shown)))
                        .font(MekaType.body).monospacedDigit().foregroundStyle(palette.textPrimary).lineLimit(1)
                    Text(tile.label)
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, MekaSpace.s)
                .padding(.vertical, MekaSpace.xs)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                .opacity(a)
                .offset(y: (1 - a) * MotionMath.riseDistance(expressive: expressive) / 2)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(tile.spokenLine)
            }
        }
    }
}

/// The greeting on Today. On the first open of the day (`.full`) its letters fade in one after another, the line
/// rising a little with the first of them (`MotionMath.greetingLetter`); later opens and Motion → Off show it at once.
/// VoiceOver reads the plain words.
struct GreetingText: View {
    let text: String
    let play: DayRingPlayback
    let palette: MekaPalette
    @State private var began = Date()

    var body: some View {
        let letters = Array(text)
        let total = MotionMath.greetingTotal(letters: letters.count, play: play)
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: total == 0)) { context in
            let elapsed = total == 0 ? total : context.date.timeIntervalSince(began)
            let lead = MotionMath.greetingLetter(elapsed: elapsed, index: 0, play: play)
            letters.enumerated().reduce(Text("")) { line, item in
                let f = MotionMath.greetingLetter(elapsed: elapsed, index: item.offset, play: play)
                return line + Text(String(item.element)).foregroundStyle(palette.textPrimary.opacity(f))
            }
            .font(MekaType.greeting).tracking(MekaType.greetingTracking)
            .offset(y: (1 - lead) * MotionMath.greetingLetterRise)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .onAppear { began = Date() }
        .onChange(of: play) { began = Date() }
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

    /// What coming back to Today after `away` seconds plays (the core's `DayRingRules.onReturn`): nil for a short trip
    /// (Today stays as it was), else the quick draw-in, or the full opening on a new day (marked at once, like `claim`).
    static func onReturn(away: TimeInterval, reduced: Bool, now: Date = Date(), defaults: UserDefaults = .standard) -> DayRingPlayback? {
        let today = Int64((now.timeIntervalSince1970 + Double(TimeZone.current.secondsFromGMT(for: now))) / 86_400.0)
        let last: KotlinLong? = defaults.object(forKey: key) == nil ? nil : KotlinLong(longLong: Int64(defaults.integer(forKey: key)))
        let awayMs = Int64(max(away, 0) * 1000)
        guard let play = DayRingRules.shared.onReturn(lastFullEpochDay: last, todayEpochDay: today, awayMs: awayMs, reduced: reduced) else {
            return nil
        }
        switch play {
        case .full:
            defaults.set(Int(today), forKey: key)
            return .full
        case .quick: return .quick
        default: return nil
        }
    }
}

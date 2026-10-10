@preconcurrency import MekaKit
import SwiftUI

/// Today's watch face (Fold review 2026-10-09 07:26, item 2), the Mac twin of the Fold's `WatchFaceDial`: a 12-hour
/// face in the header — the brass rim with 12 markers (no numerals; 12, 3, 6 and 9 heavier), the next 12 hours of
/// events as arcs on the rim (work a faint band, the gym, Barça and training wider, the one on now glowing), gold
/// tapered hour and minute hands and the fine sweeping second hand with its comet tail. The opening draws it in like
/// the Day ring did (the rim round from 12, the markers behind it, the arcs one after another, the hands sweeping from
/// 12 to the time); then it lives (the rim breathes, the hour's shimmer runs, the second hand sweeps). A click opens
/// the full 24-hour Day ring as a sheet. VoiceOver reads one line.
///
/// Calm Today, slice 2: today's habits sit on the face as small dots just inside the markers, centred on 6 o'clock
/// (`HabitDotRules`), hollow until done; a click on a dot ticks it (`onTick`; the fill floods in and the dot pops on
/// the complete spring, unticking empties it at once), anywhere else opens the day. VoiceOver gets one action per habit.
struct WatchFaceView: View {
    let face: WatchFace
    let play: DayRingPlayback
    let played: () -> Void
    let palette: MekaPalette
    var size: CGFloat = 150
    var onOpen: (() -> Void)? = nil
    var dots: [HabitDot] = []
    var onTick: ((HabitDot) -> Void)? = nil
    /// Done for the day: the day is shut down, so the face rests (no second hand or breath; redrawn each minute).
    var night: Bool = false
    @Environment(\.mekaExpressiveMotion) private var expressive
    @State private var began = Date()

    var body: some View {
        let total = MotionMath.dayRingTotal(arcs: face.arcs.count, play: play, expressive: expressive)
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: play == .still)) { context in
            let elapsed = play == .still ? total : context.date.timeIntervalSince(began)
            let landed = play == .still || elapsed >= total
            dial(elapsed: elapsed)
                .overlay(dotsLayer(mark: MotionMath.dayRingMark(elapsed: elapsed, play: play, expressive: expressive)))
                .overlay(WatchFaceLiveLayer(face: face, landed: landed,
                                            needle: MotionMath.dayRingNeedle(elapsed: elapsed, play: play, expressive: expressive),
                                            palette: palette, size: size, night: night))
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
        .onTapGesture(coordinateSpace: .local) { location in
            // A click near a habit's dot ticks it; anywhere else opens the whole day.
            if let onTick, let dot = HabitDotRules.shared.hit(dots: dots, xDp: Float(location.x - size / 2),
                                                               yDp: Float(location.y - size / 2), sizeDp: Int32(size)) {
                onTick(dot)
                return
            }
            guard let onOpen else { return }
            MekaHaptics.tick()
            onOpen()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(face.spokenLine + HabitDotRules.shared.spokenLine(dots: dots))
        .accessibilityAddTraits(onOpen == nil ? [] : .isButton)
        .accessibilityActions {
            if let onTick {
                ForEach(dots, id: \.id) { dot in
                    Button(dot.tickLabel) { onTick(dot) }
                }
            }
        }
        .onAppear { began = Date() }
        .task(id: play) {
            guard play != .still else { return }
            began = Date()
            try? await Task.sleep(for: .seconds(total))
            played()
        }
    }

    /// Today's habits as dots inside the markers at the foot of the dial, coming up as the drawing rim passes them.
    private func dotsLayer(mark: Double) -> some View {
        let rules = HabitDotRules.shared
        let sizeDp = Int32(size)
        return ZStack {
            ForEach(dots, id: \.id) { dot in
                let place = rules.place(dot: dot, sizeDp: sizeDp)
                HabitDotView(done: dot.done, behind: dot.behind, diameter: CGFloat(place.radiusDp * 2), palette: palette)
                    .opacity(Double(rules.shown(mark: Float(mark), degrees: dot.degrees)))
                    .position(x: size / 2 + CGFloat(place.xDp), y: size / 2 + CGFloat(place.yDp))
            }
        }
        .frame(width: size, height: size)
        .allowsHitTesting(false)
    }

    private func dial(elapsed: Double) -> some View {
        let mark = MotionMath.dayRingMark(elapsed: elapsed, play: play, expressive: expressive)
        let rules = WatchFaceRules.shared
        let sizeDp = Int32(size)
        let rim = CGFloat(rules.rimStrokeDp(sizeDp: sizeDp))
        let radius = CGFloat(rules.rimRadiusDp(sizeDp: sizeDp))
        let markers = rules.markers().map { (Double($0.degrees), $0.major) }
        let work = face.work.map { (Double($0.startDegrees), Double($0.sweepDegrees), $0.current) }
        let arcs = face.arcs.map { (Double($0.startDegrees), Double($0.sweepDegrees), $0.highlighted, $0.kind == .task) }
        let grows = arcs.indices.map { MotionMath.dayRingArc(elapsed: elapsed, index: $0, play: play, expressive: expressive) }
        let shows = markers.indices.map { MotionMath.dayRingHour(mark: mark, hour: $0 * 2, play: play) }
        let look = DayRingLook.shared
        return Canvas { ctx, canvasSize in
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
            // The rim's track: brass, 3 pt at 55 %, drawing itself round from 12.
            if mark > 0 {
                ctx.stroke(arcPath(from: 0, sweep: 360 * mark), with: .color(palette.accent.opacity(Double(look.TRACK_ALPHA))),
                           lineWidth: CGFloat(look.TRACK_STROKE_DP))
            }
            // The 12 markers just inside the rim, fading in behind the drawing rim.
            for (i, m) in markers.enumerated() where shows[i] > 0 {
                let outer = radius - rim / 2 - 2
                let inner = outer - radius * CGFloat(m.1 ? rules.MAJOR_MARKER_LENGTH : rules.MINOR_MARKER_LENGTH)
                var tick = Path()
                tick.move(to: point(m.0, inner))
                tick.addLine(to: point(m.0, outer))
                ctx.stroke(tick, with: .color(palette.accent.opacity(Double(rules.MARKER_ALPHA) * shows[i])),
                           style: StrokeStyle(lineWidth: CGFloat(m.1 ? rules.MAJOR_MARKER_DP : rules.MINOR_MARKER_DP), lineCap: .round))
            }
            // Work in the next 12 hours: a brass band at 70 % as wide as the rim (clear of the 3 pt track), coming up
            // with it (Meka's 10:48 screenshots: the old grey band vanished under the track).
            for band in work {
                ctx.stroke(arcPath(from: band.0, sweep: band.1), with: .color(palette.accent.opacity(Double(rules.WORK_BAND_ALPHA) * mark)),
                           style: StrokeStyle(lineWidth: rim * CGFloat(rules.WORK_BAND_WIDTH), lineCap: .butt))
            }
            // The next 12 hours' arcs, growing clockwise one after another; the gym, Barça and training wider.
            for (i, arc) in arcs.enumerated() where grows[i] > 0 {
                let alpha = Double(arc.3 ? rules.TASK_ARC_ALPHA : rules.EVENT_ARC_ALPHA)
                ctx.stroke(arcPath(from: arc.0, sweep: arc.1 * grows[i]), with: .color(palette.accent.opacity(alpha)),
                           style: StrokeStyle(lineWidth: arc.2 ? rim * CGFloat(rules.HIGHLIGHT_WIDTH) : rim, lineCap: .butt))
            }
        }
        .frame(width: size, height: size)
    }
}

/// The watch face's living layer, the Mac twin of the Fold's `WatchFaceLiveLayer`: the hour and minute hands (always —
/// a watch shows the time even with Motion → Off), and once the opening has landed the breathing rim with its soft
/// blur, the hour's shimmer, the glow on what's on now and the sweeping second hand with its comet tail (the Day ring's
/// numbers, `DayRingLive` and `DayRingLook`). 60 fps only while sweeping; Low Power Mode and Motion → Off redraw once a
/// minute with no second hand or breath.
struct WatchFaceLiveLayer: View {
    let face: WatchFace
    let landed: Bool
    let needle: Double
    let palette: MekaPalette
    let size: CGFloat
    var night: Bool = false
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var landedAt: Date? = nil

    private var sweeping: Bool {
        landed && WatchFaceRules.shared.liveMode(reduced: reduceMotion, powerSave: ProcessInfo.processInfo.isLowPowerModeEnabled,
                                                 bedside: false, quiet: false, night: night) == .sweep
    }

    var body: some View {
        Group {
            if sweeping || !landed {
                TimelineView(.animation(minimumInterval: 1.0 / 60)) { context in layer(now: context.date) }
            } else {
                TimelineView(.everyMinute) { context in layer(now: context.date) }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { if landed { landedAt = Date() } }
        .onChange(of: landed) { _, now in landedAt = now ? Date() : nil }
    }

    private func layer(now: Date) -> some View {
        let live = DayRingLive.shared
        let look = DayRingLook.shared
        let rules = WatchFaceRules.shared
        let ms = Int64((now.timeIntervalSince1970 * 1000).rounded(.down))
        let offsetMs = Int64(TimeZone.current.secondsFromGMT(for: now)) * 1000
        let sweeping = self.sweeping
        let landed = self.landed
        let sinceLanded = Int64(now.timeIntervalSince(landedAt ?? now) * 1000)
        let fadeIn = sweeping ? Double(live.handFade(sinceLandedMs: sinceLanded)) : 1
        let glow = sweeping ? Double(live.glow(epochMs: ms)) : 0.8
        let shimmer = sweeping ? live.shimmer(epochMs: ms, offsetMs: offsetMs)?.doubleValue : nil
        let hands = rules.handsAt(epochMs: ms, offsetMs: offsetMs)
        let hour = Double(hands.hourDegrees) * needle
        let minute = Double(hands.minuteDegrees) * needle
        let second = Double(hands.secondDegrees)
        let onNow = face.arcs.filter { $0.current }.map { (Double($0.startDegrees), Double($0.sweepDegrees)) }
        let accent = palette.accent
        let background = palette.background
        let sizeDp = Int32(size)
        let rim = CGFloat(rules.rimStrokeDp(sizeDp: sizeDp))
        let radius = CGFloat(rules.rimRadiusDp(sizeDp: sizeDp))
        let scale = min(max(size / 150, 0.7), 2)
        // The canvas reaches past the face by the rim's blur (negative padding below) so the soft glow isn't clipped.
        let bleed = CGFloat(look.EDGE_BLUR_DP) + 2
        return Canvas { ctx, canvasSize in
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
            /// A tapered hand from the hub out to `length`, with a short counterweight behind the hub.
            func hand(_ degrees: Double, length: CGFloat, base: CGFloat, tip: CGFloat) -> Path {
                let a = (degrees - 90) * .pi / 180
                let d = CGPoint(x: cos(a), y: sin(a))
                let n = CGPoint(x: -d.y, y: d.x)
                let back = radius * CGFloat(rules.TAIL_LENGTH)
                func p(_ along: CGFloat, _ across: CGFloat) -> CGPoint {
                    CGPoint(x: c.x + d.x * along + n.x * across, y: c.y + d.y * along + n.y * across)
                }
                var path = Path()
                path.move(to: p(-back, base / 2.6))
                path.addLine(to: p(0, base / 2))
                path.addLine(to: p(length, tip / 2))
                path.addLine(to: p(length + tip / 2, 0))
                path.addLine(to: p(length, -tip / 2))
                path.addLine(to: p(0, -base / 2))
                path.addLine(to: p(-back, -base / 2.6))
                path.closeSubpath()
                return path
            }
            if landed {
                let shown = sweeping ? fadeIn : 1
                let edge = radius + rim / 2 + 1
                // The brass rim's edge, breathing 60 % → 100 %, with a soft blur reaching 8 pt out.
                for i in 0..<Int(look.EDGE_BLUR_LAYERS) {
                    ctx.stroke(arc(edge + CGFloat(look.blurOffsetDp(i: Int32(i))), from: 0, sweep: 360),
                               with: .color(accent.opacity(Double(look.blurAlpha(i: Int32(i), glow: Float(glow))) * shown)),
                               lineWidth: CGFloat(look.blurStrokeDp()))
                }
                ctx.stroke(arc(edge, from: 0, sweep: 360), with: .color(accent.opacity(Double(look.edgeAlpha(glow: Float(glow))) * shown)),
                           lineWidth: CGFloat(look.EDGE_STROKE_DP))
                // What's on now glows with the rim's breath.
                for (from, sweep) in onNow {
                    ctx.stroke(arc(radius, from: from, sweep: sweep), with: .color(accent.opacity(0.30 * glow * shown)),
                               style: StrokeStyle(lineWidth: rim + 6, lineCap: .round))
                }
                // The hour's shimmer: a band of light running once round the rim.
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
            }
            // The hour and minute hands: gold, tapered, sweeping round from 12 to the time as the face draws in.
            ctx.fill(hand(minute, length: radius * CGFloat(rules.MINUTE_HAND_LENGTH),
                          base: CGFloat(rules.MINUTE_HAND_BASE_DP) * scale, tip: CGFloat(rules.MINUTE_HAND_TIP_DP) * scale),
                     with: .color(accent))
            ctx.fill(hand(hour, length: radius * CGFloat(rules.HOUR_HAND_LENGTH),
                          base: CGFloat(rules.HOUR_HAND_BASE_DP) * scale, tip: CGFloat(rules.HOUR_HAND_TIP_DP) * scale),
                     with: .color(accent))
            if needle > 0 {
                let hub = CGFloat(rules.HUB_DP) * scale
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - hub, y: c.y - hub, width: hub * 2, height: hub * 2)), with: .color(accent))
                let pin = hub * 0.4
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - pin, y: c.y - pin, width: pin * 2, height: pin * 2)), with: .color(background))
            }
            guard sweeping else { return }
            // The second hand: a comet tail along the rim, a fine brass hand across it and a bead where they meet.
            let segments = Int(live.TAIL_SEGMENTS)
            let step = Double(live.TAIL_DEGREES) / Double(segments)
            for i in 0..<segments {
                ctx.stroke(arc(radius, from: second - step * Double(i + 1), sweep: step + 0.4),
                           with: .color(accent.opacity(Double(live.tailAlpha(i: Int32(i))) * fadeIn)), lineWidth: CGFloat(look.TAIL_STROKE_DP))
            }
            var line = Path()
            line.move(to: point(second, radius - rim * 1.6))
            line.addLine(to: point(second, radius + rim * 0.9))
            ctx.stroke(line, with: .color(accent.opacity(fadeIn)), style: StrokeStyle(lineWidth: CGFloat(look.HAND_STROKE_DP) * 0.8, lineCap: .round))
            let bead = point(second, radius)
            let halo = CGFloat(look.HAND_TIP_HALO_DP)
            ctx.fill(Path(ellipseIn: CGRect(x: bead.x - halo, y: bead.y - halo, width: halo * 2, height: halo * 2)),
                     with: .color(accent.opacity(Double(look.HAND_TIP_HALO_ALPHA) * fadeIn)))
            let tipR = CGFloat(look.HAND_TIP_DP)
            ctx.fill(Path(ellipseIn: CGRect(x: bead.x - tipR, y: bead.y - tipR, width: tipR * 2, height: tipR * 2)), with: .color(accent.opacity(fadeIn)))
        }
        .padding(-bleed)
    }
}

/// The full 24-hour Day ring as a sheet (Fold review 2026-10-09 07:26, item 2: clicking the watch face), the Mac twin
/// of the Fold's `DayRingSheet`: the existing ring, enlarged, with its centre line, live tiles and clickable arcs; it
/// draws itself in quickly as the sheet appears.
struct DayRingSheet: View {
    let ring: DayRing
    let tiles: [DayTile]
    let palette: MekaPalette
    let onOpenArc: (DayArc) -> Void
    let onClose: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var play: DayRingPlayback = .quick

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("Your whole day").font(MekaType.greeting).foregroundStyle(palette.textPrimary)
                    Text("Midnight at the top · click an arc to open it").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
                Spacer()
                Button("Close", action: onClose)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
            }
            DayRingView(ring: ring, play: play, played: { play = .still }, palette: palette, size: 320, tiles: tiles, onOpenArc: onOpenArc)
        }
        .padding(MekaSpace.l)
        .frame(minWidth: 460)
        .background(palette.background)
        .onAppear { if reduceMotion { play = .still } }
    }
}

/// One habit's dot on the watch face (Calm Today, slice 2): a ring (the full accent when behind for the week, else
/// quieter), filled with the accent once done. Ticking floods the fill in and pops the dot on the complete spring;
/// unticking empties it at once; already done shows full. Reduced motion: at once.
struct HabitDotView: View {
    let done: Bool
    let behind: Bool
    let diameter: CGFloat
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var popping = false

    var body: some View {
        let rules = HabitDotRules.shared
        ZStack {
            Circle()
                .stroke(palette.accent.opacity(behind || done ? 1 : Double(rules.OPEN_ALPHA)), lineWidth: CGFloat(rules.RING_STROKE_DP))
            Circle()
                .fill(palette.accent)
                .scaleEffect(done ? 1 : 0.001)
                .animation(done && !reduceMotion ? MekaMotion.complete(reduced: false) : nil, value: done)
        }
        .frame(width: diameter, height: diameter)
        .scaleEffect(popping ? CGFloat(rules.POP_SCALE) : 1)
        .onChange(of: done) { _, now in
            guard now, !reduceMotion else { return }
            withAnimation(MekaMotion.complete(reduced: false)) { popping = true }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(160))
                withAnimation(MekaMotion.complete(reduced: false)) { popping = false }
            }
        }
    }
}

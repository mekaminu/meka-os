@preconcurrency import MekaKit
@preconcurrency import AVFoundation
import CoreImage
import os
import SwiftUI

/// Obi's face, slice 4 (the Mac): the face's seven files kept in this Mac's own Application Support folder (never in
/// the repository, never shared), like the Fold's ObiFaceStore. `refresh` asks Navilife's server for its list when
/// `FaceFileRules.due` (weekly; sooner while nothing or only part is there), fetches only the files whose tag changed
/// and lets go of any the server no longer has. `ready` turns true only with all seven kept; `on` is the Face setting
/// in Obi's voice (on by default, kept on this Mac). Until both, the orb stays, with no error shown. While the face can
/// show, the core asks Navilife's clips with their mouth sounds (`CoreModel.speechFaceMarks`).
///
/// Main actor throughout; only Strings and Data cross to the core.
@MainActor
@Observable
final class MacFaceStore {
    static let shared = MacFaceStore()
    static let onKey = "meka.obi.face"

    private(set) var ready = false
    private(set) var on: Bool = UserDefaults.standard.object(forKey: MacFaceStore.onKey) as? Bool ?? true

    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private var toldCore: Bool?
    @ObservationIgnored private weak var model: CoreModel?

    /// The face replaces the orb on the big stages (`FaceFileRules.shows`).
    var shows: Bool { FaceFileRules.shared.shows(ready: ready, faceOn: on) }

    static func folder() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("os.meka.mac", isDirectory: true)
            .appendingPathComponent("obi-face", isDirectory: true)
    }

    private static let indexName = "index.txt"

    /// One kept file, or nil for a name outside the seven or one not kept.
    func file(_ name: String) -> URL? {
        guard FaceFileRules.shared.allowed(name: name) else { return nil }
        let url = Self.folder().appendingPathComponent(name)
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber,
              size.int64Value > 0 else { return nil }
        return url
    }

    /// Reads the index once (cheap; whatever shows the face first calls it).
    func load() {
        guard !loaded else { return }
        loaded = true
        ready = readIndex().complete
    }

    func setOn(_ value: Bool) {
        UserDefaults.standard.set(value, forKey: Self.onKey)
        on = value
        Task { await tellCore() }
    }

    /// Health → Obi's voice's face line (Meka 2026-10-24 13:12): the setting, how many are kept, the last look.
    func health() -> FaceHealth {
        load()
        let kept = Int32(readIndex().kept.count)
        let text = try? String(contentsOf: Self.folder().appendingPathComponent(Self.checkName), encoding: .utf8)
        return FaceHealth(on: on, kept: kept, check: FaceHealthRules.shared.decode(text: text))
    }

    private static let checkName = "check.txt"

    private func writeCheck(_ check: FaceCheck) {
        ensureFolder()
        let text = FaceHealthRules.shared.encode(c: check)
        try? text.write(to: Self.folder().appendingPathComponent(Self.checkName), atomically: true, encoding: .utf8)
    }

    /// Looks at the server's list when it's due, or now with `force` (Health's Look again); never throws (a failure
    /// keeps what's kept and looks again later).
    func refresh(_ model: CoreModel, force: Bool = false) async {
        self.model = model
        load()
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        let rules = FaceFileRules.shared
        let allowed = Set(rules.FILES)
        let index = readIndex()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard force || rules.due(index: index, nowMs: now) else {
            ready = index.complete
            await tellCore()
            return
        }
        guard let offered = await model.faceManifest() else {
            writeCheck(FaceCheck(atMs: now, offered: nil, notFetched: []))
            // With the face kept, the next open simply asks again; without it, not for another few hours.
            if !index.complete { writeIndex(rules.failed(index: index, nowMs: now)) }
            ready = index.complete
            await tellCore()
            return
        }
        let plan = rules.plan(index: index, offered: offered, allowed: allowed)
        ensureFolder()
        for name in plan.drop where rules.allowed(name: name) {
            try? FileManager.default.removeItem(at: Self.folder().appendingPathComponent(name))
        }
        var fetched: [String: String] = [:]
        for name in plan.fetch where rules.allowed(name: name) {
            guard let data = await model.faceFileData(name), !data.isEmpty, let tag = offered[name] else { continue }
            do {
                try data.write(to: Self.folder().appendingPathComponent(name), options: .atomic)
                fetched[name] = tag
            } catch {
                Self.log.debug("face file not kept")
            }
        }
        writeCheck(FaceCheck(atMs: now, offered: Array(offered.keys), notFetched: plan.fetch.filter { fetched[$0] == nil }))
        let next = rules.after(index: index, plan: plan, fetched: fetched, allowed: allowed, nowMs: now)
        writeIndex(next)
        ready = next.complete
        await tellCore()
    }

    private static let log = Logger(subsystem: "os.meka.mac", category: "face")

    /// The core asks clips with their mouth sounds only while the face can show (each change told once).
    private func tellCore() async {
        let now = shows
        guard now != toldCore, let model else { return }
        toldCore = now
        await model.speechFaceMarks(now)
    }

    private func ensureFolder() {
        try? FileManager.default.createDirectory(at: Self.folder(), withIntermediateDirectories: true)
    }

    /// The index, with any file that has gone missing left out (so it's fetched again).
    private func readIndex() -> FaceFileRules.Index {
        let rules = FaceFileRules.shared
        let allowed = Set(rules.FILES)
        let text = try? String(contentsOf: Self.folder().appendingPathComponent(Self.indexName), encoding: .utf8)
        let index = rules.decode(text: text, allowed: allowed)
        let kept = index.kept.filter { file($0.key) != nil }
        return FaceFileRules.Index(kept: kept, checkedAtMs: index.checkedAtMs, complete: rules.ready(kept: Set(kept.keys), allowed: allowed))
    }

    private func writeIndex(_ index: FaceFileRules.Index) {
        ensureFolder()
        let text = FaceFileRules.shared.encode(index: index)
        try? text.write(to: Self.folder().appendingPathComponent(Self.indexName), atomically: true, encoding: .utf8)
    }
}

/// The gold look as Core Image colour cubes (`FaceGold.cube`: the mockup's CSS chain, by luminance), plain and the
/// listening touch brighter. Made once on the main actor from the core's numbers; only `Data` leaves it.
@MainActor
enum FaceGoldCube {
    static let dimension = 32
    static let plain: Data = make(brightness: 1)
    static let lit: Data = make(brightness: FaceLook.shared.LISTEN_BRIGHT)

    private static func make(brightness: Float) -> Data {
        let cube = FaceGold.shared.cube(dimension: Int32(dimension), brightness: brightness)
        let n = Int(cube.size)
        var floats = [Float](repeating: 0, count: n)
        for i in 0..<n { floats[i] = cube.get(index: Int32(i)) }
        return floats.withUnsafeBufferPointer { Data(buffer: $0) }
    }
}

/// The Core Image side, off the main actor: the film's frames and the mouth patches through the gold cube.
nonisolated enum FaceGoldImage {
    static func gold(_ image: CIImage, cube: Data, dimension: Int) -> CIImage {
        guard let f = CIFilter(name: "CIColorCubeWithColorSpace") else { return image }
        f.setValue(image, forKey: kCIInputImageKey)
        f.setValue(dimension, forKey: "inputCubeDimension")
        f.setValue(cube, forKey: "inputCubeData")
        f.setValue(CGColorSpace(name: CGColorSpace.sRGB), forKey: "inputColorSpace")
        return f.outputImage?.cropped(to: image.extent) ?? image
    }

    /// One mouth patch, decoded and turned gold once (its own feathered alpha kept).
    static func patch(_ url: URL, cube: Data, dimension: Int) -> CGImage? {
        guard let source = CIImage(contentsOf: url) else { return nil }
        let out = gold(source, cube: cube, dimension: dimension)
        return CIContext().createCGImage(out, from: source.extent)
    }

    /// The film's frames, gold; `lit` picks the listening cube for the frames drawn while he speaks to Obi.
    static func composition(for asset: AVURLAsset, plain: Data, lit: Data, dimension: Int,
                            listening: OSAllocatedUnfairLock<Bool>) async -> AVVideoComposition? {
        let handler: @Sendable (AVAsynchronousCIImageFilteringRequest) -> Void = { request in
            let cube = listening.withLock { $0 } ? lit : plain
            request.finish(with: gold(request.sourceImage, cube: cube, dimension: dimension), context: nil)
        }
        return try? await AVVideoComposition.videoComposition(with: asset, applyingCIFiltersWithHandler: handler)
    }
}

/// The face film: a silent looping video (gold, through `FaceGoldImage.composition`) on an AVPlayerLayer filling its
/// view; the caller sizes it so only the crop shows. Reduced motion: held on its first frame.
private struct FaceFilm: NSViewRepresentable {
    let url: URL
    let playing: Bool
    let listening: Bool

    func makeCoordinator() -> FaceFilmPlayer { FaceFilmPlayer(url: url) }

    func makeNSView(context: Context) -> FaceFilmNSView {
        let v = FaceFilmNSView(frame: .zero)
        v.playerLayer.player = context.coordinator.player
        return v
    }

    func updateNSView(_ v: FaceFilmNSView, context: Context) {
        context.coordinator.set(playing: playing, listening: listening)
    }

    static func dismantleNSView(_ v: FaceFilmNSView, coordinator: FaceFilmPlayer) {
        v.playerLayer.player = nil
        coordinator.stop()
    }
}

final class FaceFilmNSView: NSView {
    let playerLayer = AVPlayerLayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        playerLayer.videoGravity = .resize
        playerLayer.backgroundColor = NSColor.clear.cgColor
        layer?.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) { nil }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}

/// The film's player: one looping, muted item with the gold composition (set before the looper copies it).
final class FaceFilmPlayer {
    let player = AVQueuePlayer()
    private var looper: AVPlayerLooper?
    private let listening = OSAllocatedUnfairLock<Bool>(initialState: false)
    private var playing = false
    private var setup: Task<Void, Never>?

    init(url: URL) {
        player.isMuted = true
        player.preventsDisplaySleepDuringVideoPlayback = false
        let asset = AVURLAsset(url: url)
        let plain = FaceGoldCube.plain, lit = FaceGoldCube.lit, dimension = FaceGoldCube.dimension
        let listening = self.listening
        setup = Task { [weak self] in
            let composition = await FaceGoldImage.composition(for: asset, plain: plain, lit: lit, dimension: dimension,
                                                              listening: listening)
            guard let self, !Task.isCancelled else { return }
            let item = AVPlayerItem(asset: asset)
            item.videoComposition = composition
            self.looper = AVPlayerLooper(player: self.player, templateItem: item)
            if self.playing { self.player.play() }
        }
    }

    func set(playing: Bool, listening: Bool) {
        self.listening.withLock { $0 = listening }
        guard playing != self.playing else { return }
        self.playing = playing
        if playing { player.play() } else { player.pause() }
    }

    func stop() {
        setup?.cancel()
        player.pause()
        looper?.disableLooping()
        looper = nil
        player.removeAllItems()
    }
}

/// Obi's face on the Mac, the approved mockup like the Fold's ObiFaceView: Meka's face film cropped to hide the
/// vendor's HUD (`FaceGeometry.filmIn`), turned gold (`FaceGold`), its edge faded to black by the ellipse, and one of
/// the six mouth patches cross-fading over the film's mouth as Obi speaks: from the clip's own mouth sounds when it
/// came with them (`mouth`, the speaker's `MouthTrack` at the player's position), else from his voice's loudness
/// (`LiveMouth`); idle, no patch: the film's own blinking face. Listening it is a touch brighter; thinking, a slow
/// gold glint passes across. Reduced motion: the film held still, the mouth shapes only. Drawing only.
struct ObiFaceView: View {
    let state: OrbState
    let level: Float
    var height: CGFloat
    var mouth: () -> MouthMix? = { nil }
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var live = LiveMouth()
    @State private var patches: [String: CGImage] = [:]
    @State private var began = Date()
    @State private var filmURL: URL?
    private let store = MacFaceStore.shared

    var body: some View {
        let crop = FaceGeometry.shared.CROP
        let width = height * CGFloat(crop.w / crop.h)
        let film = FaceGeometry.shared.filmIn(w: Double(width), h: Double(height))
        ZStack(alignment: .topLeading) {
            if let url = filmURL {
                FaceFilm(url: url, playing: !reduceMotion, listening: state == .listening)
                    .frame(width: CGFloat(film.w), height: CGFloat(film.h))
                    .offset(x: CGFloat(film.x), y: CGFloat(film.y))
            }
            TimelineView(.animation(minimumInterval: 1.0 / 60)) { context in
                let t = Int64(context.date.timeIntervalSince(began) * 1000)
                Canvas { g, size in draw(&g, size: size, tMs: t) }
            }
            .frame(width: width, height: height)
        }
        .frame(width: width, height: height, alignment: .topLeading)
        .clipped()
        .mask { FaceFade() }
        .compositingGroup()
        .accessibilityHidden(true)
        .task { await loadPatches() }
    }

    private func draw(_ g: inout GraphicsContext, size: CGSize, tMs: Int64) {
        // A clip with its own mouth sounds drives the mouth exactly; otherwise the loudness does (always stepped, so
        // the hold and cross-fade carry on smoothly when one hands over to the other).
        let fromLevel = live.at(state: state, level: level, tMs: tMs)
        let mix = (state == .speaking ? mouth() : nil) ?? fromLevel
        let box = FaceGeometry.shared.mouthIn(w: Double(size.width), h: Double(size.height))
        let rect = CGRect(x: box.x, y: box.y, width: box.w, height: box.h)
        func patch(_ shape: MouthShape?, _ alpha: Float) {
            guard let shape, alpha > 0, let img = patches[MouthMix.companion.file(shape: shape)] else { return }
            var p = g
            p.opacity = Double(min(alpha, 1))
            p.draw(Image(decorative: img, scale: 1), in: rect)
        }
        patch(mix.under, mix.underAlpha)
        patch(mix.over, mix.overAlpha)
        // Thinking: a soft gold band sweeping left to right.
        if let glint = FaceLook.shared.glint(state: state, tMs: tMs, reduced: reduceMotion)?.floatValue {
            let w = size.width
            let x = -0.3 * w + CGFloat(glint) * 1.6 * w
            var band = g
            band.blendMode = .plusLighter
            let warm = Color(.sRGB, red: 0.925, green: 0.843, blue: 0.620, opacity: 0.22)
            band.fill(Path(CGRect(origin: .zero, size: size)),
                      with: .linearGradient(Gradient(colors: [.clear, warm, .clear]),
                                            startPoint: CGPoint(x: x - 0.15 * w, y: 0), endPoint: CGPoint(x: x + 0.15 * w, y: 0)))
        }
    }

    /// The film's file, and the six patches decoded and turned gold once (small PNGs: a few milliseconds).
    private func loadPatches() async {
        filmURL = store.file(FaceFileRules.shared.LOOP)
        var done: [String: CGImage] = [:]
        for name in FaceFileRules.shared.FILES where name != FaceFileRules.shared.LOOP {
            guard let url = store.file(name),
                  let img = FaceGoldImage.patch(url, cube: FaceGoldCube.plain, dimension: FaceGoldCube.dimension) else { continue }
            done[name] = img
        }
        patches = done
    }
}

/// The ellipse: the full face inside `FADE_INNER` of its radius, fading to nothing at its edge (centred 50 % / 43 %,
/// half the crop's width across, 1.5× as tall).
private struct FaceFade: View {
    var body: some View {
        Canvas { g, size in
            let geo = FaceGeometry.shared
            let r = size.width * 0.5
            g.translateBy(x: size.width * CGFloat(geo.FADE_CX), y: size.height * CGFloat(geo.FADE_CY))
            g.scaleBy(x: 1, y: CGFloat(geo.FADE_TALL))
            let stops = [Gradient.Stop(color: .white, location: 0),
                         Gradient.Stop(color: .white, location: CGFloat(geo.FADE_INNER)),
                         Gradient.Stop(color: .clear, location: CGFloat(geo.FADE_OUTER))]
            g.fill(Path(ellipseIn: CGRect(x: -r, y: -r, width: 2 * r, height: 2 * r)),
                   with: .radialGradient(Gradient(stops: stops), center: .zero, startRadius: 0, endRadius: r))
        }
    }
}

/// Obi's face, slice 4: the orb with Obi's face coming in over it while `faceWanted` (a conversation is running) and
/// the face can show (`MacFaceStore.shows`: all seven files kept and Face on); back to the orb when it ends. Like the
/// Fold's ObiOrbOrFace: over the 600 ms dissolve (`FaceLook.DISSOLVE_MS`, smoothstep) the orb's points gather into the
/// face's oval (`FaceGather`), then the face shows through over them; closing runs it backwards. Reduced motion: the
/// 200 ms plain fade. Drawing only; the caller gives it its click and label.
struct ObiOrbOrFace: View {
    let state: OrbState
    let level: Float
    let palette: MekaPalette
    var entranceAt: Date?
    let faceWanted: Bool
    var mouth: () -> MouthMix? = { nil }
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private let store = MacFaceStore.shared
    /// The dissolve: from where it was to where it's going, since when.
    @State private var fromIn: Float = 0
    @State private var toIn: Float = 0
    @State private var changedAt = Date.distantPast

    private var size: CGFloat { CGFloat(HelloOrb.shared.SIZE) }

    var body: some View {
        let shows = faceWanted && store.shows
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !moving(Date()))) { context in
            let faceIn = value(at: context.date)
            let reduced = reduceMotion
            ZStack {
                HelloOrbView(state: state, level: level, palette: palette, entranceAt: entranceAt,
                             gather: FaceGather.shared.gather(faceIn: faceIn, reduced: reduced))
                    .opacity(Double(FaceGather.shared.orbAlpha(faceIn: faceIn, reduced: reduced)))
                if faceIn > 0 {
                    ObiFaceView(state: state, level: level, height: size, mouth: mouth)
                        .opacity(Double(FaceGather.shared.faceAlpha(faceIn: faceIn, reduced: reduced)))
                }
            }
            .frame(width: size, height: size)
        }
        .onAppear { store.load() }
        .onChange(of: shows, initial: true) { _, now in retarget(now ? 1 : 0) }
    }

    private var durationS: Double {
        Double(reduceMotion ? HelloEntrance.shared.FADE_MS : FaceLook.shared.DISSOLVE_MS) / 1000
    }

    private func moving(_ now: Date) -> Bool { now.timeIntervalSince(changedAt) < durationS + 0.05 }

    private func value(at now: Date) -> Float {
        let p = Float(min(max(now.timeIntervalSince(changedAt) / durationS, 0), 1))
        let eased = p * p * (3 - 2 * p)
        return fromIn + (toIn - fromIn) * eased
    }

    private func retarget(_ target: Float) {
        guard target != toIn else { return }
        let now = Date()
        // First appearance with the face already wanted: no dissolve from nothing on a redraw, it just starts in.
        fromIn = changedAt == .distantPast ? 0 : value(at: now)
        toIn = target
        changedAt = now
    }
}

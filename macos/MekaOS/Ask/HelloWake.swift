@preconcurrency import MekaKit
import AppKit
import AVFoundation
import Observation
import Speech

/// "Hello, Navilife" slice 3 on the Mac: Say “Hello Navilife”. Off by default and kept on this Mac (UserDefaults, never
/// synced), switched in Ask → More → Talk. While on, with Navilife the app in front, the microphone and speech
/// recognition already allowed (never asked for by this) and no conversation running (`HelloWakeRules.listens`), it
/// listens with the Mac's **on-device** speech only (`requiresOnDeviceRecognition`; the words never leave the Mac and
/// nothing is kept), a fresh task every `HelloWakeRules.MAC_WINDOW_MS`, and when `HelloWakeRules.heard` finds the phrase
/// it stops and hands over to the greeting (`CoreModel.requestHello`). `listening` drives the sidebar's
/// "Listening for “Hello Navilife”" line.
///
/// Main actor throughout. The microphone tap runs on the audio thread and touches only the recognition request; the
/// recogniser's callback hops back with plain values (a String, a Bool).
@MainActor
@Observable
final class MacHelloWake {
    static let shared = MacHelloWake()
    static let key = "meka.hello.wake"

    static var enabled: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? HelloWakeRules.shared.DEFAULT_ON
    }

    /// The Mac can recognise speech on the device (else the sheet says so and offers no switch).
    static var available: Bool { SFSpeechRecognizer(locale: Locale(identifier: "en-GB"))?.supportsOnDeviceRecognition ?? false }

    /// Listening for the phrase now (the visible indicator).
    private(set) var listening = false

    @ObservationIgnored private var onWake: (() -> Void)?
    @ObservationIgnored private var appActive = false
    @ObservationIgnored private var talking = false
    @ObservationIgnored private var errors = 0
    @ObservationIgnored private var generation = 0
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-GB"))
    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var window: Task<Void, Never>?

    /// The shell is ready: a heard phrase goes to `onWake` from now on.
    func attach(_ onWake: @escaping () -> Void) {
        self.onWake = onWake
        appActive = NSApp.isActive
        apply()
    }

    /// The app came to the front or went behind another (the app delegate).
    func setAppActive(_ active: Bool) {
        appActive = active
        apply()
    }

    /// A conversation started or ended (Ask's section): never listen for the phrase over one.
    func setTalking(_ active: Bool) {
        talking = active
        apply()
    }

    /// Starts or stops listening to match the setting and the moment (the Talk sheet calls this after switching it).
    func apply() {
        let should = onWake != nil && Self.available && HelloWakeRules.shared.listens(
            on: Self.enabled, micAllowed: MacTalkOnOpen.micAllowed, place: appActive, talking: talking,
            charging: true, quiet: false)
        if should && !listening {
            errors = 0
            listening = true
            listen()
        } else if !should && listening {
            stop()
        }
    }

    private func stop() {
        listening = false
        generation += 1
        endTask()
    }

    private func endTask() {
        window?.cancel()
        window = nil
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if let engine {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        engine = nil
    }

    private func listen() {
        endTask()
        guard listening, let recognizer, recognizer.supportsOnDeviceRecognition, recognizer.isAvailable else { return again(error: true) }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.taskHint = .search
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return again(error: true) }
        Self.tap(input, format: format, into: request)
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            return again(error: true)
        }
        self.engine = engine
        self.request = request
        generation += 1
        let gen = generation
        recognizer.queue = .main
        task = recognizer.recognitionTask(with: request) { result, error in
            let text = result?.bestTranscription.formattedString
            let final = (result?.isFinal ?? false) || error != nil
            // "No speech detected" (1110) and a cancel (216, 301) are a quiet room, not a fault.
            let code = (error as NSError?)?.code ?? 0
            let failed = error != nil && code != 1110 && code != 216 && code != 301
            Task { @MainActor in self.recognised(text, final: final, failed: failed, gen: gen) }
        }
        // The Mac's on-device speech stops a task after about a minute: start a fresh one each window.
        window = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(HelloWakeRules.shared.MAC_WINDOW_MS))
            guard let self, !Task.isCancelled, gen == self.generation, self.listening else { return }
            self.errors = 0
            self.listen()
        }
    }

    private func recognised(_ text: String?, final: Bool, failed: Bool, gen: Int) {
        guard gen == generation, listening else { return }
        if HelloWakeRules.shared.heard(transcript: text) {
            stop()
            onWake?()
            return
        }
        if final { again(error: failed) } else if failed { again(error: true) }
    }

    /// The next listen after a pause, longer with errors in a row; too many and it rests until `apply` starts it again.
    private func again(error: Bool) {
        endTask()
        errors = error ? errors + 1 : 0
        guard let delay = HelloWakeRules.shared.restartDelayMs(errorsInRow: Int32(errors))?.int64Value else {
            listening = false
            return
        }
        generation += 1
        let gen = generation
        window = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(delay))
            guard let self, !Task.isCancelled, gen == self.generation, self.listening else { return }
            self.listen()
        }
    }

    /// The microphone's buffers go to the recogniser only. Nonisolated: the tap runs on the audio thread.
    nonisolated private static func tap(_ input: AVAudioInputNode, format: AVAudioFormat,
                                         into request: SFSpeechAudioBufferRecognitionRequest) {
        nonisolated(unsafe) let req = request
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in req.append(buffer) }
    }
}

/// "Hello, Navilife" slice 3: the Mac's welcome back. On by default and kept on this Mac, switched in Ask → More → Talk.
/// The screen locking or the Mac sleeping marks when he left; unlocking or waking measures how long he was away, and
/// the core decides (`MekaCore.welcomeBackGreets`: two hours or more, once per return, never in quiet hours or protected
/// time, the microphone already allowed). Then Navilife comes forward and greets him ("Welcome back, …"), then listens.
/// Nothing listens while he's away: only the time is noted.
@MainActor
final class MacWelcomeBack {
    static let shared = MacWelcomeBack()
    static let key = "meka.hello.welcomeBack"

    static var enabled: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? WelcomeBackRules.shared.DEFAULT_ON
    }

    private var leftAt: Date?
    private var onReturn: ((Int) -> Void)?
    private var observers: [NSObjectProtocol] = []

    /// The shell is ready: a return goes to `onReturn` with the minutes away.
    func attach(_ onReturn: @escaping (Int) -> Void) {
        self.onReturn = onReturn
        guard observers.isEmpty else { return }
        let ws = NSWorkspace.shared.notificationCenter
        let dist = DistributedNotificationCenter.default()
        observers = [
            ws.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { MacWelcomeBack.shared.left() }
            },
            ws.addObserver(forName: NSWorkspace.screensDidSleepNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { MacWelcomeBack.shared.left() }
            },
            // Woken with no lock to undo (no password asked): the return is the wake itself.
            ws.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    Task { @MainActor in
                        try? await Task.sleep(for: .seconds(2))
                        if !MacWelcomeBack.screenLocked { MacWelcomeBack.shared.returned() }
                    }
                }
            },
            dist.addObserver(forName: Notification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { MacWelcomeBack.shared.left() }
            },
            dist.addObserver(forName: Notification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { MacWelcomeBack.shared.returned() }
            },
        ]
    }

    /// The login window is over the screen now (the session's own flag).
    static var screenLocked: Bool {
        guard let info = CGSessionCopyCurrentDictionary() as? [String: Any] else { return false }
        return (info["CGSSessionScreenIsLocked"] as? Bool) ?? false
    }

    /// Locked or asleep: the first moment counts (a lock then a sleep is one time away).
    private func left() {
        if leftAt == nil { leftAt = Date() }
    }

    /// Unlocked: how long he was away goes to the handler (which asks the core), once.
    private func returned() {
        guard let at = leftAt else { return }
        leftAt = nil
        guard Self.enabled else { return }
        let minutes = Int(min(Date().timeIntervalSince(at) / 60, 100_000))
        onReturn?(minutes)
    }
}

@preconcurrency import MekaKit
import AppKit
import Carbon.HIToolbox
import Observation

/// ⌥Space from any app (MEKA as the default assistant, the Mac's system-wide shortcut): on by default and kept on this
/// Mac (UserDefaults, never synced), switched in Ask → More → Talk → From any app. While on, ⌥Space is registered with
/// macOS as a global hot key (Carbon's `RegisterEventHotKey`, which needs no Accessibility permission and sees only
/// this one key press, never other typing), so pressing it in any app brings MEKA forward on Ask with the orb
/// listening (the same `requestTalk` as the Edit menu's Talk to MEKA; pressed again it ends the conversation). If
/// another app already holds ⌥Space, macOS refuses it: `taken` is set and the sheet says so (the core's
/// `TalkAnywhereRules`), and ⌥Space keeps working while MEKA is in front through the menu. Nothing listens until the
/// key is pressed, and the microphone asks the first time as the mic does.
@MainActor
@Observable
final class GlobalTalkHotKey {
    static let shared = GlobalTalkHotKey()
    static let key = "meka.talk.anywhere"

    /// On unless Meka turned it off (`TalkAnywhereRules.DEFAULT_ON`).
    static var enabled: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? TalkAnywhereRules.shared.DEFAULT_ON
    }

    /// macOS refused ⌥Space because another app holds it.
    private(set) var taken = false

    @ObservationIgnored private var hotKey: EventHotKeyRef?
    @ObservationIgnored private var handler: EventHandlerRef?
    @ObservationIgnored private var onPress: (() -> Void)?

    /// The shell is ready: presses go to `onPress` from now on, and the key is registered per the setting.
    func attach(_ onPress: @escaping () -> Void) {
        self.onPress = onPress
        installHandler()
        apply()
    }

    /// Registers or unregisters ⌥Space to match the setting (the Talk sheet calls this after switching it).
    func apply() {
        if Self.enabled { register() } else { unregister() }
    }

    fileprivate func pressed() {
        NSApp.activate()
        // A window MEKA hid or minimised comes back with it (a closed one returns with a Dock click, as before).
        if !NSApp.windows.contains(where: { $0.isVisible && $0.canBecomeMain }),
           let window = NSApp.windows.first(where: { $0.canBecomeMain }) {
            window.makeKeyAndOrderFront(nil)
        }
        onPress?()
    }

    private func register() {
        guard hotKey == nil else { return }
        var ref: EventHotKeyRef?
        let id = EventHotKeyID(signature: OSType(0x4D45_4B41), id: 1) // "MEKA"
        let status = RegisterEventHotKey(UInt32(kVK_Space), UInt32(optionKey), id, GetApplicationEventTarget(), 0, &ref)
        if status == OSStatus(noErr), let ref {
            hotKey = ref
            taken = false
        } else {
            taken = true
        }
    }

    private func unregister() {
        if let hotKey { _ = UnregisterEventHotKey(hotKey) }
        hotKey = nil
        taken = false
    }

    private func installHandler() {
        guard handler == nil else { return }
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        var ref: EventHandlerRef?
        _ = InstallEventHandler(GetApplicationEventTarget(), globalTalkHotKeyPressed, 1, &spec, nil, &ref)
        handler = ref
    }
}

/// Carbon's callback for the hot key: a C function, so nonisolated; Carbon delivers application events on the main
/// thread, where the press is handed to `GlobalTalkHotKey`. Only Sendable values cross (none, in fact).
nonisolated private func globalTalkHotKeyPressed(
    _ call: EventHandlerCallRef?,
    _ event: EventRef?,
    _ data: UnsafeMutableRawPointer?
) -> OSStatus {
    MainActor.assumeIsolated { GlobalTalkHotKey.shared.pressed() }
    return OSStatus(noErr)
}

@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask → More → Settings → Recovery key on the Mac (Recovery key, slice 3; ADR-005 amendment 2026-10-12), like the
/// Fold's RecoveryKeyPane: whether the household has a key ("Made today on the Fold · …"), and Make: the 24 words in six
/// rows of four, "I've written them down", then three of them typed back from the paper before the key's verifier
/// (never the words) is saved and the old key stops working. While the words or the confirmation are on screen the
/// sheet's window is left out of screenshots and screen sharing (`sharingType = .none`). The draft lives only in this
/// sheet's state: never stored or synced, dropped when the sheet closes, handed to the core once to save.
///
/// Motion: the sheet scale-fades (system); a shimmer until the server answers; the status line cross-fades and blends to
/// the accent while there's no usable key; Make gives a light haptic and the status cross-fades into the words, which
/// fade in row by row (the stagger); "I've written them down" cross-fades to the three fields; Save gives a light haptic
/// once saved and the words cross-fade back to the status ("Saved · keep the paper somewhere safe"); words that don't
/// match or a problem fade and drop in (accent) with a tick haptic. Reduce Motion: cross-fades, no stagger.
struct RecoveryKeySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var draft: RecoveryKeyDraft?
    @State private var confirming = false
    @State private var saved = false
    @State private var answers = ["", "", ""]

    private enum Stage { case status, words, confirm }
    private var stage: Stage { draft == nil ? .status : (confirming ? .confirm : .words) }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Recovery key").font(MekaType.upNextTitle).staggeredAppear(0)
            Group {
                switch stage {
                case .status: status
                case .words: words
                case .confirm: confirm
                }
            }
            .transition(.opacity)
            if let problem = model.recoveryKey?.problem {
                Text(problem).font(MekaType.caption).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack {
                Spacer()
                Button("Done") { close() }.keyboardShortcut(stage == .status ? KeyboardShortcut.defaultAction : KeyboardShortcut.cancelAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .background(HiddenFromCapture(hidden: draft != nil))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: stage)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.recoveryKey?.problem)
        .task { await model.refreshRecoveryKey() }
        .onDisappear { forget() }
    }

    // MARK: The three stages

    @ViewBuilder private var status: some View {
        if let view = model.recoveryKey {
            let line = saved ? RecoveryKeyRules.shared.SAVED : view.line
            Text(line).font(MekaType.body)
                .foregroundStyle(view.lit && !saved ? palette.accent : palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: line)
                .staggeredAppear(1)
            Text(RecoveryKeyRules.shared.HOW).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(1)
            if let warning = view.warning {
                Text(warning).font(MekaType.caption).foregroundStyle(palette.textSecondary).staggeredAppear(2)
            }
            pill(view.action, enabled: !model.recoveryBusy, action: make).staggeredAppear(2)
        } else {
            Text("Checking…").font(MekaType.body).foregroundStyle(palette.textSecondary)
            SkeletonRows(count: 1, palette: palette)
        }
    }

    @ViewBuilder private var words: some View {
        if let d = draft {
            let rows = d.rows
            Text("Your 24 words").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            Text(RecoveryKeyRules.shared.HOW).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    // Each row fades in after the last (the stagger); Reduce Motion: all at once.
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(row, id: \.self) { w in
                            Text(w).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .staggeredAppear(i + 1)
                }
            }
            .textSelection(.disabled)
            .padding(MekaSpace.m)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .accessibilityElement(children: .combine)
            HStack(spacing: MekaSpace.l) {
                pill(RecoveryKeyRules.shared.WRITE_DOWN, enabled: true) {
                    MekaHaptics.light(); confirming = true
                }
                .staggeredAppear(rows.count + 1)
                Button("Not now") { MekaHaptics.tick(); draft = nil }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
        }
    }

    @ViewBuilder private var confirm: some View {
        if let d = draft {
            let labels = d.confirmLabels
            Text(RecoveryKeyRules.shared.CONFIRM).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    // A secure field: no autocorrect or suggestions, and the word isn't kept by the text system.
                    SecureField(label, text: Binding(get: { answers[i] }, set: { answers[i] = String($0.prefix(12)) }))
                        .textFieldStyle(.roundedBorder)
                        .disabled(model.recoveryBusy)
                        .onSubmit { if i == labels.count - 1 { save() } }
                }
                .staggeredAppear(i + 1)
            }
            HStack(spacing: MekaSpace.l) {
                pill(model.recoveryBusy ? "Saving…" : "Save",
                     enabled: !model.recoveryBusy && answers.allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty },
                     action: save)
                    .keyboardShortcut(.defaultAction)
                Button("Show the words again") { MekaHaptics.tick(); confirming = false }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .disabled(model.recoveryBusy)
            }
        }
    }

    private func pill(_ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(enabled ? palette.accent : palette.textTertiary)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surfaceRaised, in: Capsule())
            .disabled(!enabled)
    }

    // MARK: Actions

    private func make() {
        MekaHaptics.light()
        saved = false; confirming = false; answers = ["", "", ""]
        draft = model.makeRecoveryKey()
    }

    private func save() {
        guard let d = draft, !model.recoveryBusy else { return }
        let typed = answers
        Task {
            if await model.saveRecoveryKey(d, answers: typed) {
                MekaHaptics.light()
                saved = true
                forget()
            } else {
                MekaHaptics.tick()
            }
        }
    }

    private func close() { forget(); dismiss() }

    /// Drops the words from this sheet (they are never kept anywhere else).
    private func forget() { draft = nil; confirming = false; answers = ["", "", ""] }
}

/// Leaves the hosting window out of screenshots, screen recording and screen sharing while `hidden` (the recovery
/// key's words are on screen), then puts it back. The Mac's counterpart of the Fold's FLAG_SECURE.
struct HiddenFromCapture: NSViewRepresentable {
    let hidden: Bool

    func makeNSView(context: Context) -> CaptureGuardView { CaptureGuardView() }

    func updateNSView(_ view: CaptureGuardView, context: Context) { view.keepOut = hidden }

    static func dismantleNSView(_ view: CaptureGuardView, coordinator: ()) { view.keepOut = false }
}

/// Applies `keepOut` to its window, including once it is attached to one (after the first layout pass).
final class CaptureGuardView: NSView {
    var keepOut = false { didSet { apply() } }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        apply()
    }

    private func apply() { window?.sharingType = keepOut ? .none : .readOnly }
}

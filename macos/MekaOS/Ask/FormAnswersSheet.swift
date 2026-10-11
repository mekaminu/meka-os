@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// What MEKA knows, slice 4, on the Mac: a form shared through Services → "Handle this with MEKA" gets each field beside
/// the fact that answers it (worked out in the core on this Mac; nothing is sent), like the Fold's capture sheet. Copy
/// puts one fact on the pasteboard, Copy all every "Field: fact" line; the line under them says what was copied.
/// Only Strings go on the pasteboard; nothing crosses back to the core.
///
/// Motion: the sheet scale-fades (system); rows stagger in; the line cross-fades; Copy gives a light haptic.
/// Reduce Motion: cross-fades.
struct FormAnswersSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var copied: String?

    var body: some View {
        let v = model.formAnswers ?? FormFillRules.shared.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(FormFillRules.shared.SHEET_TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            HStack {
                Text(FormFillRules.shared.TITLE.uppercased())
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                    .foregroundStyle(palette.textTertiary)
                Spacer()
                if v.answers.count > 1 {
                    Button(FormFillRules.shared.COPY_ALL) { copy(v.copyAllText, line: FormFillRules.shared.copiedAllLine(view: v)) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("Copy all \(v.answers.count) answers")
                }
            }
            .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(v.answers.enumerated()), id: \.element.field) { i, a in
                        row(a).staggeredAppear(2 + i)
                    }
                }
            }
            .frame(maxHeight: 420)
            Text(copied ?? v.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: copied)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    private func copy(_ text: String, line: String) {
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(text, forType: .string)
        MekaHaptics.light()
        copied = line
    }

    private func row(_ a: FormAnswer) -> some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(a.field).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(a.copyText).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(a.spoken)
            Spacer()
            Button(FormFillRules.shared.COPY) { copy(a.copyText, line: FormFillRules.shared.copiedLine(answer: a)) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel(a.copySpoken)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

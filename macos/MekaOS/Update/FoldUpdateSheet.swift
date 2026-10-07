@preconcurrency import MekaKit
import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Self-updating phone app (build plan M1): publish the APK this Mac built so MEKA on the Fold offers it. Opened by
/// File → Publish Fold Update… (pick the APK) or by tools/publish-fold.sh (mekaos://publish-fold-update?apk=…).
/// Nothing is sent until Publish. Motion: the sheet scale-fades; the summary and the outcome cross-fade; Published
/// pops a check with a light haptic. Reduce Motion: cross-fades only.
struct FoldUpdateSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("Publish to the Fold").font(MekaType.upNextTitle).staggeredAppear(0)
            Group {
                if let done = model.foldUpdateOutcome {
                    HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                        if model.foldUpdatePublished {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.accent)
                                .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
                        }
                        Text(done).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    }
                } else if let summary = model.foldUpdateCheck?.summary {
                    Text(summary).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text("The Fold offers it the next time MEKA opens there, or within 15 minutes. You tap Install once; Android checks it's signed like the app you have.")
                        .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                } else {
                    Text(model.foldUpdateCheck?.problem ?? "Choose the APK to publish.")
                        .font(MekaType.body).foregroundStyle(palette.textSecondary)
                }
            }
            .staggeredAppear(1)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: model.foldUpdateOutcome)

            if model.publishingFoldUpdate {
                SkeletonRows(count: 1, rowHeight: 8, palette: palette).transition(.opacity)
            }

            HStack {
                Button("Choose APK…") { chooseApk() }.disabled(model.publishingFoldUpdate)
                Spacer()
                if model.foldUpdateOutcome != nil {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                } else {
                    Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button("Publish") { Task { await model.publishFoldUpdate(reduced: reduceMotion) } }
                        .keyboardShortcut(.defaultAction)
                        .disabled(model.foldUpdateCheck?.summary == nil || model.publishingFoldUpdate)
                }
            }
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 440)
        .background(palette.surface)
    }

    private func chooseApk() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "apk") ?? .data]
        panel.allowsMultipleSelection = false
        panel.message = "Choose the MEKA APK Gradle built (android/app/build/outputs/apk/debug/app-debug.apk)."
        if panel.runModal() == .OK, let url = panel.url { model.prepareFoldUpdate(path: url.path) }
    }
}

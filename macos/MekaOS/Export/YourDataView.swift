@preconcurrency import MekaKit
import SwiftUI

/// Your data (build plan M1, export and backup): everything on this Mac as one JSON file, saved wherever Meka picks
/// in the save panel. Nothing is sent anywhere. Shown as a sheet (Today's header, File → Export All Data…) and inside
/// the Vault section. Motion: sections stagger in; a shimmer runs while the file is built; Saved pops a check
/// (spring) with a light haptic. Reduce Motion: cross-fades.
struct YourDataSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Index the section's stagger starts at (the Vault's title comes first).
    var firstIndex = 0

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("EXPORT EVERYTHING")
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.textTertiary)
                .staggeredAppear(firstIndex)
            Text("Everything on this Mac as one file you can keep: tasks, steps, lists, renewals, goals, habits, fasts, calendar events and settings. Documents join it when the Vault lands.")
                .font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(firstIndex)
            Group {
                if let s = model.exportSummary {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(s.totalLine).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        if !s.partsLine.isEmpty {
                            Text(s.partsLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .transition(.opacity)
                } else {
                    SkeletonRows(count: 1, rowHeight: 28, palette: palette).transition(.opacity)
                }
            }
            .staggeredAppear(firstIndex + 1)

            if let outcome = model.exportOutcome {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    if model.exportSaved {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.accent)
                            .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
                    }
                    Text(outcome).font(MekaType.itemMeta)
                        .foregroundStyle(model.exportSaved ? palette.textPrimary : palette.critical)
                }
                .transition(.opacity)
            }
            if model.exporting {
                SkeletonRows(count: 1, rowHeight: 8, palette: palette).transition(.opacity)
            }
            HStack {
                Button(model.exportSaved ? "Export Again…" : "Export Everything…") {
                    Task { await model.exportEverything(reduced: reduceMotion) }
                }
                .disabled(model.exporting || model.exportSummary?.total == 0)
                Spacer()
            }
            .staggeredAppear(firstIndex + 2)
            Text("The file isn't encrypted: keep it somewhere only you can open. It's JSON, readable without MEKA.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(firstIndex + 2)
            if let line = model.databaseLine {
                Text(line)
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(firstIndex + 3)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.exporting)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.exportSummary?.total)
        .task { await model.loadExportSummary() }
    }
}

/// The export as a sheet, from Today's header or File → Export All Data… (⇧⌘E).
struct YourDataSheet: View {
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Your data").font(MekaType.upNextTitle).staggeredAppear(0)
            YourDataSection(palette: palette, firstIndex: 1)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
    }
}

/// The Vault section: documents land here in V2; until then it holds the export of everything.
struct VaultScreen: View {
    let palette: MekaPalette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                Text(ShellDestination.vault.label)
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .staggeredAppear(0)
                Text(ShellNav.upcomingLine(.vault) ?? "")
                    .font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(1)
                YourDataSection(palette: palette, firstIndex: 2)
                    .padding(.top, MekaSpace.l)
            }
            .frame(maxWidth: 520, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutterWide)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(palette.background)
    }
}

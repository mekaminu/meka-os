@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask → More → Family on the Mac (family sharing with Jeanette, slice 4), like the Fold's FamilyPane: make Jeanette's
/// private link to the shopping list and send it (the system share menu, or Copy link), see whether she has opened it
/// ("Joined Sat 10 Oct · seen today") and turn it off in one click. The rows are the core's `FamilyRules`; the link just
/// made is held only while the sheet is open (it carries the link's secret), never saved.
///
/// Motion: the sheet scale-fades (system); a shimmer until the server answers; rows stagger in; the summary and each
/// line cross-fade, a waiting link's line lit in the accent; Make a link presses in with a light haptic and "Link made"
/// unfolds on the expand spring; Turn off gives a tick haptic. Reduce Motion: cross-fades.
struct FamilySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The link just made: its name, address and share text (Strings only), while the sheet is open.
    @State private var made: (name: String, url: String, shareText: String, note: String)?
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("Family").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.family {
                Text(view.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: view.summary)
                    .staggeredAppear(1)
                Text(view.shared).font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(1)
                if let problem = view.problem {
                    Text(problem).font(MekaType.caption).foregroundStyle(palette.accent)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(view.rows.enumerated()), id: \.element.id) { i, row in
                        FamilyRowView(row: row, palette: palette, busy: model.familyBusy) {
                            MekaHaptics.tick()
                            let id = row.id
                            Task {
                                await model.turnOffFamily(id)
                                if made != nil, model.family?.canInvite == true { made = nil }
                            }
                        }
                        .staggeredAppear(i + 2)
                    }
                }
                if view.canInvite && made == nil {
                    Button("Make a link for Jeanette") {
                        MekaHaptics.light()
                        Task {
                            if let link = await model.inviteFamily() {
                                withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                                    made = (link.name, link.url, link.shareText, link.note)
                                }
                            }
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surfaceRaised, in: Capsule())
                    .disabled(model.familyBusy)
                    .staggeredAppear(view.rows.count + 2)
                }
            } else {
                Text("Checking…").font(MekaType.body).foregroundStyle(palette.textSecondary)
                SkeletonRows(count: 2, palette: palette)
            }
            if let made {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("Link made for \(made.name)").font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(made.note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: MekaSpace.s) {
                        ShareLink(item: made.shareText) { Text("Share…") }
                            .buttonStyle(MekaPressStyle())
                            .simultaneousGesture(TapGesture().onEnded { MekaHaptics.light() })
                        Button(copied ? "Copied" : "Copy link") {
                            MekaHaptics.light()
                            let board = NSPasteboard.general
                            board.clearContents()
                            board.setString(made.shareText, forType: .string)
                            copied = true
                        }
                        .buttonStyle(MekaPressStyle())
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: copied)
                    }
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                }
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.s)
                .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.family?.problem)
        .task { await model.refreshFamily() }
    }
}

private struct FamilyRowView: View {
    let row: FamilyRow
    let palette: MekaPalette
    let busy: Bool
    let onTurnOff: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    private var dot: Color {
        switch row.state {
        case .joined: palette.success
        case .waiting: palette.accent
        case .off: palette.textTertiary
        }
    }

    var body: some View {
        HStack(spacing: MekaSpace.s) {
            Circle().fill(dot).frame(width: 9, height: 9)
                .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: row.line)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                Text(row.line).font(MekaType.caption).foregroundStyle(row.lit ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
            }
            Spacer()
            if row.canTurnOff {
                Button("Turn off", action: onTurnOff)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.critical)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surfaceRaised, in: Capsule())
                    .disabled(busy)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.s)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.title). \(row.line)")
    }
}

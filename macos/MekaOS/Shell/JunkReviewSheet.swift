import SwiftUI
@preconcurrency import MekaKit

/// Review opens its own list (Meka, 2026-10-17 04:03), slice 1, on the Mac: ↑ Review on an "Empty Junk" card opens this
/// sheet instead of Mail. The people going back to the inbox first (who and why), then every sender going to Trash, the
/// most first (name, domain, how many, two subjects, a "Looks like a scam" chip), each with a tick; clicking a row takes
/// the tick off (their mail stays in Junk; synced with the Fold, Undo on the bar). Approve at the foot counts only what's
/// still ticked. The rows are the core's `MailJunkReviewRules.review`; only Strings and a Bool cross to the core.
///
/// Motion: the sheet scale-fades (system); rows stagger in once; the summary and Approve's label cross-fade as ticks
/// change; a tick's ring blends between brass and the hairline with a tick haptic. Reduce Motion: cross-fades only.
struct JunkReviewSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let r = model.junkReview {
                Text(r.title).font(MekaType.upNextTitle).staggeredAppear(0)
                Text(r.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: r.summary)
                    .staggeredAppear(1)
                Text(r.hint).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(2)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        if !r.rescues.isEmpty {
                            Text("BACK TO YOUR INBOX · \(r.rescues.count)").font(MekaType.captionStrong)
                                .foregroundStyle(palette.textSecondary).padding(.top, MekaSpace.m)
                            ForEach(Array(r.rescues.enumerated()), id: \.offset) { _, x in
                                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                    Text(x.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                                    Text(x.meta).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                    Text(x.why).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                                    ForEach(x.subjects, id: \.self) { sub in
                                        Text("“\(sub)”").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                                    }
                                }
                                .padding(.vertical, MekaSpace.xxs)
                            }
                        }
                        if !r.senders.isEmpty {
                            Text("TO TRASH · \(r.senders.count) SENDERS").font(MekaType.captionStrong)
                                .foregroundStyle(palette.textSecondary).padding(.top, MekaSpace.m)
                            ForEach(Array(r.senders.enumerated()), id: \.element.key) { i, row in
                                JunkSenderRowView(row: row, palette: palette) {
                                    model.keepJunkSender(row.key, keep: !row.kept, title: row.title)
                                }
                                .staggeredAppear(3 + min(i, 5))
                            }
                        }
                        if let stays = r.staysLine {
                            Text(stays).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).padding(.top, MekaSpace.m)
                        }
                        if let part = r.partLine {
                            Text(part).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .scrollIndicators(.never)
                HStack(spacing: MekaSpace.xs) {
                    Button(r.approveLabel) { model.approveJunkReview() }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemTitle)
                        .foregroundStyle(palette.accent)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(palette.surface, in: Capsule())
                        .disabled(!r.canApprove)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: r.approveLabel)
                    Spacer()
                    Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
                }
                .padding(.top, MekaSpace.m)
                Text(r.note).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            } else {
                Text("Empty Junk").font(MekaType.upNextTitle)
                Text("Nothing left to approve here · the clean-out has started or the folder is empty.")
                    .font(MekaType.body).foregroundStyle(palette.textSecondary)
                Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(minWidth: 520, idealWidth: 560, minHeight: 480, idealHeight: 640, alignment: .topLeading)
    }
}

private struct JunkSenderRowView: View {
    let row: JunkReviewRow
    let palette: MekaPalette
    let toggle: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: toggle) {
            HStack(alignment: .top, spacing: MekaSpace.m) {
                ZStack {
                    Circle().fill(row.kept ? palette.surface : palette.accent)
                    Circle().strokeBorder(row.kept ? palette.hairline : palette.accent, lineWidth: 1.5)
                    if !row.kept { Image(systemName: "checkmark").font(MekaType.captionStrong).foregroundStyle(palette.onAccent) }
                }
                .frame(width: 18, height: 18)
                .padding(.top, MekaSpace.xxs)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: row.kept)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack(spacing: MekaSpace.xs) {
                        Text(row.title).font(MekaType.itemTitle)
                            .foregroundStyle(row.kept ? palette.textSecondary : palette.textPrimary)
                        if row.scam {
                            Text("⚠ Looks like a scam").font(MekaType.caption).foregroundStyle(palette.critical)
                                .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                                .overlay(Capsule().strokeBorder(palette.critical, lineWidth: 1))
                        }
                    }
                    Text(row.meta).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    if let scam = row.scamLine { Text(scam).font(MekaType.itemMeta).foregroundStyle(palette.critical) }
                    ForEach(row.subjects, id: \.self) { sub in
                        Text("“\(sub)”").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    Text(row.state).font(MekaType.itemMeta).foregroundStyle(row.kept ? palette.accent : palette.textTertiary)
                        .contentTransition(.opacity)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .padding(.vertical, MekaSpace.xxs)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(row.title), \(row.meta), \(row.state)")
        .accessibilityAddTraits(.isButton)
    }
}

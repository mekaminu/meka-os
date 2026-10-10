@preconcurrency import MekaKit
import SwiftUI

/// "While you were at work" on the Mac (Needs Meka #10, approved 2026-10-07): what the Fold held during work mode,
/// synced through Meka's own server, grouped by person (urgent first, then family, then latest). Clicking a person
/// unfolds everything they sent; Done clears it here and on the Fold. MEKA never replies or marks anything read.
/// Motion: the sheet scale-fades; people sort in with a stagger; a person unfolds in place with the expand spring;
/// Done gives a light haptic. Reduce Motion: cross-fades.
struct AfterWorkSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var open: String?

    var body: some View {
        let summary = model.afterWork
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text(summary?.title ?? "While you were at work").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(summary?.headline ?? "Nothing came in.")
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .staggeredAppear(0)
            if let summary, !summary.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(Array(summary.people.enumerated()), id: \.element.personName) { i, p in
                            person(p).staggeredAppear(i + 1)
                        }
                    }
                }
                .frame(maxHeight: 460)
            }
            Text("Done clears MEKA's copy here and on the Fold. WhatsApp and Messages are untouched.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if summary?.people.contains(where: { $0.items.contains(where: { $0.hasAudio }) }) == true {
                Text(VoiceRecordingRules.shared.privacy(days: model.work?.recordingDays ?? Int32(VoiceRecordingRules.shared.DEFAULT_KEEP_DAYS)))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                if summary?.isEmpty == false {
                    Button("Done") { model.clearAfterWork() }
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 520)
        .background(palette.surface)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }

    private func person(_ p: PersonSummary) -> some View {
        let key = p.items.first?.personKey ?? p.personName
        let expanded = open == key
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.xs) {
                Text(p.personName).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(1)
                if p.urgent {
                    Text("Urgent").font(MekaType.captionStrong).foregroundStyle(palette.critical)
                }
                if p.isFamily {
                    Text("Family").font(MekaType.caption).foregroundStyle(palette.accent)
                }
                Spacer()
                Text(Self.time(p.latestAtMs)).font(MekaType.caption).foregroundStyle(palette.textTertiary).monospacedDigit()
            }
            Text("\(p.line) · \(p.apps.map { $0.label }.joined(separator: ", "))")
                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(p.items, id: \.id) { item in
                        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                            Text(Self.time(item.atMs)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .monospacedDigit().frame(width: 44, alignment: .leading)
                            VStack(alignment: .leading, spacing: 0) {
                                if let group = item.conversation {
                                    Text("in \(group)").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                }
                                Text(item.displayLine)
                                    .font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .textSelection(.enabled)
                                if item.hasAudio {
                                    VoiceMessagePlayerView(id: item.id, palette: palette)
                                }
                            }
                        }
                    }
                }
                .padding(.top, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                if let number = p.blockNumber {
                    blockRow(p, number: number)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            } else if let latest = p.latestText {
                Text(latest).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(2)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .contentShape(Rectangle())
        .onTapGesture {
            MekaHaptics.tick()
            open = expanded ? nil : key
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(expanded ? "Folds away" : "Shows everything they sent")
    }

    /// Block under a held message from someone nobody knows (call assistant polish 8b b, e): Block presses in with a
    /// light haptic and the line cross-fades to "Blocked · their calls won't ring"; the Mac can't text, so it says
    /// what to send to 7726 from the phone (selectable).
    private func blockRow(_ p: PersonSummary, number: String) -> some View {
        let rules = BlockedCallerRules.shared
        let blocked = model.blockedCallers?.has(number: number) == true
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Group {
                if blocked {
                    Text(rules.BLOCKED_LINE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                } else {
                    Button(rules.BLOCK_LABEL) { model.blockHeldCaller(p) }
                        .buttonStyle(MekaPressStyle())
                        .foregroundStyle(palette.critical)
                        .accessibilityLabel("Block \(rules.display(number: number))")
                }
            }
            .contentTransition(.opacity)
            Text(rules.macReportHint(number: number))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.top, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: blocked)
    }

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    static func time(_ ms: Int64) -> String { formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000)) }
}

/// Needs you's after-work card: the summary waiting after work, or how much the Fold is holding during it.
struct AfterWorkCard: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    var body: some View {
        if let summary = model.afterWork, !summary.isEmpty {
            if model.work?.atWork == true {
                if let preview = model.heldPreview {
                    HeldPreviewView(preview: preview, palette: palette)
                } else {
                    Text("\(model.work?.line ?? "At work") · \(summary.itemCount) held for later")
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            } else {
                Button { model.showAfterWork = true } label: {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(summary.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Text(summary.headline).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        if summary.urgentPeople > 0 {
                            Text("\(summary.urgentPeople) urgent").font(MekaType.captionStrong).foregroundStyle(palette.critical)
                        }
                    }
                    .padding(MekaSpace.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .mekaHoverLift()
            }
        }
    }
}

/// "At work · 3 held for later" (Fold review 2026-10-09, item 5): clicking it unfolds what's held in place — sender ·
/// first line · time, newest first, at most five — with the expand spring and a tick haptic; the chevron turns. A
/// preview only: nothing is marked read or cleared (the after-work summary and its Done are unchanged).
/// Reduce Motion: cross-fades.
struct HeldPreviewView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let preview: HeldPreview
    let palette: MekaPalette
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button {
                MekaHaptics.tick()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
            } label: {
                HStack(spacing: MekaSpace.xs) {
                    Text(preview.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.accent)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .disabled(preview.isEmpty)
            .accessibilityHint(open ? HeldPreviewRules.shared.HIDE_HINT : HeldPreviewRules.shared.SHOW_HINT)

            if open && !preview.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(preview.rows.enumerated()), id: \.element.id) { i, r in
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            HStack(spacing: MekaSpace.xs) {
                                Text(r.who).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text("\(r.app.label) · \(r.time)").font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                            }
                            Text(r.line).font(MekaType.body).foregroundStyle(r.urgent ? palette.critical : palette.textSecondary).lineLimit(2)
                        }
                        .accessibilityElement(children: .combine)
                        .staggeredAppear(i)
                    }
                    if let more = preview.moreLine {
                        Text(more).font(MekaType.caption).foregroundStyle(palette.textSecondary).staggeredAppear(preview.rows.count)
                    }
                    Text(preview.caption).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}

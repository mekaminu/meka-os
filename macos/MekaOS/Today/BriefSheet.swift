@preconcurrency import MekaKit
import SwiftUI

/// Morning brief on the Mac (build plan M1): today at a glance (work hours, events and what's planned or due, in time
/// order), what you're waiting on, what needs you on your lists, habits and a running fast. "Got it" puts the card
/// away here and on the Fold until tomorrow morning. Read-only.
/// Motion: sections stagger in; chases due today are lit in the accent colour; Got it pops a check and the sheet goes.
/// Reduce Motion: cross-fades.
struct BriefSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    let palette: MekaPalette
    @State private var closing = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            if let v = model.brief {
                Text("\(v.greeting), Meka").font(MekaType.upNextTitle).staggeredAppear(0)
                Text([v.dateLabel, v.workLine].compactMap { $0 }.joined(separator: " · "))
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        SectionLabel("Today", palette).staggeredAppear(1)
                        Text(v.daySummary).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .staggeredAppear(1)
                        ForEach(v.day, id: \.id) { r in
                            TomorrowLine(row: r, palette: palette).staggeredAppear(1)
                        }

                        if let waitingLine = v.waitingLine {
                            SectionLabel("Waiting on", palette).padding(.top, MekaSpace.l).staggeredAppear(2)
                            Text(waitingLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).staggeredAppear(2)
                            ForEach(v.waiting, id: \.id) { w in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(w.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                                    Text(w.meta).font(MekaType.caption)
                                        .foregroundStyle(w.state == .due ? palette.accent : palette.textTertiary)
                                }
                                .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.xs)
                                .staggeredAppear(2)
                            }
                            if Int(v.waitingTotal) > v.waiting.count {
                                Text("+\(Int(v.waitingTotal) - v.waiting.count) more in Lists")
                                    .font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(2)
                            }
                        }

                        if !v.attention.isEmpty {
                            SectionLabel("On your lists", palette).padding(.top, MekaSpace.l).staggeredAppear(3)
                            ForEach(v.attention, id: \.id) { a in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(a.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                                    if let d = a.detail { Text(d).font(MekaType.caption).foregroundStyle(palette.accent) }
                                }
                                .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.xs)
                                .staggeredAppear(3)
                            }
                        }

                        let extras = [v.habitsLine, v.fastingLine].compactMap { $0 }
                        if !extras.isEmpty {
                            SectionLabel("You", palette).padding(.top, MekaSpace.l).staggeredAppear(4)
                            ForEach(extras, id: \.self) { line in
                                Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).staggeredAppear(4)
                            }
                        }

                        HStack {
                            SectionLabel("Headlines", palette)
                            Spacer()
                            Menu("Topics") {
                                ForEach(v.newsTopics, id: \.id) { t in
                                    Toggle(t.label, isOn: Binding(
                                        get: { t.chosen },
                                        set: { model.setNewsTopic(t.id, on: $0) }
                                    ))
                                }
                            }
                            .menuStyle(.borderlessButton).fixedSize()
                        }
                        .padding(.top, MekaSpace.l).staggeredAppear(5)
                        if !v.newsTopics.contains(where: \.chosen) {
                            Text("No topics chosen. Pick some from Topics.").font(MekaType.caption)
                                .foregroundStyle(palette.textTertiary).staggeredAppear(5)
                        } else if v.headlines.isEmpty {
                            Text("No headlines yet. They're fetched every hour.").font(MekaType.caption)
                                .foregroundStyle(palette.textTertiary).staggeredAppear(5)
                        }
                        ForEach(v.headlines, id: \.id) { h in
                            HeadlineRow(headline: h, palette: palette) { url in openURL(url) }
                                .staggeredAppear(5)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.day.map(\.id))
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.headlines.map(\.id))
                }
                .frame(maxHeight: 460)

                if closing || v.seenToday {
                    HStack(spacing: MekaSpace.s) {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 22)).foregroundStyle(palette.accent)
                        Text("Read for today").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.scale(scale: 0.6).combined(with: .opacity))
                }
            } else {
                Text("Morning brief").font(MekaType.upNextTitle)
                SkeletonRows(count: 4, palette: palette)
            }

            HStack {
                Spacer()
                if model.brief?.seenToday == true || closing {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                } else {
                    Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button("Got it") { gotIt() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
    }

    /// The check pops (a spring) with a light haptic, holds a moment, then the sheet goes.
    private func gotIt() {
        withAnimation(reduceMotion ? MekaMotion.appear(reduced: true) : .spring(response: 0.35, dampingFraction: 0.6)) { closing = true }
        MekaHaptics.light()
        Task { @MainActor in
            await model.briefSeen()
            try? await Task.sleep(for: .milliseconds(600))
            dismiss()
        }
    }
}

/// A headline: the title, then "BBC News · World · 2 h ago". Clicking opens the article in the browser (https only).
private struct HeadlineRow: View {
    let headline: BriefHeadline
    let palette: MekaPalette
    let open: (URL) -> Void
    @State private var hovering = false

    private var url: URL? {
        guard let s = headline.url, let u = URL(string: s), u.scheme?.lowercased() == "https" else { return nil }
        return u
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(headline.title).font(MekaType.itemTitle)
                .foregroundStyle(hovering && url != nil ? palette.accent : palette.textPrimary)
            Text(headline.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { if let url { open(url) } }
        .help(url == nil ? "" : "Open in your browser")
        .accessibilityAddTraits(url == nil ? AccessibilityTraits() : .isLink)
    }
}

/// The morning card in Today: "Morning brief" with the day in one line.
struct BriefCard: View {
    @Environment(CoreModel.self) private var model
    let brief: MorningBriefView
    let palette: MekaPalette

    var body: some View {
        Button { model.showBrief = true } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Morning brief").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(brief.cardLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(MekaSpace.l)
            .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
    }
}

@preconcurrency import MekaKit
import SwiftUI

/// Morning brief on the Mac (build plan M1): today at a glance (work hours, events and what's planned or due, in time
/// order), what you're waiting on, what needs you on your lists, habits and a running fast. "Got it" puts the card
/// away here and on the Fold until tomorrow morning; so does closing the sheet once it was open long enough to read
/// (Calm Today). Read-only.
/// Motion: sections stagger in; chases due today are lit in the accent colour; Got it pops a check and the sheet goes.
/// Reduce Motion: cross-fades.
struct BriefSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    let palette: MekaPalette
    @State private var closing = false
    /// When the sheet appeared: closing it after `BriefRules.READ_AFTER_MS` counts as read (Calm Today).
    @State private var openedAt = Date()
    /// Listen (Weather and a voice, slice 8): MEKA reads the brief aloud in its voice, else the Mac's own.
    @State private var speaker = MekaSpeaker()

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            if let v = model.brief {
                Text("\(v.greeting), Meka").font(MekaType.upNextTitle).staggeredAppear(0)
                Text([v.dateLabel, v.workLine].compactMap { $0 }.joined(separator: " · "))
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(0)
                // Today's weather (Weather slice 2): "9–15°, light rain from 15:00 — take a coat".
                if let weather = v.weatherLine {
                    Text(weather).font(MekaType.caption).foregroundStyle(palette.textSecondary).staggeredAppear(0)
                }
                Button { toggleListen(v) } label: {
                    Text(speaker.speaking ? "■  Stop" : "▶  Listen")
                        .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .contentTransition(.opacity)
                }
                .buttonStyle(.borderless)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: speaker.speaking)
                .help(speaker.speaking ? "Stop reading" : "Read the brief aloud")
                .accessibilityLabel(speaker.speaking ? "Stop reading the brief" : "Read the brief aloud")
                .staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        SectionLabel("Today", palette).staggeredAppear(1)
                        Text(v.daySummary).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .staggeredAppear(1)
                        // Fold review 2026-10-09 07:26, item 7: titles on one left edge in the regular weight, a
                        // tick circle for a task (completes it like Today's rows), a dot for an event.
                        ForEach(v.dayLines, id: \.id) { r in
                            BriefDayRow(line: r, palette: palette).staggeredAppear(1)
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
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.dayLines.map(\.id))
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
        .onAppear { speaker.attach(model); openedAt = Date() }
        .onDisappear {
            speaker.stop()
            // Calm Today: open long enough to read counts as read, so Today's card folds away (a glance leaves it).
            model.briefClosed(openFor: Date().timeIntervalSince(openedAt))
        }
    }

    /// Listen reads the brief aloud (light haptic); Stop ends it (tick haptic).
    private func toggleListen(_ v: MorningBriefView) {
        if speaker.speaking {
            MekaHaptics.tick()
            speaker.stop()
        } else {
            MekaHaptics.light()
            speaker.say(BriefSpeech.shared.script(v: v, name: "Meka"), reading: true)
        }
    }

    /// The check pops (a spring) with a light haptic, holds a moment, then the sheet goes.
    private func gotIt() {
        withAnimation(reduceMotion ? MekaMotion.appear(reduced: true) : .spring(response: 0.35, dampingFraction: 0.6)) { closing = true }
        MekaHaptics.light()
        speaker.stop()
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

/// "Brief read on your Fold · Open": the card's quiet stand-in once the brief was read on the other device.
struct BriefReadLineView: View {
    @Environment(CoreModel.self) private var model
    let line: String
    let palette: MekaPalette

    var body: some View {
        Button { model.showBrief = true } label: {
            HStack(spacing: 0) {
                Text(line + " · ").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text("Open").font(MekaType.caption).foregroundStyle(palette.accent)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(line + ". Open the morning brief")
    }
}

/// The morning card in Today: "Morning brief" with the day in one line.
struct BriefCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let brief: MorningBriefView
    let palette: MekaPalette

    var body: some View {
        Button { model.showBrief = true } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Morning brief").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                // "16° · drizzle from 15:00 · 2 tasks · Barça v Getafe tomorrow 17:30" (Fold review 2026-10-09 07:26, item 4);
                // it cross-fades as the weather or the day changes.
                Text(brief.cardLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: brief.cardLine)
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

/// One row of the brief's Today section (Fold review 2026-10-09 07:26, item 7; core `BriefDayLine`): a 22 pt lead (a
/// task's tick circle, which completes it with the ring-and-check draw, or an event's small dot), then the title in
/// `body` and the caption (time and detail; an overdue task's lit in the accent). No time column.
struct BriefDayRow: View {
    let line: BriefDayLine
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
            Group {
                if let taskId = line.taskId {
                    CompleteButton(id: taskId, title: line.title, palette: palette)
                } else {
                    Circle().fill(palette.textTertiary).frame(width: 6, height: 6)
                        .frame(width: 22, height: 22)
                }
            }
            .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 5 }
            VStack(alignment: .leading, spacing: 2) {
                Text(line.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let c = line.caption {
                    Text(c).font(MekaType.caption).foregroundStyle(line.lit ? palette.accent : palette.textTertiary)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(line.spoken)
            Spacer(minLength: 0)
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

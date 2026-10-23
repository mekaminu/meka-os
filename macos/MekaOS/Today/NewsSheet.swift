@preconcurrency import MekaKit
import SwiftUI

/// News on the Mac (build plan M1, "News ticker + AI/tech sources", slice 1): Ask → More → News. The chosen topics as
/// lanes, Barça first, then AI, then the rest, each story once with its source and age; a Topics menu (synced with
/// the brief's and the Fold's). Clicking a story pushes its detail across: title, source and time, the feed's own
/// summary as plain text, "Read full story" (opens the browser, https only), Previous/Next (buttons or ← →), Back (Esc).
/// Headlines are untrusted (ADR-006): text only.
/// Motion: the sheet scale-fades; lanes stagger in; the detail pushes in from the right and Previous/Next push the
/// way you moved with a tick haptic. Reduce Motion: cross-fades.
/// Slice 2: on matchday the list leads with the fixture in Barça's colour ("Barça v Real Madrid · 21:00 · in 3 h",
/// cross-fading as time moves on), the Barça lane wears that colour too, and the command centre can open the sheet
/// straight onto a story (`model.newsStoryId`).
/// Pictures (images slice): each story shows the picture its feed names, made small by the server and fetched from it
/// by key (`NewsThumb`); the detail leads with it. A tile with the source's initial stands in until it arrives, and the
/// picture cross-fades in over it (Reduce Motion: a short fade).
/// Full news hub, slice 1: a brass dot marks a story not read yet; opening it marks it read on both apps (dot and title
/// fade to the quieter colours); "12 new · 6 read" with Mark all read (Undo for 6 s); each lane's name says "3 new";
/// in a story, Save for the train puts its link in the train queue.
/// Slice 2: Topics → Sources lists every source in the chosen topics with its story count, a switch each (synced; off
/// leaves its stories out of News, the ticker and the brief), and a chosen topic with nothing to show says why after
/// the lanes ("AI · its sources are off").
/// Slice 3: Follow — a field under the captions takes a team, a person or a word ("Arsenal, Lamine Yamal"); stories
/// naming one lead News in a Following lane whatever their topic, and reach the ticker and the brief on both apps.
/// Topics → Following lists each with its count; choosing one stops following it, with Undo.
struct NewsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    let palette: MekaPalette
    @State private var openId: String?
    @State private var forward = true
    @State private var marked: String?
    @State private var trainLine: CoreModel.QueueSaid?
    @State private var followText = ""
    @State private var followNote: String?
    @State private var unfollowed: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let place = model.newsPlace {
                if let id = openId, let d = place.detail(id: id) {
                    detail(d)
                        .id(d.item.id)
                        .transition(reduceMotion ? .opacity : .asymmetric(
                            insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
                            removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)))
                } else {
                    list(place).transition(reduceMotion ? .opacity : .move(edge: .leading).combined(with: .opacity))
                }
            } else {
                Text("News").font(MekaType.upNextTitle)
                SkeletonRows(count: 4, palette: palette)
                HStack { Spacer(); Button("Close") { dismiss() }.keyboardShortcut(.cancelAction) }
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 520, height: 600, alignment: .top)
        .clipped()
        .onAppear {
            if let id = model.newsStoryId { forward = true; openId = id; model.newsStoryId = nil }
        }
        // Full news hub, slice 5: which feeds aren't coming through, named after the lanes and on their switches.
        .task { await model.refreshNewsSources() }
    }

    /// Full news hub, slice 3: the Follow field, and its line (what following does, or what just changed, with Undo).
    @ViewBuilder
    private func followRow(_ place: NewsPlace) -> some View {
        if place.canFollowMore {
            TextField("Follow a team, person or word…", text: $followText)
                .textFieldStyle(.roundedBorder).font(MekaType.body)
                .onSubmit {
                    let typed = followText
                    guard !typed.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                    followText = ""
                    Task { unfollowed = nil; followNote = await model.newsFollow(typed) }
                }
        }
        HStack {
            Text(LoneWordGlue.shared.kept(line: followNote ?? place.followsLine)).font(MekaType.caption)
                .foregroundStyle(followNote != nil ? palette.accent : palette.textTertiary)
                .contentTransition(.opacity)
            Spacer()
            if let name = unfollowed, followNote != nil {
                Button("Undo") {
                    Task { _ = await model.newsFollow(name); unfollowed = nil; followNote = nil }
                }
                .buttonStyle(.borderless).foregroundStyle(palette.accent)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: followNote)
        .task(id: followNote) {
            guard followNote != nil else { return }
            try? await Task.sleep(for: .seconds(6))
            followNote = nil; unfollowed = nil
        }
    }

    private func unfollow(_ name: String) {
        Task {
            if await model.newsUnfollow(name) { unfollowed = name; followNote = "Stopped following " + name }
        }
    }

    @ViewBuilder
    private func list(_ place: NewsPlace) -> some View {
        HStack {
            Text("News").font(MekaType.upNextTitle).staggeredAppear(0)
            Spacer()
            Menu("Topics") {
                ForEach(place.topics, id: \.id) { t in
                    Toggle(t.label, isOn: Binding(get: { t.chosen }, set: { model.setNewsTopic(t.id, on: $0) }))
                }
                // English only by default (Fold review 2026-10-09 07:26): Spanish sources are a switch, off.
                Divider()
                Toggle("Spanish sources", isOn: Binding(get: { place.spanishSources }, set: { model.setNewsSpanish(on: $0) }))
                // Full news hub, slice 2: a switch per source in the chosen topics (off leaves its stories out of
                // News, the ticker and the brief on both apps).
                if !place.sources.isEmpty {
                    Divider()
                    Menu("Sources") {
                        ForEach(place.sources, id: \.key) { src in
                            let name = src.name
                            Toggle(src.label, isOn: Binding(get: { src.on }, set: { model.setNewsSource(name, on: $0) }))
                        }
                    }
                }
                // Full news hub, slice 3: what he follows; choosing one stops following it.
                if !place.follows.isEmpty {
                    Divider()
                    Menu("Following") {
                        ForEach(place.follows, id: \.key) { f in
                            let name = f.name
                            Button("Stop following " + f.label) { unfollow(name) }
                        }
                    }
                }
            }
            .menuStyle(.borderlessButton).fixedSize()
        }
        Text(LoneWordGlue.shared.kept(line: "Spanish sources: " + place.spanishLine + " · " + place.sourcesCaption))
            .font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(0)
            .contentTransition(.opacity).animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: place.spanishSources)
        if let line = place.sourcesLine {
            Text(LoneWordGlue.shared.kept(line: "Sources: " + line)).font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(0)
                .contentTransition(.opacity).animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: line)
        }
        followRow(place).staggeredAppear(0)
        if let line = place.unreadLine {
            HStack {
                Text(LoneWordGlue.shared.kept(line: marked ?? line)).font(MekaType.itemMeta)
                    .foregroundStyle(marked != nil ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                Spacer()
                if marked != nil {
                    Button("Undo") { model.newsUndoAllRead(); marked = nil }.buttonStyle(.borderless).foregroundStyle(palette.accent)
                } else if place.canMarkAllRead {
                    Button("Mark all read") {
                        Task {
                            let n = await model.newsMarkAllRead()
                            if n > 0 { marked = "Marked \(n) read" }
                        }
                    }
                    .buttonStyle(.borderless).foregroundStyle(palette.accent)
                    .keyboardShortcut("k", modifiers: [.command, .shift])
                    .help("Marks every story here read, on both apps (⇧⌘K)")
                }
            }
            .animation(MekaMotion.appear(reduced: reduceMotion), value: marked)
            .staggeredAppear(0)
            .task(id: marked) {
                guard marked != nil else { return }
                try? await Task.sleep(for: .seconds(6))
                marked = nil
            }
        }
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                if let md = place.matchday {
                    matchday(md).staggeredAppear(1)
                }
                if let line = place.emptyLine {
                    Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).staggeredAppear(1)
                }
                ForEach(Array(place.lanes.enumerated()), id: \.element.topicId) { i, lane in
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        if lane.isBarca {
                            Text(lane.label.uppercased() + (lane.unreadLabel.map { " · " + $0.uppercased() } ?? ""))
                                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                .foregroundStyle(palette.barca)
                                .padding(.bottom, MekaSpace.xxs)
                        } else {
                            HStack(spacing: 0) {
                                SectionLabel(lane.label, palette)
                                if let u = lane.unreadLabel {
                                    Text(" · " + u.uppercased())
                                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                        .foregroundStyle(palette.accent)
                                }
                            }
                        }
                        if !lane.sources.isEmpty {
                            Text(LoneWordGlue.shared.kept(line: lane.sources)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        }
                    }
                    .padding(.top, i == 0 ? 0 : MekaSpace.l)
                    .staggeredAppear(1 + i)
                    ForEach(lane.items, id: \.id) { n in
                        StoryRow(item: n, palette: palette) { open(n.id, forward: true) }.staggeredAppear(1 + i)
                    }
                }
                // Full news hub, slice 2: a chosen topic with nothing to show says why.
                if !place.quiet.isEmpty {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        ForEach(place.quiet, id: \.self) { line in
                            Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        }
                    }
                    .padding(.top, MekaSpace.l)
                    .staggeredAppear(1 + place.lanes.count)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: place.items.map(\.id))
        }
        HStack { Spacer(); Button("Done") { dismiss() }.keyboardShortcut(.defaultAction) }
    }

    /// Matchday: "MATCHDAY" (or "ON NOW") in Barça's colour over the fixture's line; the line cross-fades as it changes.
    private func matchday(_ md: NewsMatchday) -> some View {
        HStack(spacing: MekaSpace.xs) {
            Circle().fill(palette.barca).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(md.live ? "ON NOW" : "MATCHDAY")
                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                    .foregroundStyle(palette.barca)
                Text(md.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .id(md.line)
                    .transition(.opacity)
            }
            .animation(MekaMotion.appear(reduced: reduceMotion), value: md.line)
            Spacer(minLength: 0)
        }
        .padding(MekaSpace.m)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .padding(.bottom, MekaSpace.m)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func detail(_ d: NewsDetail) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Button("‹ News") { close() }.buttonStyle(.borderless).foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Text(d.position).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if d.item.imageKey != nil {
                        NewsThumb(item: d.item, palette: palette, corner: MekaRadius.m, initialFont: MekaType.upNextTitle)
                            .aspectRatio(16.0 / 9.0, contentMode: .fit)
                            .frame(maxWidth: .infinity)
                            .padding(.bottom, MekaSpace.xs)
                    }
                    Text(d.item.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(LoneWordGlue.shared.kept(line: d.item.meta)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    // Quality gate slice 43: without a link it never says to read the full story (there's no button).
                    Text(d.item.summary ?? LoneWordGlue.shared.kept(line: NewsRules.shared.noSummary(source: d.item.source, hasLink: d.item.url != nil)))
                        .font(MekaType.body)
                        .foregroundStyle(d.item.summary != nil ? palette.textPrimary : palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.xs)
                    if let url = Self.safeURL(d.item.url) {
                        Button("Read full story") { openURL(url) }
                            .buttonStyle(.borderedProminent).tint(palette.accent)
                            .padding(.top, MekaSpace.m)
                            .help("Opens \(d.item.source) in your browser")
                        // Save for the train (full news hub, slice 1): the link into the train queue, on both apps.
                        Group {
                            if let t = trainLine, t.refused {
                                Text(t.line).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            } else if d.item.queued {
                                Text("✓ In your train queue" + (trainLine == nil ? " · Ask → Daily → Train queue" : ""))
                                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            } else {
                                Button("Save for the train") {
                                    let id = d.item.id
                                    Task { trainLine = await model.newsForTrain(id) }
                                }
                                .help("Puts this story's link in your train queue, on both apps")
                            }
                        }
                        .transition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: d.item.queued)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .task(id: d.item.id) { trainLine = nil; model.newsOpened(d.item.id) }
            HStack {
                Button("‹ Previous") { if let p = d.previousId { open(p, forward: false) } }
                    .disabled(d.previousId == nil).keyboardShortcut(.leftArrow, modifiers: [])
                Spacer()
                Button("Next ›") { if let n = d.nextId { open(n, forward: true) } }
                    .disabled(d.nextId == nil).keyboardShortcut(.rightArrow, modifiers: [])
            }
        }
    }

    private func open(_ id: String, forward: Bool) {
        MekaHaptics.tick()
        self.forward = forward
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { openId = id }
    }

    private func close() {
        forward = false
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { openId = nil }
    }

    /// Only plain https links are opened (the core already drops anything else).
    static func safeURL(_ s: String?) -> URL? {
        guard let s, let u = URL(string: s), u.scheme?.lowercased() == "https", u.host != nil else { return nil }
        return u
    }
}

/// A story: a small dot (Barça's colour for Barça), the title in the regular weight (news is context), "Sport · 2 h ago",
/// and its picture on the right.
private struct StoryRow: View {
    let item: NewsItem
    let palette: MekaPalette
    let open: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var hovering = false

    var body: some View {
        HStack(alignment: .top, spacing: MekaSpace.xs) {
            // Full news hub, slice 1: brass (Barça's colour) while new, the quiet colour once read; the change blends.
            Circle().fill(item.read ? palette.textTertiary.opacity(0.4) : (item.topic == "barca" ? palette.barca : palette.accent))
                .frame(width: 6, height: 6)
                .padding(.top, MekaSpace.xs)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: item.read)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(item.title).font(MekaType.body)
                    .foregroundStyle(hovering ? palette.accent : (item.read ? palette.textSecondary : palette.textPrimary))
                    .fixedSize(horizontal: false, vertical: true)
                Text(LoneWordGlue.shared.kept(line: item.meta)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer(minLength: MekaSpace.xs)
            NewsThumb(item: item, palette: palette).frame(width: 72, height: 54).padding(.top, 2) // rhythm: ok (lines the picture up with the title's first line)
        }
        .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { open() }
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the story")
        .accessibilityValue(item.read ? "" : "New")
    }
}

/// A story's picture: the server's small JPEG (fetched by key through the core, kept in memory), over an accent-tinted
/// tile with the source's short name ("MD", "BBC", "TC"; Barça's colour on Barça stories) that shows until it arrives
/// or when there is none. Decorative:
/// the row already says the title and source.
struct NewsThumb: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let item: NewsItem
    let palette: MekaPalette
    var corner: CGFloat = MekaRadius.s
    var initialFont: Font = MekaType.itemTitle
    @State private var picture: NSImage?

    var body: some View {
        ZStack {
            Rectangle().fill(palette.surfaceRaised)
            if let picture {
                Image(nsImage: picture).resizable().scaledToFill()
                    .transition(.opacity)
            } else {
                let tint = item.topic == "barca" ? palette.barca : palette.accent
                ZStack {
                    Rectangle().fill(tint.opacity(0.16))
                    Text(item.tileMark).font(initialFont).foregroundStyle(tint).lineLimit(1).fixedSize()
                }
                .transition(.opacity)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: picture != nil)
        .accessibilityHidden(true)
        .task(id: item.imageKey) {
            picture = await model.newsImage(item.imageKey)
        }
    }
}

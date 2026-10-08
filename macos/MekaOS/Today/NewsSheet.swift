@preconcurrency import MekaKit
import SwiftUI

/// News on the Mac (build plan M1, "News ticker + AI/tech sources", slice 1): Ask → More → News. The chosen topics as
/// lanes, Barça first, then AI, then the rest, each story once with its source and age; a Topics menu (synced with
/// the brief's and the Fold's). Clicking a story pushes its detail across: title, source and time, the feed's own
/// summary as plain text, "Read full story" (opens the browser, https only), Previous/Next (buttons or ← →), Back (Esc).
/// Headlines are untrusted (ADR-006): text only.
/// Motion: the sheet scale-fades; lanes stagger in; the detail pushes in from the right and Previous/Next push the
/// way you moved with a tick haptic. Reduce Motion: cross-fades.
struct NewsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    let palette: MekaPalette
    @State private var openId: String?
    @State private var forward = true

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
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
            }
            .menuStyle(.borderlessButton).fixedSize()
        }
        Text("Barça: Mundo Deportivo, Sport and Google News · AI: The Verge, TechCrunch, MIT Technology Review and OpenAI · "
             + "Tech news: Hacker News (200+ points) · the rest: BBC News. Refreshed hourly by your server; nothing about you is sent.")
            .font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(0)
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                if let line = place.emptyLine {
                    Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).staggeredAppear(1)
                }
                ForEach(Array(place.lanes.enumerated()), id: \.element.topicId) { i, lane in
                    VStack(alignment: .leading, spacing: 2) {
                        SectionLabel(lane.label, palette)
                        if !lane.sources.isEmpty {
                            Text(lane.sources).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        }
                    }
                    .padding(.top, i == 0 ? 0 : MekaSpace.l)
                    .staggeredAppear(1 + i)
                    ForEach(lane.items, id: \.id) { n in
                        StoryRow(item: n, palette: palette) { open(n.id, forward: true) }.staggeredAppear(1 + i)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: place.items.map(\.id))
        }
        HStack { Spacer(); Button("Done") { dismiss() }.keyboardShortcut(.defaultAction) }
    }

    @ViewBuilder
    private func detail(_ d: NewsDetail) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            HStack {
                Button("‹ News") { close() }.buttonStyle(.borderless).foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Text(d.position).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.s) {
                    Text(d.item.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(d.item.meta).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    Text(d.item.summary ?? "No summary from \(d.item.source). Read the full story for the details.")
                        .font(MekaType.body)
                        .foregroundStyle(d.item.summary != nil ? palette.textPrimary : palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.s)
                    if let url = Self.safeURL(d.item.url) {
                        Button("Read full story") { openURL(url) }
                            .buttonStyle(.borderedProminent).tint(palette.accent)
                            .padding(.top, MekaSpace.m)
                            .help("Opens \(d.item.source) in your browser")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
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

/// A story: a small dot (accent for Barça), the title in the regular weight (news is context), "Sport · 2 h ago".
private struct StoryRow: View {
    let item: NewsItem
    let palette: MekaPalette
    let open: () -> Void
    @State private var hovering = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.s) {
            Circle().fill(item.topic == "barca" ? palette.accent : palette.textTertiary).frame(width: 6, height: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(MekaType.body)
                    .foregroundStyle(hovering ? palette.accent : palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(item.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { open() }
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the story")
    }
}

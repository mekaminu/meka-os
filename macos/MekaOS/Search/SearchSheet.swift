@preconcurrency import MekaKit
import SwiftUI

/// Search everything on the Mac (build plan M1): tasks, calendar, Waiting for, decisions, renewals, Someday, goals,
/// habits and done tasks, searched on this Mac over what is already synced (the same rules as the Fold, in the core).
/// An open task shows its detail beside the results; a done one offers Reopen; a list item, goal or habit opens its
/// section with its row unfolded; calendar events are shown, not opened. ⌘F opens it from anywhere.
/// Motion: groups stagger in; rows glide as results narrow while typing; the detail pushes across to the chosen task.
/// Reduce Motion: cross-fades.
struct SearchSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var query = ""
    @State private var chosen: String?
    @State private var unfolded: String?
    @FocusState private var fieldFocused: Bool

    private var view: SearchView? { model.searchResults }

    /// The chosen open task, fresh from the latest results (it follows edits; nil once it leaves them).
    private var chosenTask: MekaTask? {
        guard let chosen, let v = view else { return nil }
        return v.hits.first { $0.id == chosen && $0.target == .task }?.task
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("Search").font(MekaType.upNextTitle).staggeredAppear(0)
            TextField("Search everything…", text: $query)
                .textFieldStyle(.roundedBorder)
                .font(MekaType.body)
                .focused($fieldFocused)
                .onSubmit { if let first = view?.hits.first { choose(first) } }
                .staggeredAppear(0)
            Text(summary)
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .staggeredAppear(1)
            HSplitView {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(Array((view?.groups ?? []).enumerated()), id: \.element.kind) { i, g in
                            SectionLabel(g.label, palette).padding(.top, MekaSpace.s).staggeredAppear(min(i + 2, 8))
                            ForEach(g.hits, id: \.id) { hit in
                                SearchRow(hit: hit, chosen: chosen == hit.id, unfolded: unfolded == hit.id, palette: palette,
                                          onTap: { choose(hit) }, onReopen: { unfolded = nil; model.reopen(hit.id) })
                                    .staggeredAppear(min(i + 2, 8))
                            }
                            if let more = g.moreLine {
                                Text(more).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                        }
                    }
                    .padding(.vertical, MekaSpace.xs)
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: view?.hits.map(\.id))
                }
                .frame(minWidth: 380, idealWidth: 460)
                DetailView(task: chosenTask, palette: palette)
                    .frame(minWidth: 280, idealWidth: 320)
            }
            HStack {
                Spacer()
                Button("Close") { close() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 860, height: 600)
        .background(palette.background)
        .onAppear {
            fieldFocused = true
            // "Search for …" from the command bar opens the sheet with what was typed there.
            if let seed = model.searchSeed { query = seed; model.searchSeed = nil }
            model.search(query)
        }
        .task(id: query) {
            if !query.isEmpty { try? await Task.sleep(for: .milliseconds(120)) }
            guard !Task.isCancelled else { return }
            unfolded = nil
            model.search(query)
        }
    }

    private var summary: String {
        let s = view?.summary ?? ""
        return s.isEmpty ? "Tasks, calendar, lists, renewals, goals and habits. Searched on this Mac; nothing leaves it." : s
    }

    private func choose(_ hit: SearchHit) {
        if hit.kind == .done {
            MekaHaptics.tick()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { unfolded = unfolded == hit.id ? nil : hit.id }
        } else if hit.target == .task {
            withAnimation(MekaMotion.replan(reduced: reduceMotion)) { chosen = hit.id }
        } else if SearchNav.destination(hit.target) != nil {
            model.open(hit, reduced: reduceMotion)
        }
    }

    private func close() {
        model.search("")
        dismiss()
    }
}

/// A result: the title, its line ("Planned today 14:00 · ↻ Every weekday") and, for a match in the notes, a snippet.
private struct SearchRow: View {
    let hit: SearchHit
    let chosen: Bool
    let unfolded: Bool
    let palette: MekaPalette
    let onTap: () -> Void
    let onReopen: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false

    private var tappable: Bool { hit.kind == .done || hit.target == .task || SearchNav.destination(hit.target) != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(hit.title).font(MekaType.itemTitle)
                .foregroundStyle(hit.kind == .done ? palette.textSecondary : (hovering && tappable ? palette.accent : palette.textPrimary))
                .lineLimit(2)
            if let detail = hit.detail {
                Text(detail).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(2)
            }
            if let snippet = hit.snippet {
                Text(snippet).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(2)
            }
            if unfolded {
                Button("Reopen") { onReopen() }
                    .buttonStyle(.plain).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    .padding(.top, MekaSpace.xxs)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.vertical, MekaSpace.xs).padding(.horizontal, MekaSpace.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(chosen ? palette.surfaceRaised : palette.surface))
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { if tappable { onTap() } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(tappable ? .isButton : AccessibilityTraits())
        .accessibilityHint(hit.kind == .done ? "Shows Reopen" : (SearchNav.hint(hit.target) ?? ""))
    }
}

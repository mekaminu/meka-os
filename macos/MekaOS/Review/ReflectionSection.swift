@preconcurrency import MekaKit
import SwiftUI

/// Weekly reflection in Review on the Mac (V1, slice 1), like the Fold's: "Reflect on your week" — what went well, what
/// to drop, one thing that matters next week — each a multi-line field (Dictation works in it) with suggestions from the
/// week's facts a click adds; Save keeps it as the week's dated entry, can keep the went-well lines in Wins and make the
/// one thing's goal one of next week's goals. Open and lit from Sunday 18:00 through Monday; otherwise folded behind
/// "Reflect now". Earlier weeks are in "Your reflections". Only Strings and Bools cross to the core.
///
/// Motion: the form unfolds/folds on the expand spring (the chevron turns); a suggestion lights as it is added; the
/// lines cross-fade; Save folds the form into the saved answers; the journal unfolds its weeks, staggered.
/// Reduce Motion: cross-fades.
struct ReflectionSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let reflection: ReflectionView
    let palette: MekaPalette
    @State private var open = false
    @State private var editing = false
    @State private var wentWell = ""
    @State private var drop = ""
    @State private var matters = ""
    @State private var goalId: String?
    @State private var keepWins = true
    @State private var said: (week: Int64, said: CoreModel.ReflectionSaid)?
    @State private var journalOpen = false
    @State private var removed: (week: Int64, label: String)?

    private var r: ReflectionView { reflection }
    private var folds: Bool { !r.saved && !r.skipped && !r.asking }
    private var formShown: Bool { (open || r.asking) && (!r.saved || editing) && !r.skipped }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                SectionLabel("Reflect", palette)
                header
                Text(r.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: r.line)
                Text(r.facts).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                if r.skipped {
                    Button("Reflect now") { model.skipReflection(false); open = true }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                if r.saved && !editing {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        answer(r.wentWellPrompt, r.wentWell)
                        answer(r.dropPrompt, r.drop)
                        answer(r.mattersPrompt, r.matters)
                        Button("Edit") {
                            MekaHaptics.tick()
                            wentWell = r.wentWell; drop = r.drop; matters = r.matters
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { editing = true }
                        }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
                if formShown {
                    form.transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
                if let s = said, s.week == r.weekStart {
                    Text(s.said.line).font(MekaType.caption)
                        .foregroundStyle(s.said.refused ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: s.said)
                }
            }
            .padding(MekaSpace.m)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.l))
            if !r.journal.isEmpty || removed != nil { journal }
        }
        .padding(.top, MekaSpace.l)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: formShown)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: r.saved)
        .onChange(of: r.weekStart) { reset() }
    }

    private func reset() {
        open = false; editing = false; wentWell = ""; drop = ""; matters = ""; goalId = nil; keepWins = true
    }

    @ViewBuilder
    private var header: some View {
        if folds {
            Button {
                MekaHaptics.tick()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
            } label: {
                HStack {
                    Text(r.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(palette.accent)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(open ? "Fold the reflection" : "Reflect now")
        } else {
            Text(r.title).font(MekaType.itemTitle)
                .foregroundStyle(r.asking ? palette.accent : palette.textPrimary)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: r.asking)
        }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            prompt(r.wentWellPrompt, $wentWell, r.wentWellHints) { h in
                wentWell = ReflectionRules.shared.withHint(field: wentWell, hint: h.text)
            }
            prompt(r.dropPrompt, $drop, r.dropHints) { h in
                drop = ReflectionRules.shared.withHint(field: drop, hint: h.text)
            }
            prompt(r.mattersPrompt, $matters, r.mattersHints) { h in
                matters = ReflectionRules.shared.withHint(field: matters, hint: h.text)
                goalId = h.goalId
            }
            Toggle("Also keep what went well in Wins", isOn: $keepWins)
                .toggleStyle(.checkbox)
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.m) {
                Button("Save") { save() }
                    .buttonStyle(.borderedProminent)
                    .tint(palette.accent)
                    .keyboardShortcut(.return, modifiers: .command)
                if !r.saved {
                    Button("Skip this week") { model.skipReflection(true) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
        }
        .onAppear {
            if r.saved && wentWell.isEmpty && drop.isEmpty && matters.isEmpty {
                wentWell = r.wentWell; drop = r.drop; matters = r.matters
            }
        }
    }

    private func save() {
        let w = wentWell, d = drop, m = matters, g = goalId, k = keepWins
        let week = r.weekStart
        Task {
            guard let s = await model.saveReflection(wentWell: w, drop: d, matters: m, goalId: g, keepWins: k) else { return }
            said = (week, s)
            if !s.refused { withAnimation(MekaMotion.expand(reduced: reduceMotion)) { editing = false; open = false } }
        }
    }

    private func prompt(_ label: String, _ text: Binding<String>, _ hints: [ReflectionHint], add: @escaping (ReflectionHint) -> Void) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(label).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            TextField("A line or two…", text: text, axis: .vertical)
                .lineLimit(2...6)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(label)
            if !hints.isEmpty {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(hints, id: \.text) { h in
                        let added = text.wrappedValue.split(separator: "\n").contains { WinRules.shared.key(text: String($0)) == WinRules.shared.key(text: h.text) }
                        Button {
                            MekaHaptics.tick()
                            add(h)
                        } label: {
                            Text((added ? "✓ " : "+ ") + h.text)
                                .font(MekaType.caption).lineLimit(1)
                                .foregroundStyle(added ? palette.onAccent : palette.textPrimary)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(added ? palette.accent : palette.surfaceRaised, in: Capsule())
                        }
                        .buttonStyle(MekaPressStyle())
                        .disabled(added)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: added)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func answer(_ label: String, _ text: String) -> some View {
        if !text.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text(text).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
            }
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private var journal: some View {
        Button {
            MekaHaptics.tick()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { journalOpen.toggle() }
        } label: {
            HStack {
                Text(r.journalTitle).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(palette.accent)
                    .rotationEffect(.degrees(journalOpen ? 90 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(journalOpen ? "Hide your reflections" : "Show your reflections")
        if let gone = removed {
            HStack {
                Text("Removed \(gone.label)").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Button("Undo") {
                    model.removeReflection(gone.week, removed: false)
                    removed = nil
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
            }
        }
        if journalOpen {
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                ForEach(Array(r.journal.enumerated()), id: \.element.weekStart) { i, e in
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        HStack {
                            Text(e.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            Spacer()
                            Button("Remove") {
                                removed = (e.weekStart, e.label)
                                model.removeReflection(e.weekStart, removed: true)
                            }
                            .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .accessibilityLabel("Remove \(e.label)'s reflection")
                        }
                        VStack(alignment: .leading, spacing: MekaSpace.xs) {
                            answer(ReflectionRules.shared.WENT_WELL, e.wentWell)
                            answer(ReflectionRules.shared.DROP_PROMPT, e.drop)
                            answer("The one thing", e.matters)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(e.spoken)
                    }
                    .staggeredAppear(i)
                }
            }
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
    }
}

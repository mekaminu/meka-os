@preconcurrency import MekaCore
import SwiftUI

/// TODAY on macOS: Today | detail split, resizable, keyboard-first (brief §45).
struct TodayView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        @Bindable var model = model
        HSplitView {
            todayColumn
                .frame(minWidth: 380, idealWidth: 520)
            DetailView(task: model.selected, palette: palette)
                .frame(minWidth: 280, idealWidth: 360)
        }
        .background(palette.background)
    }

    private var todayColumn: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text(greeting)
                        .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                        .foregroundStyle(palette.textPrimary)
                    if let line = model.syncLine {
                        Text(line).font(MekaType.caption).foregroundStyle(palette.offline)
                            .transition(.opacity)
                    }
                    Spacer().frame(height: MekaSpace.l)

                    if let today = model.today {
                        if today.isClear {
                            Text("You're clear.")
                                .font(MekaType.upNextTitle).foregroundStyle(palette.textSecondary)
                        }
                        if !today.needsYou.isEmpty {
                            SectionLabel("Needs you", palette)
                            ForEach(today.needsYou, id: \.task.id) { item in
                                TaskRow(task: item.task, reason: item.reason, palette: palette)
                            }
                            Spacer().frame(height: MekaSpace.l)
                        }
                        if let next = today.upNext {
                            SectionLabel("Up next", palette)
                            UpNextCard(task: next, palette: palette)
                            Spacer().frame(height: MekaSpace.l)
                        }
                        if !today.yourDay.isEmpty {
                            SectionLabel("Your day", palette)
                            ForEach(today.yourDay, id: \.id) { t in TaskRow(task: t, reason: nil, palette: palette) }
                        }
                        if !today.doneToday.isEmpty {
                            Text("\(today.doneToday.count) done today")
                                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .padding(.top, MekaSpace.l)
                        }
                    }
                }
                .padding(.horizontal, MekaSpace.gutter)
                .padding(.vertical, MekaSpace.xl)
                // Replan / complete motion: rows glide to new positions instead of redrawing.
                .animation(MekaMotion.replan(reduced: reduceMotion), value: model.allTasks.map(\.id))
            }
            CaptureField(palette: palette)
                .padding(MekaSpace.m)
        }
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<12: "Good morning, Meka"
        case 12..<18: "Good afternoon, Meka"
        default: "Good evening, Meka"
        }
    }
}

private struct SectionLabel: View {
    let text: String
    let palette: MekaPalette
    init(_ text: String, _ palette: MekaPalette) { self.text = text; self.palette = palette }
    var body: some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.bottom, MekaSpace.xxs)
    }
}

private struct TaskRow: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let reason: NeedsYouReason?
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            CompleteButton(task: task, palette: palette)
            VStack(alignment: .leading, spacing: 2) {
                Text(task.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                if let line = subtitle {
                    Text(line).font(MekaType.itemMeta).foregroundStyle(isAlert ? palette.critical : palette.textSecondary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.s)
        .padding(.horizontal, MekaSpace.xs)
        .background(
            RoundedRectangle(cornerRadius: MekaRadius.m)
                .fill(model.selectedID == task.id ? palette.surfaceRaised : .clear)
        )
        .contentShape(Rectangle())
        .onTapGesture { model.selectedID = task.id }
        .transition(.asymmetric(insertion: .opacity, removal: .opacity.combined(with: .scale(scale: 0.96))))
    }

    private var isAlert: Bool { reason == .conflict || reason == .overdue }

    private var subtitle: String? {
        switch reason {
        case .conflict: "Edited on two devices — choose a version"
        case .overdue: "Overdue"
        case .dueTodayUnscheduled: "Due today · not scheduled"
        default: nil
        }
    }
}

private struct UpNextCard: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask
    let palette: MekaPalette

    var body: some View {
        HStack {
            Text(task.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
            Spacer()
            CompleteButton(task: task, palette: palette)
        }
        .padding(MekaSpace.l)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .onTapGesture { model.selectedID = task.id }
    }
}

/// Completion motion: ring fills → check → row leaves (brief §3).
private struct CompleteButton: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var pressed = false

    var body: some View {
        Button {
            guard !pressed else { return }
            withAnimation(MekaMotion.complete(reduced: reduceMotion)) { pressed = true }
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
            let id = task.id
            let delay: Duration = reduceMotion ? .milliseconds(50) : .milliseconds(280)
            Task { @MainActor in
                try? await Task.sleep(for: delay)
                model.complete(id)
            }
        } label: {
            ZStack {
                Circle().strokeBorder(pressed ? palette.accent : palette.textTertiary, lineWidth: 1.5)
                Circle().fill(pressed ? palette.accent : .clear).padding(pressed ? 0 : 8)
                if pressed { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(palette.onAccent) }
            }
            .frame(width: 22, height: 22)
            .scaleEffect(pressed && !reduceMotion ? 0.9 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Complete \(task.title)")
    }
}

private struct CaptureField: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("Capture anything…", text: $text)
            .textFieldStyle(.plain)
            .font(MekaType.body)
            .focused($focused)
            .padding(.horizontal, MekaSpace.l)
            .padding(.vertical, MekaSpace.m)
            .background(Capsule().fill(palette.surfaceRaised))
            .onSubmit { model.add(text); text = "" }
            .onChange(of: model.focusCapture) { focused = true }
    }
}

private struct DetailView: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask?
    let palette: MekaPalette
    @State private var title = ""

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let task {
                TextField("Title", text: $title)
                    .textFieldStyle(.plain)
                    .font(MekaType.upNextTitle)
                    .onSubmit { model.rename(task.id, to: title) }
                    .onAppear { title = task.title }
                    .onChange(of: task.id) { title = task.title }

                ForEach(model.conflicts.filter { $0.taskId == task.id }, id: \.field) { c in
                    Text("EDITED ON TWO DEVICES").font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    ForEach(c.options, id: \.self) { option in
                        Button(option) { model.resolve(c, with: option) }
                            .buttonStyle(.plain)
                            .font(MekaType.itemTitle)
                            .padding(MekaSpace.m)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    }
                }
                Spacer()
                HStack(spacing: MekaSpace.l) {
                    Button("Done") { model.complete(task.id) } // ⌘↩ lives in the Today menu
                    Button("Delete", role: .destructive) { model.delete(task.id) }
                }
                .buttonStyle(.plain)
                .font(MekaType.itemTitle)
                .foregroundStyle(palette.accent)
            } else {
                Spacer()
                Text("Select something to see it here.")
                    .font(MekaType.body).foregroundStyle(palette.textTertiary)
                    .frame(maxWidth: .infinity)
                Spacer()
            }
        }
        .padding(MekaSpace.gutter)
        .background(palette.surface)
    }
}

struct QuickCaptureMenu: View {
    @Environment(CoreModel.self) private var model
    @State private var text = ""

    var body: some View {
        TextField("Capture anything…", text: $text)
            .textFieldStyle(.roundedBorder)
            .onSubmit { model.add(text); text = "" }
            .padding(MekaSpace.m)
            .frame(width: 320)
    }
}

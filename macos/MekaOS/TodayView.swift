@preconcurrency import MekaKit
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
        .sheet(isPresented: $model.showConnect) { ConnectSheet(palette: palette) }
        .sheet(isPresented: $model.showCalendars) { CalendarsSheet(palette: palette) }
    }

    private var todayColumn: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text(greeting)
                        .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                        .foregroundStyle(palette.textPrimary)
                    if !model.isConnected || model.signedOut {
                        Button(model.signedOut ? "Reconnect this Mac" : "This Mac isn't syncing yet · Connect") { model.showConnect = true }
                            .buttonStyle(.plain)
                            .font(MekaType.caption)
                            .foregroundStyle(palette.accent)
                    }
                    if model.isConnected && !model.signedOut {
                        Button("Calendars") { model.showCalendars = true }
                            .buttonStyle(.plain)
                            .font(MekaType.caption)
                            .foregroundStyle(palette.accent)
                    }
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
                        if !today.events.isEmpty {
                            SectionLabel("Calendar", palette)
                            ForEach(today.events, id: \.id) { e in EventRow(event: e, palette: palette) }
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

/// One calendar event: time on the left, title and source on the right. Finished events step back.
private struct EventRow: View {
    let event: CalendarEvent
    let palette: MekaPalette

    var body: some View {
        let past = !event.allDay && Double(event.endAtMs) / 1000 < Date.now.timeIntervalSince1970
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
            Text(time).font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(past ? palette.textTertiary : palette.textSecondary)
                .frame(width: 96, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(event.title).font(MekaType.itemTitle).foregroundStyle(past ? palette.textTertiary : palette.textPrimary)
                Text([event.location, CoreModel.providerName(event.provider)].compactMap { $0 }.joined(separator: " · "))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }

    private var time: String {
        if event.allDay { return "All day" }
        let f = Date.FormatStyle.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)
        let start = Date(timeIntervalSince1970: Double(event.startAtMs) / 1000)
        let end = Date(timeIntervalSince1970: Double(event.endAtMs) / 1000)
        return "\(start.formatted(f))–\(end.formatted(f))"
    }
}

/// Connected calendars. Connecting opens the provider's own sign-in page in the browser; MEKA OS never sees the
/// password. The list refreshes whenever the app becomes active again (i.e. when the owner returns from the browser).
private struct CalendarsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Calendars").font(MekaType.upNextTitle)
            Text("Read-only. Events appear in Today on all your devices.")
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            if let accounts = model.accounts {
                if accounts.isEmpty {
                    Text("No calendars connected yet.").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                }
                ForEach(accounts, id: \.self) { a in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(a.email).font(MekaType.itemTitle)
                            Text(status(a)).font(MekaType.caption)
                                .foregroundStyle(a.needsReconnect ? palette.critical : palette.textTertiary)
                        }
                        Spacer()
                        if a.needsReconnect {
                            Button("Reconnect") { Task { await model.connectCalendar(a.provider) } }
                        }
                    }
                    .padding(MekaSpace.m)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                }
            } else {
                ProgressView().controlSize(.small)
            }
            HStack {
                Button("Connect Google Calendar") { Task { await model.connectCalendar("google") } }
                Button("Connect Outlook Calendar") { Task { await model.connectCalendar("microsoft") } }
            }
            if let message = model.calendarsMessage {
                Text(message).font(MekaType.caption).foregroundStyle(palette.critical)
            }
            HStack {
                Button("Refresh") { Task { await model.loadAccounts() } }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .task { await model.loadAccounts() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.loadAccounts() }
        }
    }

    private func status(_ a: ConnectedAccount) -> String {
        if a.needsReconnect { return "Access expired · Reconnect" }
        if a.status == "error" { return "Couldn't sync last time · retrying" }
        guard let ms = a.lastSyncAtMs?.int64Value else { return "\(CoreModel.providerName(a.provider)) · first sync in progress" }
        let d = Date(timeIntervalSince1970: Double(ms) / 1000)
        return "\(CoreModel.providerName(a.provider)) · synced \(d.formatted(date: .omitted, time: .shortened))"
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

/// One-time enrolment: server address + enrolment code. Everything works offline before and after.
private struct ConnectSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette
    @State private var url = ""
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Connect this Mac").font(MekaType.upNextTitle)
            TextField("Server address (https://…)", text: $url).textFieldStyle(.roundedBorder)
            SecureField("Enrolment code", text: $code).textFieldStyle(.roundedBorder)
            if let error { Text(error).font(MekaType.caption).foregroundStyle(palette.critical) }
            HStack {
                Spacer()
                Button("Not now") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(busy ? "Connecting…" : "Connect") {
                    busy = true; error = nil
                    Task {
                        error = await model.connect(serverURL: url, code: code)
                        busy = false
                        if error == nil { dismiss() }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(busy || url.isEmpty || code.isEmpty)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
        .onAppear { url = model.defaultServerURL }
    }
}

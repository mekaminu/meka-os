@preconcurrency import MekaKit
import SwiftUI

/// Event detail on the Mac (calendar redesign, slice 3), matching android/.../calendar/EventDetailPane.kt: what, when
/// and how soon, which calendar, where (click to open Maps), a Join button for Meet/Teams/Zoom links, and the event's
/// notes. Opened by clicking an event in Today or the Calendar section. Shows only: events change in their calendar.
///
/// The notes come from whoever made the event, so they are untrusted (ADR-006): plain text only, nothing in them is
/// opened unless you click Join (an https link to a known call service, or the provider's own link).
///
/// Motion: the sheet scale-fades in (macOS); sections stagger in. Reduce Motion: cross-fades.
struct EventDetailSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let palette: MekaPalette

    var body: some View {
        // "In 25 min" moves on while the sheet is open.
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            VStack(alignment: .leading, spacing: MekaSpace.s) {
                // Reading the marks here re-renders the sheet when a prep task or hide lands (or syncs in).
                let _ = model.eventMarks
                if let e = model.openEvent, let d = model.eventDetail(e) {
                    content(d, event: e)
                } else {
                    Text("Event").font(MekaType.upNextTitle)
                }
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
            }
            .padding(MekaSpace.l)
            .frame(width: 440)
        }
    }

    @ViewBuilder
    private func content(_ d: EventDetailView, event: CalendarEvent) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(d.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Text([d.whenLine, d.duration].compactMap { $0 }.joined(separator: " · "))
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            if let status = d.status {
                Text(status).font(MekaType.itemMeta)
                    .foregroundStyle(d.statusLit ? palette.accent : palette.textTertiary)
                    .contentTransition(.opacity)
            }
        }
        .staggeredAppear(0)

        // Calendar actions: MEKA-only, the real event is untouched.
        HStack(spacing: MekaSpace.s) {
            if d.canPrep {
                Button("Prep task") { model.addPrepTask(event) }
            }
            Button(d.hidden ? "Show in my day" : "Hide from my day") {
                if d.hidden { model.showEvent(d.id) } else { model.hideEvent(d.id, offerUndo: false) }
            }
            let note = [d.prepLine, d.hidden ? "Hidden from your day" : nil].compactMap { $0 }.joined(separator: " · ")
            if !note.isEmpty {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
            }
        }
        .controlSize(.small)
        .staggeredAppear(1)

        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.l) {
                if let join = d.join, let url = URL(string: join.url), url.scheme == "https" {
                    Button {
                        MekaHaptics.tick()
                        openURL(url)
                    } label: {
                        Text(join.label).font(MekaType.itemTitle).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .staggeredAppear(1)
                }

                if let place = d.location {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        SectionLabel("Where", palette)
                        Text(place).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
                        if let query = d.mapsQuery, let url = Self.mapsURL(query) {
                            Button("Open in Maps") { openURL(url) }.buttonStyle(.link)
                        }
                    }
                    .staggeredAppear(2)
                }

                if let line = d.calendarLine {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        SectionLabel("Calendar", palette)
                        Text(line).font(MekaType.body).foregroundStyle(d.isFixture ? palette.accent : palette.textPrimary)
                    }
                    .staggeredAppear(3)
                }

                if let notes = d.notes {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        SectionLabel("Notes", palette)
                        // Verbatim: plain text, never Markdown, so nothing in the notes becomes a link.
                        Text(verbatim: notes).font(MekaType.body).foregroundStyle(palette.textSecondary)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .staggeredAppear(4)
                }

                Text("Change the event itself in your calendar. Prep tasks and hiding stay in MEKA.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .staggeredAppear(5)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, MekaSpace.s)
        }
        .frame(maxHeight: 420)
    }

    /// Apple Maps search for the place.
    static func mapsURL(_ query: String) -> URL? {
        var c = URLComponents(string: "https://maps.apple.com/")
        c?.queryItems = [URLQueryItem(name: "q", value: query)]
        return c?.url
    }
}

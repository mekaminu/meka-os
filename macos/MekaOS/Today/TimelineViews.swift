@preconcurrency import MekaKit
import SwiftUI

/// Today's timeline (calendar redesign, slice 1), matching android/.../today/TimelineViews.kt: events and planned tasks
/// in one list with a now line and free gaps. Events are context, so their titles use the regular body weight.
/// Motion: rows glide as the day moves on; the now line's dot breathes; "3 earlier" unfolds in place.
/// Reduce Motion: cross-fades only and a steady dot.
enum TimelineMetrics {
    static let timeColumn: CGFloat = 96
}

/// An event: time on the left, title and where/which calendar under it. A running one is marked "Now".
struct TimelineEventRow: View {
    let row: TimelineRow
    let past: Bool
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(past ? palette.textTertiary : palette.textSecondary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.body).foregroundStyle(past ? palette.textTertiary : palette.textPrimary)
                let line = [row.running ? "Now" : nil, row.detail].compactMap { $0 }.joined(separator: " · ")
                if !line.isEmpty {
                    Text(line).font(MekaType.caption).foregroundStyle(row.running ? palette.accent : palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// A free stretch: "1 h 30 free", quiet.
struct GapRow: View {
    let row: TimelineRow
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textTertiary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            Text(row.title).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Spacer()
        }
        .padding(.vertical, MekaSpace.xxs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// The now line: a breathing accent dot, the time, and a hairline across.
struct NowLine: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let row: TimelineRow
    let palette: MekaPalette
    @State private var dim = false

    var body: some View {
        HStack(spacing: 0) {
            Text(row.time).font(MekaType.caption).monospacedDigit().foregroundStyle(palette.accent)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            Circle().fill(palette.accent).frame(width: 8, height: 8)
                .opacity(reduceMotion ? 1 : (dim ? 0.45 : 1))
            Rectangle().fill(palette.accent.opacity(0.5)).frame(height: 1)
        }
        .padding(.vertical, MekaSpace.xxs)
        .padding(.horizontal, MekaSpace.xs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Now, \(row.time)")
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) { dim = true }
        }
    }
}

/// All-day events as chips above the timeline.
struct AllDayChips: View {
    let events: [CalendarEvent]
    let palette: MekaPalette

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MekaSpace.xs) {
                ForEach(events, id: \.id) { e in
                    Text(e.title).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .padding(.horizontal, MekaSpace.s).padding(.vertical, MekaSpace.xxs)
                        .background(Capsule().fill(palette.surfaceRaised))
                }
            }
        }
        .padding(.bottom, MekaSpace.xs)
    }
}

/// "3 earlier ›": unfolds the events that have finished.
struct EarlierToggle: View {
    let label: String
    @Binding var open: Bool
    let palette: MekaPalette

    var body: some View {
        Button { open.toggle() } label: {
            HStack(spacing: MekaSpace.xxs) {
                Text(label)
                Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold))
                    .rotationEffect(.degrees(open ? 90 : 0))
            }
            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
            .padding(.vertical, MekaSpace.xxs)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Up next's event line: "Call with Tunde in 25 min" with its time and place.
struct NextEventCard: View {
    let next: UpNextEvent
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            Circle().fill(palette.accent).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(next.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(next.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer()
        }
        .padding(MekaSpace.l)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
    }
}

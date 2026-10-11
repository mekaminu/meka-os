@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Week ahead on the Mac (Sunday week ahead, slice 1), like the Fold's WeekAheadPane: the coming week day
/// by day — what kind of day (office or from home with the hours, annual leave, a bank holiday), the weather while the
/// forecast reaches it, then events, fixtures, school, date night, appointments and renewals in time order — and
/// FAMILY SYNC, what needs both Meka and Jeanette. Read-only; Today's "Your week ahead · …" line opens it on Sunday
/// evening. Nothing crosses to the core from here.
///
/// Motion: the sheet scale-fades (system); the summary cross-fades as it changes; the days and the family sync stagger
/// in 40 ms apart. Reduce Motion: cross-fades.
struct WeekAheadSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let v = model.weekAhead ?? WeekAheadView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Week ahead").font(MekaType.upNextTitle).staggeredAppear(0)
            Text("\(v.title) · \(v.rangeLabel)").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.m) {
                    ForEach(Array(v.days.enumerated()), id: \.element.epochDay) { i, d in
                        WeekAheadDayView(day: d, palette: palette).staggeredAppear(2 + i)
                    }
                    if !v.sync.isEmpty {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            label("Family sync")
                            Text("To sort out with Jeanette").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            ForEach(v.sync, id: \.self) { line in
                                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                                    Circle().fill(palette.accent).frame(width: 6, height: 6)
                                    Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                        .staggeredAppear(2 + v.days.count)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 480)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct WeekAheadDayView: View {
    let day: WeekAheadDay
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(day.label.uppercased())
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .accessibilityAddTraits(.isHeader)
            let meta = [day.dayLine, day.weather].compactMap { $0 }.joined(separator: " · ")
            if !meta.isEmpty {
                Text(meta).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if day.lines.isEmpty && day.dayLine == nil {
                Text("Nothing in the diary").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            ForEach(day.lines, id: \.id) { l in
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(l.time ?? WeekAheadDayView.mark(l.kind)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .frame(width: 52, alignment: .leading)
                    Text(l.text).font(MekaType.body).foregroundStyle(l.lit ? palette.accent : palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(l.spoken)
            }
            if day.more > 0 {
                Text("and \(day.more) more").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
    }

    /// The lead mark for a line with no time: school, money due, the rest a dot.
    static func mark(_ kind: WeekAheadKind) -> String {
        if kind == .school { return "School" }
        if kind == .due { return "Due" }
        return "·"
    }
}

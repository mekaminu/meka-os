@preconcurrency import MekaKit
import SwiftUI

/// Study plan, slice 2b rest, like the Fold's: Sunday 17:00 to Monday 12:00, Today's study week card ("Your study week",
/// the week's dates), then per course what was ticked, on track or behind, and next week's sessions; Obi's one line;
/// Looks good (put away on both devices) and Change plan (opens the course). A click on a course opens it too. Never
/// spoken. Looks good folds it away at once. Only Strings cross to the core.
struct StudyCheckinCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let checkin: StudyCheckin?
    let palette: MekaPalette
    /// Put away here, shown at once until the view says the same.
    @State private var gone: Set<String> = []
    @State private var openId: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let c = checkin, !gone.contains(c.key) {
                card(c).transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: checkin?.key)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: gone)
        .sheet(isPresented: Binding(get: { openId != nil }, set: { if !$0 { openId = nil } })) {
            if let id = openId {
                StudySheet(courseId: id, palette: palette, onClose: { openId = nil }, onRemoved: { _ in openId = nil })
            }
        }
    }

    private func card(_ c: StudyCheckin) -> some View {
        let key = c.key
        let rows = c.rows
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(alignment: .firstTextBaseline) {
                Text(c.headline).font(MekaType.captionStrong).foregroundStyle(palette.accent).lineLimit(1)
                Spacer(minLength: MekaSpace.xs)
                Text(c.sub).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(c.spoken)
            ForEach(rows, id: \.courseId) { r in
                let id = r.courseId
                Button { openId = id } label: {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                        Text(r.week).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                        if let status = r.status {
                            Text(status).font(MekaType.caption).foregroundStyle(r.behind ? palette.accent : palette.success).lineLimit(1)
                        }
                        Text(r.next).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .padding(.top, MekaSpace.xxs)
                .accessibilityLabel(r.spoken)
            }
            Text(c.obi).font(MekaType.caption).foregroundStyle(palette.textSecondary).padding(.top, MekaSpace.xxs)
            HStack(spacing: MekaSpace.m) {
                Button {
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { _ = gone.insert(key) }
                    Task { if !(await model.seeStudyCheckin(key)) { gone.remove(key) } }
                } label: {
                    Text(StudyCheckinRules.shared.LOOKS_GOOD)
                        .font(MekaType.captionStrong)
                        .foregroundStyle(palette.accent)
                        .padding(.horizontal, MekaSpace.m)
                        .padding(.vertical, MekaSpace.xxs)
                        .background(palette.surfaceRaised, in: Capsule())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Looks good, put the study week away")
                Button(StudyCheckinRules.shared.CHANGE) { openId = rows.first?.courseId }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textSecondary)
            }
            .padding(.top, MekaSpace.xxs)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.l))
        .accessibilityElement(children: .contain)
    }
}

@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Errands on the Mac (errands by location, slice 1), like the Fold's ErrandsPane: things that wait for a
/// place, not a time ("Tesco: milk, batteries"), grouped by place, oldest first. Done ticks one off (it moves to
/// "Done lately", with Back), Remove for good. The reminder near a place is the Fold's (slice 2). Only Strings cross.
///
/// Motion: the sheet scale-fades (system); the summary and the field's line cross-fade; rows stagger in and move
/// between places and Done lately on the expand spring; an add or Done gives a light haptic, a refusal, Back or Remove
/// a tick. Reduce Motion: cross-fades.
struct ErrandsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var said: CoreModel.ErrandSaid?

    var body: some View {
        let v = model.errands ?? ErrandView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Errands").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            TextField(ErrandRules.shared.HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task {
                        guard let r = await model.addErrand(typed) else { return }
                        said = r
                        if r.refused { text = typed } // keep the words to fix
                    }
                }
                .staggeredAppear(2)
            if let said {
                Text(said.line).font(MekaType.caption)
                    .foregroundStyle(said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(v.places, id: \.key) { place in
                        label(place.place)
                        ForEach(place.rows, id: \.id) { row in
                            ErrandRowView(row: row, palette: palette, primary: "Done",
                                          onPrimary: { model.errandDone(row.id, done: true) },
                                          onRemove: { model.removeErrand(row.id) })
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    if !v.done.isEmpty { label("Done lately") }
                    ForEach(v.done, id: \.id) { row in
                        ErrandRowView(row: row, palette: palette, primary: "Back",
                                      onPrimary: { model.errandDone(row.id, done: false) },
                                      onRemove: { model.removeErrand(row.id) })
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion),
                           value: v.places.flatMap { $0.rows.map(\.id) } + v.done.map(\.id))
            }
            .frame(maxHeight: 420)
            .staggeredAppear(3)
            Text(ErrandRules.shared.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
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
            .padding(.top, MekaSpace.xs)
    }
}

private struct ErrandRowView: View {
    let row: ErrandRow
    let palette: MekaPalette
    let primary: String
    let onPrimary: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.itemTitle)
                    .foregroundStyle(row.done ? palette.textSecondary : palette.textPrimary)
                    .lineLimit(2)
                if let meta = row.meta {
                    Text(meta).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(primary, action: onPrimary)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("\(primary): \(row.title)")
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(row.title)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}

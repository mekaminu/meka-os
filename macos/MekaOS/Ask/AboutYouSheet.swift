@preconcurrency import MekaKit
import SwiftUI

/// Ask → Settings → About you on the Mac (rule 13, slice 1), like the Fold's AboutYouPane: who's who, home and work,
/// typed once and kept in Meka's own synced data rather than MEKA's code. Each line is a field holding what's there;
/// Return saves it (blank clears it, with Undo). The partner's Watch for requests switch, then the children (Remove on
/// each, a field to add one: "Alex, U7, Oakfield School"). Only Strings, an Int and a Bool cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and each line under a field cross-fade (a refusal in the accent
/// with a tick haptic, a save a light one); rows stagger in; children arrive and leave on the expand spring; the
/// switch blends its colour. Reduce Motion: cross-fades.
struct AboutYouSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Per field key: what its field holds (nil = what's stored) and what the last save said.
    @State private var texts: [String: String] = [:]
    @State private var said: [String: CoreModel.AboutSaid] = [:]
    @State private var childText = ""

    var body: some View {
        let v = model.aboutYou ?? AboutYouView.companion.EMPTY
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(AboutYouRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(Array(v.rows.enumerated()), id: \.element.field.key) { i, row in
                        line(row, watch: row.field.key == AboutYouFields.shared.PARTNER_EMAIL ? v.watchLine : nil, watching: v.partnerWatch)
                            .staggeredAppear(2 + i)
                    }
                    children(v).staggeredAppear(2 + v.rows.count)
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: v.children.map(\.name))
            }
            .frame(maxHeight: 520)
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

    @ViewBuilder
    private func line(_ row: AboutRow, watch: String?, watching: Bool) -> some View {
        let key = row.field.key
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(row.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .padding(.top, MekaSpace.xs)
            TextField(row.hint, text: Binding(get: { texts[key] ?? row.value ?? "" }, set: { texts[key] = $0 }))
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(row.spokenHint)
                .onSubmit {
                    let typed = texts[key] ?? row.value ?? ""
                    Task {
                        guard let r = await model.setAboutYou(key: key, text: typed) else { return }
                        said[key] = r
                        if !r.refused { texts[key] = nil } // show what's stored again
                    }
                }
            saidLine(said[key], fallback: row.line) { back in
                Task { if let r = await model.setAboutYou(key: key, text: back) { said[key] = r; texts[key] = nil } }
            }
            if let watch {
                HStack {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text("Watch for requests").font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(watch).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: watch)
                    }
                    Spacer()
                    Toggle("Watch for requests", isOn: Binding(get: { watching }, set: { model.setAboutYouPartnerWatch($0) }))
                        .labelsHidden().toggleStyle(.switch).tint(palette.accent)
                }
                .padding(.top, MekaSpace.xs)
            }
        }
    }

    @ViewBuilder
    private func children(_ v: AboutYouView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("CHILDREN")
                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.m)
            if let empty = v.childrenEmpty {
                Text(empty).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(v.children, id: \.index) { c in
                HStack(spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(c.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(c.spoken)
                    Spacer()
                    Button("Remove") {
                        let index = c.index
                        Task { if let r = await model.removeAboutYouChild(index) { said["children"] = r } }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Remove \(c.name)")
                }
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.xs)
                .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            TextField(v.childHint, text: $childText)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(v.childDescription)
                .onSubmit {
                    let typed = childText
                    childText = ""
                    Task {
                        guard let r = await model.addAboutYouChild(typed) else { return }
                        said["children"] = r
                        if r.refused { childText = typed } // keep the words to fix
                    }
                }
            saidLine(said["children"], fallback: nil) { back in
                Task { if let r = await model.addAboutYouChild(back) { said["children"] = r } }
            }
        }
    }

    /// What the last save said (the accent when refused) with Undo after a clear or a removal; else the row's line.
    @ViewBuilder
    private func saidLine(_ s: CoreModel.AboutSaid?, fallback: String?, undo: @escaping (String) -> Void) -> some View {
        if let s {
            HStack(spacing: MekaSpace.m) {
                Text(s.line).font(MekaType.caption)
                    .foregroundStyle(s.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let back = s.undo {
                    Button("Undo") { undo(back) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                }
            }
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: s)
        } else if let fallback {
            Text(fallback).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
    }
}

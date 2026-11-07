@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Your team on the Mac (slice 1), like the Fold's TeamPane: WORKING · NEED YOU · DONE TODAY tiles, a
/// card per helper (its symbol in its colour, name and role, a status with a dot that breathes while it is working or
/// watching, TODAY and WAITS ON YOU side by side, the wait box lit when something waits), the foot line; a click on a
/// card shows its page in the sheet (how much it may do by itself, TODAY's log, what waits with Review, slice 2's
/// Pause for today / Resume on Obi's, Inbox's and Money's, ‹ Your team).
/// The words are the core's `TeamRules.view`. Only Strings cross to the core; nothing here acts.
/// Slice 3: **Ask the team…** at the foot closes the sheet into Ask's field (the mic: Talk, listening); for a few
/// minutes what's asked carries the team in full (`TeamAskRules`).
///
/// Motion: the sheet scale-fades (system); tiles and cards stagger in; a card's click gives a tick haptic and the page
/// cross-fades in on the expand spring; the live dot breathes. Reduce Motion: cross-fades and a still dot.
struct TeamSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var view: TeamView?
    /// The helper whose page shows (its code), nil for the list.
    @State private var open: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let v = view, let code = open, let c = v.card(code: code) {
                page(c)
                    .transition(.opacity)
            } else {
                Text(TeamRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
                if let v = view {
                    list(v).transition(.opacity)
                }
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .padding(MekaSpace.l)
        .frame(width: 520)
        .background(palette.surface)
        .task {
            // The day's log grows as the helpers work and sync brings the Fold's in: read again while open.
            while !Task.isCancelled {
                if let next = await model.teamView() { view = next }
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }

    @ViewBuilder
    private func list(_ v: TeamView) -> some View {
        Text(LoneWordGlue.shared.kept(line: v.subtitle)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .staggeredAppear(0)
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(Array(v.tiles.enumerated()), id: \.offset) { _, t in tile(t) }
                }
                .staggeredAppear(1)
                ForEach(Array(v.cards.enumerated()), id: \.element.agent.code) { i, c in
                    card(c).staggeredAppear(i + 2)
                }
                Text(LoneWordGlue.shared.kept(line: TeamRules.shared.FOOT)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.m)
                    .staggeredAppear(v.cards.count + 2)
                // Slice 3: Ask the team… talks to Obi: Ask's field (or Talk, listening), the team in what he sees.
                MoneyAskBarView(palette: palette, onMic: { model.askTeam(listen: true, reduced: reduceMotion) },
                                text: TeamAskRules.shared.BAR, spoken: TeamAskRules.shared.BAR_LABEL,
                                micSpoken: TeamAskRules.shared.MIC_LABEL) {
                    model.askTeam(listen: false, reduced: reduceMotion)
                }
                .padding(.top, MekaSpace.m)
                .staggeredAppear(v.cards.count + 3)
            }
        }
        .scrollIndicators(.never)
        .frame(maxHeight: 560)
    }

    private func tile(_ t: TeamTile) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(t.label).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(t.lit ? palette.accent : palette.textTertiary).lineLimit(1)
            Text(t.value).font(MekaType.upNextTitle).foregroundStyle(t.lit ? palette.accent : palette.textPrimary)
                .contentTransition(.numericText())
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(t.spoken)
    }

    private func tint(_ code: String) -> Color {
        switch code {
        case "inbox": palette.approval
        case "calls": palette.success
        case "money": palette.money2
        case "kestrel": palette.calendar3
        default: palette.accent
        }
    }

    private func symbol(_ code: String) -> String {
        switch code {
        case "inbox": "tray"
        case "calls": "phone"
        case "money": "banknote"
        case "kestrel": "chart.line.uptrend.xyaxis"
        default: "globe"
        }
    }

    private func icon(_ code: String) -> some View {
        Image(systemName: symbol(code)).font(MekaType.body)
            .foregroundStyle(tint(code))
            .frame(width: 44, height: 44)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .accessibilityHidden(true)
    }

    private func card(_ c: TeamCard) -> some View {
        let code = c.agent.code
        return Button {
            MekaHaptics.tick()
            open = code
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(spacing: MekaSpace.m) {
                    icon(code)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(c.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                        Text(LoneWordGlue.shared.kept(line: c.role)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    Spacer(minLength: 0)
                    HStack(spacing: MekaSpace.xxs) {
                        TeamStatusDot(live: c.status.live, palette: palette)
                        Text(c.status.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                }
                HStack(alignment: .top, spacing: MekaSpace.xs) {
                    infoBox(TeamRules.shared.TODAY, c.today, lit: false)
                    infoBox(TeamRules.shared.WAITS, c.wait, lit: c.waits)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(c.spoken)
    }

    private func infoBox(_ label: String, _ text: String, lit: Bool) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(label.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(lit ? palette.accent : palette.textTertiary).lineLimit(1)
            Text(LoneWordGlue.shared.kept(line: text)).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(lit ? palette.accent.opacity(0.14) : palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
    }

    private func page(_ c: TeamCard) -> some View {
        let code = c.agent.code
        let target = c.review?.name
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button("‹ \(TeamRules.shared.TITLE)") {
                MekaHaptics.tick()
                open = nil
            }
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta).foregroundStyle(palette.accent)
            .accessibilityLabel("Back to \(TeamRules.shared.TITLE)")
            HStack(spacing: MekaSpace.m) {
                icon(code)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(c.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    Text(LoneWordGlue.shared.kept(line: c.level)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Text(TeamRules.shared.TODAY.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textTertiary)
                .padding(.top, MekaSpace.xs)
                .accessibilityAddTraits(.isHeader)
            if c.log.isEmpty {
                Text(LoneWordGlue.shared.kept(line: TeamRules.shared.LOG_EMPTY)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            ForEach(Array(c.log.enumerated()), id: \.offset) { _, l in
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
                    Text(l.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).monospacedDigit()
                    Text(LoneWordGlue.shared.kept(line: l.text)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let wait = c.waitLine, let target {
                HStack(spacing: MekaSpace.xs) {
                    Text(LoneWordGlue.shared.kept(line: wait)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button("Review") {
                        MekaHaptics.tick()
                        model.openFromTeam(target, reduced: reduceMotion)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("\(c.review?.label ?? "Review"): \(c.title)")
                }
                .padding(MekaSpace.xs)
                .background(palette.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .padding(.top, MekaSpace.xs)
            }
            if let pause = c.pauseAction {
                let paused = c.paused
                HStack(spacing: MekaSpace.xs) {
                    Text(LoneWordGlue.shared.kept(line: c.pauseLine ?? "")).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button(pause) {
                        MekaHaptics.tick()
                        Task {
                            if let next = await model.setTeamPause(code, on: !paused) { view = next }
                        }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta).foregroundStyle(paused ? palette.onAccent : palette.textPrimary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(paused ? palette.accent : palette.surface, in: Capsule())
                    .accessibilityLabel("\(pause): \(c.title)")
                }
                .padding(MekaSpace.xs)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .padding(.top, MekaSpace.xs)
            }
            if c.readOnly {
                Text(LoneWordGlue.shared.kept(line: TeamRules.shared.KESTREL_FOOT)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The status dot: breathes while live (still with Reduce Motion). Decorative.
private struct TeamStatusDot: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let live: Bool
    let palette: MekaPalette
    @State private var dim = false

    var body: some View {
        Circle()
            .fill(live ? palette.success : palette.textTertiary)
            .frame(width: 8, height: 8)
            .opacity(live && dim ? 0.35 : 1)
            .onAppear {
                guard live, !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { dim = true }
            }
            .accessibilityHidden(true)
    }
}

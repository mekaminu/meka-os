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
/// **Team, simpler** (approved 2026-11-11 21:21, `docs/design/routines/team-simpler.dc.html`): the three small number
/// tiles, one **Waiting on you** box (only what needs him, a row each, a click opens it; hidden when nothing waits) and
/// one **Helpers** box, a line per helper that unfolds in place to its role, its last three things and Open · Pause ·
/// Whole day › (Whole day shows the helper's page with the day's log). Rows unfold on the expand spring, the chevron
/// turning; Reduce Motion cross-fades.
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
    /// Team, simpler: the helper whose row is unfolded (one at a time), by its code.
    @State private var unfolded: String?

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
        .animation(MekaMotion.expand(reduced: reduceMotion), value: unfolded)
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
                // Team, simpler: one Waiting on you box (hidden when nothing waits), then one Helpers box.
                let waiting = v.waiting
                if !waiting.isEmpty {
                    waitingBox(waiting).staggeredAppear(2)
                }
                helpersBox(v.cards).staggeredAppear(3)
                // Slice 3: Ask the team… talks to Obi: Ask's field (or Talk, listening), the team in what he sees.
                MoneyAskBarView(palette: palette, onMic: { model.askTeam(listen: true, reduced: reduceMotion) },
                                text: TeamAskRules.shared.BAR, spoken: TeamAskRules.shared.BAR_LABEL,
                                micSpoken: TeamAskRules.shared.MIC_LABEL) {
                    model.askTeam(listen: false, reduced: reduceMotion)
                }
                .staggeredAppear(4)
                Text(LoneWordGlue.shared.kept(line: TeamRules.shared.FOOT)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(5)
            }
        }
        .scrollIndicators(.never)
        .frame(maxHeight: 560)
    }

    private func tile(_ t: TeamTile) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            // Team, simpler: the number over its small label.
            Text(t.value).font(MekaType.itemTitle).foregroundStyle(t.lit ? palette.accent : palette.textPrimary)
                .contentTransition(.numericText())
            Text(t.label).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
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
            .frame(width: 34, height: 34)
            .background(tint(code).opacity(0.12), in: RoundedRectangle(cornerRadius: MekaRadius.s))
            .accessibilityHidden(true)
    }

    /// A box on the team screen: the raised surface with its title row.
    private func box<Trailing: View, Content: View>(
        _ title: String, @ViewBuilder trailing: () -> Trailing, @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: MekaSpace.xs) {
                Text(title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).accessibilityAddTraits(.isHeader)
                trailing()
            }
            .padding(.bottom, MekaSpace.xxs)
            content()
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.top, MekaSpace.m)
        .padding(.bottom, MekaSpace.xxs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }

    private var hairline: some View { Rectangle().fill(palette.surface).frame(height: 1) }

    /// Waiting on you: only what needs him, one row each; a click opens it (a trade's goes to Needs you).
    private func waitingBox(_ rows: [TeamWaitRow]) -> some View {
        box(TeamSimplerRules.shared.WAITING) {
            Text("\(rows.count)").font(MekaType.caption).foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                .background(palette.accent.opacity(0.14), in: Capsule())
                .accessibilityHidden(true)
        } content: {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, w in
                hairline
                Button {
                    MekaHaptics.tick()
                    model.openFromTeam(w.target.name, reduced: reduceMotion)
                } label: {
                    HStack(spacing: MekaSpace.xs) {
                        icon(w.agent.code)
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(LoneWordGlue.shared.kept(line: w.what)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            Text(LoneWordGlue.shared.kept(line: w.who)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        }
                        Spacer(minLength: 0)
                        Text("\(w.cta) ›").font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(1)
                    }
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(w.spoken)
            }
        }
    }

    /// Helpers: one line per helper; a click unfolds its role, its last three things and Open · Pause · Whole day ›.
    private func helpersBox(_ cards: [TeamCard]) -> some View {
        box(TeamSimplerRules.shared.HELPERS) {
            Spacer(minLength: 0)
            Text(TeamSimplerRules.shared.HELPERS_HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
        } content: {
            ForEach(cards, id: \.agent.code) { c in
                hairline
                helperRow(c)
            }
        }
    }

    private func helperRow(_ c: TeamCard) -> some View {
        let code = c.agent.code
        let isOpen = unfolded == code
        let paused = c.paused
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Button {
                MekaHaptics.tick()
                unfolded = isOpen ? nil : code
            } label: {
                HStack(spacing: MekaSpace.xs) {
                    icon(code)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        HStack(spacing: MekaSpace.xs) {
                            Text(c.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                            TeamStatusDot(live: c.status.live, palette: palette)
                            Text(c.status.label).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                        }
                        Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1).truncationMode(.tail)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.down").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .rotationEffect(.degrees(isOpen ? 180 : 0))
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(c.rowSpoken)
            .accessibilityValue(isOpen ? "Unfolded" : "Folded")
            if isOpen {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(LoneWordGlue.shared.kept(line: c.roleLine)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    if c.recent.isEmpty {
                        Text(LoneWordGlue.shared.kept(line: TeamRules.shared.LOG_EMPTY)).font(MekaType.caption)
                            .foregroundStyle(palette.textTertiary)
                    }
                    ForEach(Array(c.recent.enumerated()), id: \.offset) { _, l in
                        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                            Text(l.time).font(MekaType.caption).foregroundStyle(palette.textTertiary).monospacedDigit()
                                .frame(minWidth: 40, alignment: .leading)
                            Text(LoneWordGlue.shared.kept(line: l.text)).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    HStack(spacing: MekaSpace.xxs) {
                        pill(c.openLabel, filled: true) {
                            let target = c.openTarget.name
                            if target == "ASK" { model.askTeam(listen: false, reduced: reduceMotion) }
                            else { model.openFromTeam(target, reduced: reduceMotion) }
                        }
                        .accessibilityLabel("\(c.openLabel): \(c.title)")
                        if let p = c.pauseShort {
                            pill(p, filled: true) {
                                Task {
                                    if let next = await model.setTeamPause(code, on: !paused) { view = next }
                                }
                            }
                            .accessibilityLabel("\(p): \(c.title)")
                        }
                        pill("\(TeamSimplerRules.shared.WHOLE_DAY) ›", filled: false) { open = code }
                            .accessibilityLabel("\(TeamSimplerRules.shared.WHOLE_DAY): \(c.title)")
                    }
                    .padding(.top, MekaSpace.xxs)
                }
                .padding(.leading, MekaSpace.xl + MekaSpace.xs)
                .padding(.bottom, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func pill(_ label: String, filled: Bool, action: @escaping () -> Void) -> some View {
        Button {
            MekaHaptics.tick()
            action()
        } label: {
            Text(label).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(filled ? palette.surface : Color.clear, in: Capsule())
        }
        .buttonStyle(MekaPressStyle())
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

@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// The "now" card in the menu-bar window (Fold modes, slice 3; the Mac's counterpart of the Fold's cover screen, which
/// it has no hinge for): the one thing that matters now with its one-tap actions, above the capture field. A booked
/// session (the Gym) on now or over offers Went and Didn't go.
/// Motion: when the thing changes, the content pushes in from the right; buttons press in (0.97) with a light haptic.
/// Reduce Motion: cross-fades, no press scale.
struct NowCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    @Environment(\.openWindow) private var openWindow
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        if let v = model.coverNow {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                card(v)
                    .id(Self.key(v))
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .trailing))
                if let then = v.thenLine {
                    Button(then) {
                        if let t = v.thenTask { openTask(t.id) } else if let e = v.thenEvent { openEvent(e) }
                    }
                    .buttonStyle(.plain)
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                    .disabled(v.thenTask == nil && v.thenEvent == nil)
                }
                if let needs = v.needsYouLine {
                    Button("\(needs) ›") { bringForward { model.go(to: .needsYou, reduced: reduceMotion) } }
                        .buttonStyle(.plain)
                        .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
            .animation(MekaMotion.replan(reduced: reduceMotion), value: Self.key(v))
            .padding([.horizontal, .top], MekaSpace.m)
            .frame(width: 320, alignment: .leading)
        }
    }

    private func card(_ v: NowView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(v.label.uppercased()).font(MekaType.sectionLabel)
                .foregroundStyle(v.lit ? palette.accent : palette.textTertiary)
            Text(v.title).font(MekaType.upNextTitle).lineLimit(2)
                .foregroundStyle(v.kind == NowKind.clear ? palette.textSecondary : palette.textPrimary)
            if let line = v.line {
                Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
            }
            if !v.actions.isEmpty {
                HStack(spacing: MekaSpace.s) {
                    ForEach(v.actions, id: \.name) { a in chip(a, v) }
                }
                .padding(.top, MekaSpace.s)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .contentShape(Rectangle())
        .onTapGesture {
            if let t = v.task { openTask(t.id) } else if let e = v.event { openEvent(e) }
        }
    }

    private func chip(_ a: NowAction, _ v: NowView) -> some View {
        let primary = a == NowAction.join || a == NowAction.done || a == NowAction.went || (a == NowAction.maps && v.join == nil)
        return Button {
            // Went and Didn't go give their own haptics (light / tick) in the model, as from Today's session card.
            if a != NowAction.went && a != NowAction.didntGo { MekaHaptics.light() }
            switch a {
            case NowAction.join: if let s = v.join?.url, let url = URL(string: s) { openURL(url) }
            case NowAction.maps: if let q = v.mapsQuery, let url = EventDetailSheet.mapsURL(q) { openURL(url) }
            case NowAction.openEvent: if let e = v.event { openEvent(e) }
            case NowAction.done: if let id = v.task?.id { model.complete(id) }
            case NowAction.tomorrow: if let id = v.task?.id { model.snooze(id) }
            case NowAction.openTask: if let id = v.task?.id { openTask(id) }
            case NowAction.went: if let id = v.session?.habitId { model.sessionWent(id) }
            case NowAction.didntGo: if let id = v.session?.habitId { model.sessionMissed(id) }
            default: break
            }
        } label: {
            Text(Self.label(a, v))
                .font(MekaType.caption)
                .foregroundStyle(primary ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(primary ? palette.accent : palette.background))
        }
        .buttonStyle(MekaPressStyle())
    }

    private static func label(_ a: NowAction, _ v: NowView) -> String {
        switch a {
        case NowAction.join: return v.join?.label ?? "Join"
        case NowAction.maps: return "Directions"
        case NowAction.done: return "Done"
        case NowAction.tomorrow: return "Tomorrow"
        case NowAction.went: return "Went"
        case NowAction.didntGo: return "Didn't go"
        default: return "Open"
        }
    }

    private static func key(_ v: NowView) -> String { "\(v.kind.name):\(v.task?.id ?? v.event?.id ?? v.session?.habitId ?? "")" }

    private func openTask(_ id: String) {
        bringForward {
            model.go(to: .today, reduced: reduceMotion)
            model.select(id, reduced: reduceMotion)
        }
    }

    private func openEvent(_ e: CalendarEvent) { bringForward { model.openEvent = e } }

    /// Brings MEKA's window forward (opening it if it was closed), then does what was asked there.
    private func bringForward(_ then: () -> Void) {
        NSApp.activate()
        if !NSApp.windows.contains(where: { $0.isVisible && $0.canBecomeMain }) { openWindow(id: "today") }
        then()
    }
}

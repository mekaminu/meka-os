import SwiftUI
@preconcurrency import MekaKit

/// The ten-minute inbox on the Mac (Inbox autopilot, slice 7), in Needs you above "From your mail": from the moment
/// (morning or evening, the Mail sheet) while emails need him — "TEN-MINUTE INBOX", "6 need you · 2 min each", the
/// streak — with Start and Not today (Undo on the bar). Start turns it into the session: one email at a time ("2 of 6 ·
/// 1 done · 5 to go") with the email's own answers (Reply, Done, Task, Waiting for, Snooze, each with Undo) and Skip
/// (or drag it aside: it follows the pointer); each next email pushes in from the trailing edge with a
/// fade on the expand spring. The end says "Inbox clear" with the streak, and Close. Reduced motion: cross-fades.
struct MailMomentView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var dragged: CGFloat = 0

    var body: some View {
        Group {
            if model.mailMoment != nil || model.mailMomentSession != nil {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(MailMomentRules.shared.TITLE.uppercased())
                        .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                    if let session = model.mailMomentSession {
                        sessionBody(session)
                    } else if let card = model.mailMoment {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                            if let streak = card.streakLine {
                                Text(streak).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            }
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(card.spoken)
                        HStack(spacing: MekaSpace.xs) {
                            MailNeedsPill(label: MailMomentRules.shared.START, palette: palette,
                                          spoken: "Start the ten-minute inbox: \(card.headline)",
                                          action: { model.startMailMoment() })
                            MailNeedsPill(label: MailMomentRules.shared.NOT_TODAY, palette: palette, quiet: true,
                                          spoken: "Not today: hide the ten-minute inbox until tomorrow",
                                          action: { model.notTodayMailMoment() })
                        }
                        .padding(.top, MekaSpace.xs)
                    }
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailMoment == nil && model.mailMomentSession == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailMomentSession?.row?.id)
    }

    @ViewBuilder
    private func sessionBody(_ session: MailMomentSession) -> some View {
        let line = [session.position, session.line].compactMap { $0 }.joined(separator: " · ")
        Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            .accessibilityAddTraits(.updatesFrequently)
        if let row = session.row {
            let id = row.id
            MailNeedsRowView(row: row, palette: palette,
                             done: { model.doneMailItem(id) },
                             task: { model.taskFromMailItem(id) },
                             waiting: { model.waitingFromMailItem(id) },
                             snooze: { model.snoozeMailItem(id) },
                             send: { text, sent in model.replyToMailItem(id, text, sent: sent) },
                             draft: { await model.draftMailReply(id) },
                             block: { model.blockMailItem(id) },
                             unsubscribe: { model.unsubscribeMailItem(id) },
                             suggestTimes: { await model.suggestMeetingTimes(id) },
                             book: { model.bookMeetingPick(id) },
                             read: { images in await model.readMailItem(id, images: images) },
                             attachment: { v, a in await model.openMailAttachment(v, a) },
                             toDay: { day in model.mailToDay(id, day: day) },
                             toDays: { model.boardFeedDays() })
                .id(id)
                .padding(.top, MekaSpace.xs)
                .offset(x: reduceMotion ? 0 : dragged)
                .gesture(
                    DragGesture(minimumDistance: 12)
                        .onChanged { v in dragged = v.translation.width }
                        .onEnded { v in
                            if abs(v.translation.width) > 72 { model.skipMailMoment(id) }
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { dragged = 0 }
                        }
                )
                .transition(reduceMotion ? .opacity : .asymmetric(
                    insertion: .opacity.combined(with: .move(edge: .trailing)),
                    removal: .opacity.combined(with: .move(edge: .leading))))
            HStack(spacing: MekaSpace.xs) {
                if session.left > 1 {
                    MailNeedsPill(label: MailMomentRules.shared.SKIP, palette: palette, quiet: true,
                                  spoken: "Skip this email for now; it comes round again",
                                  action: { model.skipMailMoment(id) })
                }
                MailNeedsPill(label: MailMomentRules.shared.FINISH, palette: palette, quiet: true,
                              spoken: "Finish for now; the rest stay in From your mail",
                              action: { model.endMailMoment() })
            }
            .padding(.top, MekaSpace.xxs)
        } else {
            if let streak = session.streakLine {
                Text(streak).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            MailNeedsPill(label: MailMomentRules.shared.CLOSE, palette: palette,
                          spoken: "Close the ten-minute inbox", action: { model.endMailMoment() })
                .padding(.top, MekaSpace.xs)
        }
    }
}

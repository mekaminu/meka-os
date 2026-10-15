@preconcurrency import MekaKit
import SwiftUI

/// The Mac shell (build plan M1; Four tabs, one front door): a sidebar with the four tabs (Today · Needs you · Calendar ·
/// Ask) and, below them, a More section with the places that sit behind Ask (Lists · Goals · Review · Vault; the Mac
/// has the room, as rule 7 allows), beside the destination. Switching pushes the content the way you moved down the
/// sidebar; Reduce Motion cross-fades.
struct ShellView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        NavigationSplitView {
            List(selection: selection) {
                ForEach(ShellNav.destinations(.rail)) { d in
                    Label { Text(d.label) } icon: { TabIconView(symbol: d.symbol, lit: model.destination == d) }
                        .badge(d == .needsYou ? ShellNav.badge(needsYouCount).map { Text($0) } : nil)
                        .accessibilityLabel(ShellNav.accessibilityLabel(d, needsYouCount: needsYouCount))
                        .tag(d as ShellDestination?)
                }
                Section("More") {
                    ForEach(ShellDestination.allCases.filter { ShellNav.parent($0) != nil }) { d in
                        Label { Text(d.label) } icon: { TabIconView(symbol: d.symbol, lit: model.destination == d) }
                            .tag(d as ShellDestination?)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 200, max: 260)
        } detail: {
            ZStack {
                destination(model.destination)
                    .id(model.destination)
                    .transition(transition)
            }
            .clipped()
            // Calendar actions: "Hidden from your day · Undo" rises over whichever screen you did it on.
            .overlay(alignment: .bottom) { EventUndoBar(palette: palette).padding(.bottom, MekaSpace.xxl) }
        }
        // The ⌘K command bar, over the whole window (sidebar included).
        .overlay {
            if model.showCommandBar {
                CommandBarOverlay(palette: palette)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .scale(scale: 0.96, anchor: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.showCommandBar)
        .background(palette.background)
        // The shell's sheets, in groups so each one type-checks quickly (one long chain timed out on the Mac CI).
        .modifier(ShellSheetsA(palette: palette))
        .modifier(ShellSheetsB(palette: palette))
        .modifier(ShellSheetsC(palette: palette))
        .modifier(ShellSheetsD(palette: palette))
    }

    /// Due chases and decision reviews wait on you too, so they count in the badge (as on the Fold).
    private var needsYouCount: Int { (model.today?.needsYou.count ?? 0) + model.listsDue + model.approvals.count + model.requests.count + Int(TriageChatRules.shared.replyChats(cards: model.triage)) + model.needsYouAsks }

    /// Sidebar selection that animates the switch (the List only ever sets a value; nil is ignored).
    private var selection: Binding<ShellDestination?> {
        Binding(
            get: { model.destination },
            set: { new in if let new { model.go(to: new, reduced: reduceMotion) } }
        )
    }

    private var transition: AnyTransition {
        if reduceMotion || model.lastDirection == 0 { return .opacity }
        // Moving down the sidebar, the new screen arrives from below and the old one leaves upwards.
        return .push(from: model.lastDirection > 0 ? .bottom : .top).combined(with: .opacity)
    }

    @ViewBuilder
    private func destination(_ d: ShellDestination) -> some View {
        switch d {
        case .today: TodayView()
        case .needsYou: NeedsYouView()
        case .calendar: CalendarScreen()
        case .ask: AskScreen(palette: palette)
        case .lists: ListsScreen()
        case .goals: GoalsScreen()
        case .review: ReviewScreen()
        case .vault: VaultScreen(palette: palette)
        }
    }
}

/// A sidebar row's icon (motion pass 2, screen-level motion; catalogue "Switch section"): the outline symbol while
/// quiet, its filled variant when the row is lit (where the symbol has one), morphing with the system's replace
/// transition and a small bounce, like the Fold's drawn tab icons swelling as they fill. Reduce Motion / Motion → Off:
/// the fill cross-fades, no bounce. Decorative: the row's label is what VoiceOver reads.
struct TabIconView: View {
    let symbol: String
    let lit: Bool
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Image(systemName: symbol)
            .symbolVariant(lit ? .fill : .none)
            .contentTransition(reduceMotion ? ContentTransition.opacity : ContentTransition.symbolEffect(.replace))
            // Off: the value never changes, so it never bounces.
            .symbolEffect(.bounce, value: reduceMotion ? false : lit)
            .animation(MekaMotion.approve(reduced: reduceMotion), value: lit)
            .accessibilityHidden(true)
    }
}

/// NEEDS YOU on the Mac: a stack of decisions beside the detail (four tabs, slice 2): conflicts, overdue, due today
/// but unscheduled, then "From your lists". → yes/do, ← later, ↑ open (keys, buttons or a drag). Approvals join in V1.
struct NeedsYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        HSplitView {
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("Needs you")
                        .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                        .foregroundStyle(palette.textPrimary)
                        .padding(.bottom, MekaSpace.l)
                        .staggeredAppear(0)
                    // The approvals inbox (V1, slice 1) first: time-limited, and nothing happens until Meka answers.
                    ApprovalCardsView(palette: palette, firstIndex: 1)
                        .padding(.bottom, model.approvals.isEmpty ? 0 : MekaSpace.m)
                    // What the Fold held at work (synced): the card after work, a quiet count during it.
                    AfterWorkCard(palette: palette)
                        .padding(.bottom, MekaSpace.m)
                        .staggeredAppear(1)
                    // Messages the Fold triaged (V1, messages slice 3), then requests from people Meka watches
                    // (V1, requests slice 4), above the stack.
                    TriageCardsView(palette: palette)
                        .padding(.bottom, model.triage.isEmpty ? 0 : MekaSpace.m)
                    // School days off on office days, a week ahead (school rhythm, slice 1), above the requests.
                    SchoolCoversView(palette: palette, firstIndex: 2 + model.triage.count)
                        .padding(.bottom, model.needsYouAsks == 0 ? 0 : MekaSpace.m)
                    RequestCardsView(palette: palette, firstIndex: 2 + model.triage.count + model.needsYouAsks)
                        .padding(.bottom, model.looseRequests.isEmpty ? 0 : MekaSpace.m)
                    // The group digest the Fold made at 12:30 / 18:30 (V1, messages slice 4b): gists, never the messages.
                    GroupGistsView(palette: palette, firstIndex: 2 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.digestGists.isEmpty ? 0 : MekaSpace.m)
                    // The ten-minute inbox (Inbox autopilot, slice 7): from the moment, one email at a time.
                    MailMomentView(palette: palette, firstIndex: 3 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.mailMoment == nil && model.mailMomentSession == nil ? 0 : MekaSpace.m)
                    // The emails that need Meka (Inbox autopilot, slice 6a), each with Done and Snooze.
                    MailNeedsView(palette: palette, firstIndex: 3 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.mailNeeds == nil || model.mailMomentSession != nil ? 0 : MekaSpace.m)
                    // Waiting for items from email whose chase is due (Inbox autopilot, slice 6b-3), each drafted.
                    MailChaseView(palette: palette, firstIndex: 3 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.mailChases == nil ? 0 : MekaSpace.m)
                    // Renewals read from his mail (Promises, receipts and renewals, slice 1), added only on his tap.
                    MailFindView(palette: palette, firstIndex: 3 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.mailFinds == nil ? 0 : MekaSpace.m)
                    // What Autopilot archived (Inbox autopilot, slice 4), with the digests until Got it.
                    ArchivedForYouView(palette: palette, firstIndex: 3 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.archivedForYou == nil ? 0 : MekaSpace.m)
                    // The weekly "Not opened in 30 days" (Inbox autopilot, slice 5c), from Saturday 09:00 until answered.
                    UnopenedOfferView(palette: palette, firstIndex: 3 + model.triage.count + model.needsYouAsks + model.requests.count)
                        .padding(.bottom, model.mailUnopened == nil ? 0 : MekaSpace.m)
                    if model.needsYouCards.isEmpty && model.approvals.isEmpty && model.requests.isEmpty && model.triage.isEmpty && model.needsYouAsks == 0 {
                        // The breathing check ring beside a light line and what lands here (catalogue "Empty
                        // states"; Fold review 2026-10-08, item 7); Off: still.
                        HStack(spacing: MekaSpace.m) {
                            BreathingRingView(palette: palette, check: true)
                            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                                Text(NeedsYouStackRules.shared.emptyLine(toCatchUp: model.afterWork?.toCatchUp ?? 0))
                                    .font(MekaType.body).foregroundStyle(palette.textPrimary)
                                Text(NeedsYouStackRules.shared.EMPTY_CAPTION)
                                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            }
                        }
                        .accessibilityElement(children: .combine)
                        .staggeredAppear(1)
                        // Then what's coming: today's habits, Waiting on, the next renewals.
                        NeedsYouMeanwhileView(palette: palette)
                    } else if !model.needsYouCards.isEmpty {
                        NeedsYouStackView(palette: palette).staggeredAppear(1)
                    }
                }
                .padding(.horizontal, MekaSpace.gutterWide)
                .padding(.vertical, MekaSpace.xl)
            }
            .frame(minWidth: 380, idealWidth: 520)
            DetailView(task: model.selected, palette: palette, growsFromRow: true)
                .frame(minWidth: 280, idealWidth: 360)
        }
        .background(palette.background)
    }
}

/// The shell's sheets, group 1 of 4 (split from ShellView's body so the Swift type-checker keeps up).
private struct ShellSheetsA: ViewModifier {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    func body(content: Content) -> some View {
        content
            // Search everything (⌘F from anywhere): results, with an open task's detail beside them.
            .sheet(isPresented: Binding(get: { model.showSearch }, set: { model.showSearch = $0 })) { SearchSheet(palette: palette) }
            // Event detail (calendar redesign, slice 3), opened from Today or the Calendar section.
            .sheet(isPresented: Binding(get: { model.openEvent != nil }, set: { if !$0 { model.openEvent = nil } })) {
                EventDetailSheet(palette: palette)
            }
            // Self-updating phone app: publish the APK this Mac built for the Fold to offer.
            .sheet(isPresented: Binding(get: { model.showFoldUpdate }, set: { model.showFoldUpdate = $0 })) { FoldUpdateSheet(palette: palette) }
            // Export everything (your data), from Today's header or File → Export All Data….
            .sheet(isPresented: Binding(get: { model.showYourData }, set: { model.showYourData = $0 })) { YourDataSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showActivity }, set: { model.showActivity = $0 })) { ActivitySheet(palette: palette) }
            // MEKA's voice (Ask → More): the synced voice for Talk, the spoken brief and calls.
            .sheet(isPresented: Binding(get: { model.showVoice }, set: { model.showVoice = $0 })) { VoiceSheet(palette: palette) }
            // Talk (Ask → More): ⌥Space and the mic, and why it stays safe.
            .sheet(isPresented: Binding(get: { model.showTalk }, set: { model.showTalk = $0 })) { TalkSheet(palette: palette) }
            // Health (Ask → More, and Today's health line): everything MEKA depends on, with the next step.
            .sheet(isPresented: Binding(get: { model.showHealth }, set: { model.showHealth = $0 })) { HealthSheet(palette: palette) }
            // Setup (Ask → More, and Today's setup card): every capability with a tick or the next step.
            .sheet(isPresented: Binding(get: { model.showSetup }, set: { model.showSetup = $0 })) { SetupSheet(palette: palette) }
            // Opened from Today's header or Ask's More list, so they live on the shell.
            .sheet(isPresented: Binding(get: { model.showCalendars }, set: { model.showCalendars = $0 })) { CalendarsSheet(palette: palette) }
            // Family (Ask → More): Jeanette's link to the shopping list.
            .sheet(isPresented: Binding(get: { model.showFamily }, set: { model.showFamily = $0 })) { FamilySheet(palette: palette) }
            // Watch (Ask → More): link a Galaxy Watch with the code it shows.
            .sheet(isPresented: Binding(get: { model.showWatch }, set: { model.showWatch = $0 })) { WatchSheet(palette: palette) }
    }
}

/// The shell's sheets, group 2 of 4 (split from ShellView's body so the Swift type-checker keeps up).
private struct ShellSheetsB: ViewModifier {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    func body(content: Content) -> some View {
        content
            // Recovery key (Ask → More → Settings, and Setup's step): the 24 words that connect a new device.
            .sheet(isPresented: Binding(get: { model.showRecovery }, set: { model.showRecovery = $0 })) { RecoveryKeySheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showWork }, set: { model.showWork = $0 })) { WorkSheet(palette: palette) }
            // "While you were at work" (synced from the Fold), opened from Needs you's card.
            .sheet(isPresented: Binding(get: { model.showAfterWork }, set: { model.showAfterWork = $0 })) { AfterWorkSheet(palette: palette) }
            // The brief and the shutdown open from Today's cards and from Ask's More, so they live on the shell too.
            .sheet(isPresented: Binding(get: { model.showBrief }, set: { model.showBrief = $0 })) { BriefSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showShutdown }, set: { model.showShutdown = $0 })) { ShutdownSheet(palette: palette) }
            // School (Ask → More): Rex's and Logan's days off, dates and weekly things.
            .sheet(isPresented: Binding(get: { model.showSchool }, set: { model.showSchool = $0 })) { SchoolSheet(palette: palette) }
            // Dinners (Ask → More): favourites, the week's dinners and their shopping (meal plan, slice 1).
            .sheet(isPresented: Binding(get: { model.showMeals }, set: { model.showMeals = $0 })) { MealsSheet(palette: palette) }
            // What MEKA may do (Ask → More): the pause switch, earned trust and the locked rows (policy engine UI, slice 1).
            .sheet(isPresented: Binding(get: { model.showAutonomy }, set: { model.showAutonomy = $0 })) { AutonomySheet(palette: palette) }
            // Protected time (Ask → More): evenings and weekends kept free of tasks (protected time, slice 1).
            .sheet(isPresented: Binding(get: { model.showProtected }, set: { model.showProtected = $0 })) { ProtectedTimeSheet(palette: palette) }
            // Date night (Ask → More): the fortnightly evening kept clear (date night, slice 1).
            .sheet(isPresented: Binding(get: { model.showDateNight }, set: { model.showDateNight = $0 })) { DateNightSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showLifeAdmin }, set: { model.showLifeAdmin = $0 })) { LifeAdminSheet(palette: palette) }
            // Emergency card (Ask → More): who to call, where things are, key accounts by name; Jeanette reads it.
            .sheet(isPresented: Binding(get: { model.showEmergency }, set: { model.showEmergency = $0 })) { EmergencySheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showKids }, set: { model.showKids = $0 })) { KidsSheet(palette: palette) }
    }
}

/// The shell's sheets, group 3 of 4 (split from ShellView's body so the Swift type-checker keeps up).
private struct ShellSheetsC: ViewModifier {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    func body(content: Content) -> some View {
        content
            // Annual leave (Ask → More): the allowance, the days booked and the school holidays.
            .sheet(isPresented: Binding(get: { model.showLeave }, set: { model.showLeave = $0 })) { LeaveSheet(palette: palette) }
            // Train queue (Ask → More, or Today's "For the train"): links and podcasts saved for the commute.
            .sheet(isPresented: Binding(get: { model.showTrainQueue }, set: { model.showTrainQueue = $0 })) { TrainQueueSheet(palette: palette) }
            // Voice notes (Ask → Daily, slice 3): the notes this Mac keeps; and the sheet that records one.
            .sheet(isPresented: Binding(get: { model.showVoiceNotes }, set: { model.showVoiceNotes = $0 })) { VoiceNotesSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showVoiceNote }, set: { model.showVoiceNote = $0 })) { VoiceNoteSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showWeekAhead }, set: { model.showWeekAhead = $0 })) { WeekAheadSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showErrands }, set: { model.showErrands = $0 })) { ErrandsSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showAppointments }, set: { model.showAppointments = $0 })) { AppointmentsSheet(palette: palette) }
            // Bins (Ask → More): each bin's day and pattern, and the next collection.
            .sheet(isPresented: Binding(get: { model.showBins }, set: { model.showBins = $0 })) { BinsSheet(palette: palette) }
            // Car (Ask → Life): the parking timer; the cars' dates open Lists → Renewals.
            .sheet(isPresented: Binding(get: { model.showCar }, set: { model.showCar = $0 })) { CarSheet(palette: palette) }
            // Gifts (Ask → More): birthdays and anniversaries, gift ideas and the order-by days.
            .sheet(isPresented: Binding(get: { model.showGifts }, set: { model.showGifts = $0 })) { GiftsSheet(palette: palette) }
            // People (Ask → More): promises both ways, birthdays and last contact, by person.
            .sheet(isPresented: Binding(get: { model.showPeople }, set: { model.showPeople = $0 })) { PeopleSheet(palette: palette) }
            // Trips (Ask → More): trips and their bookings, day by day.
            .sheet(isPresented: Binding(get: { model.showTrips }, set: { model.showTrips = $0 })) { TripsSheet(palette: palette) }
            // What MEKA knows (Ask → More): the facts page Ask and Talk answer from.
            .sheet(isPresented: Binding(get: { model.showFacts }, set: { model.showFacts = $0 })) { FactsSheet(palette: palette) }
            // What MEKA knows, slice 4: a form shared with "Handle this with MEKA" answered from the facts page.
            .sheet(isPresented: Binding(get: { model.showFormAnswers }, set: { model.showFormAnswers = $0 })) { FormAnswersSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showNews }, set: { model.showNews = $0 })) { NewsSheet(palette: palette) }
    }
}

/// The shell's sheets, group 4 of 4 (split from ShellView's body so the Swift type-checker keeps up).
private struct ShellSheetsD: ViewModifier {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { model.showNotifications }, set: { model.showNotifications = $0 })) {
                NotificationsSheet(palette: palette)
            }
            // About you (rule 13, slice 1): who's who, home and work, kept in his data.
            .sheet(isPresented: Binding(get: { model.showAboutYou }, set: { model.showAboutYou = $0 })) {
                AboutYouSheet(palette: palette)
            }
            // Mail (Inbox autopilot, slice 1): connect Gmail and Outlook, label each account.
            .sheet(isPresented: Binding(get: { model.showMail }, set: { model.showMail = $0 })) {
                MailSheet(palette: palette)
            }
    }
}

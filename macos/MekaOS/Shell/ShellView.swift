@preconcurrency import MekaKit
import OSLog
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

    /// The sidebar's own selection, a plain stored value (Meka, 2026-10-15: on macOS 27 a List bound to a computed
    /// Binding into the model never switched screens). It follows the model, and a change made here goes to it.
    @State private var sidebarPick: ShellDestination? = .today
    /// Mac presentation pass, slice 2 (item 8): a window that opens wide (core MacHeaderRules.SIDEBAR_PT) shows the
    /// sidebar, so the screens are in view on first launch; decided once per window, collapsible as before.
    @State private var columns: NavigationSplitViewVisibility = .automatic
    @State private var sidebarDecided = false

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            List(selection: $sidebarPick) {
                ForEach(ShellNav.destinations(.rail)) { d in
                    sidebarRow(d)
                        .badge(d == .needsYou ? ShellNav.badge(needsYouCount).map { Text($0) } : nil)
                        .accessibilityLabel(ShellNav.accessibilityLabel(d, needsYouCount: needsYouCount))
                        .tag(d as ShellDestination?)
                }
                Section("More") {
                    ForEach(ShellNav.sidebarRows.filter { ShellNav.parent($0) != nil }) { d in
                        sidebarRow(d)
                            .tag(d as ShellDestination?)
                    }
                }
            }
            .onChange(of: sidebarPick) { _, picked in go(picked, via: "selection") }
            // ⌘1–⌘8, the command bar, search and notifications move the model; the highlight follows.
            .onChange(of: model.destination, initial: true) { _, now in if sidebarPick != now { sidebarPick = now } }
            // "Hello, Navilife" slice 2: the Hello orb at the sidebar's foot; a click greets him on Ask.
            .safeAreaInset(edge: .bottom) {
                SayHelloButton(palette: palette)
                    .padding(.horizontal, MekaSpace.m)
                    .padding(.vertical, MekaSpace.xs)
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 200, max: 260)
        } detail: {
            ZStack {
                destination(model.destination)
                    // The UI-test target checks the screen a click switched to by this.
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier(ShellView.screenID(model.destination))
                    .id(model.destination)
                    // Part (4): the click to this screen appearing, into Health → Speed (aim 150 ms).
                    .onAppear { model.switchShown(model.destination) }
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
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { openSidebarIfWide($0) }
        // `-MekaWindowSize 1440x900` (the UI-test target's window sizes); an ordinary launch does nothing.
        .onAppear { LaunchWindowSize.apply() }
        .background(palette.background)
        // The shell's sheets, in groups so each one type-checks quickly (one long chain timed out on the Mac CI).
        .modifier(ShellSheetsA(palette: palette))
        .modifier(ShellSheetsB(palette: palette))
        .modifier(ShellSheetsC(palette: palette))
        .modifier(ShellSheetsD(palette: palette))
    }

    /// The first real width this window gets: wide enough, the sidebar shows (on the expand spring; Reduce Motion: at
    /// once). Later resizes leave his choice alone.
    private func openSidebarIfWide(_ width: CGFloat) {
        guard !sidebarDecided, width > 0 else { return }
        sidebarDecided = true
        // A launch that asks for its window size (the UI checks) is judged at that size, not the default it opens at.
        let opening = LaunchWindowSize.requested()?.width ?? width
        guard MacHeaderRules.shared.sidebarOnOpen(windowWidthPt: Float(opening)), columns != .all else { return }
        if reduceMotion { columns = .all } else { withAnimation(MekaMotion.expand(reduced: false)) { columns = .all } }
    }

    /// Due chases and decision reviews wait on you too, so they count in the badge (as on the Fold).
    private var needsYouCount: Int { (model.today?.needsYou.count ?? 0) + model.listsDue + model.approvals.count + model.requests.count + Int(TriageChatRules.shared.replyChats(cards: model.triage)) + model.needsYouAsks }

    /// A sidebar row: the whole row width takes the click, and a click switches straight away as well as selecting
    /// (a simultaneous tap, so the List's own selection still works; whichever comes first wins, the other is a no-op).
    private func sidebarRow(_ d: ShellDestination) -> some View {
        Label { Text(d.label) } icon: { TabIconView(symbol: d.symbol, lit: model.destination == d) }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .simultaneousGesture(TapGesture().onEnded { go(d, via: "click") })
            // The UI-test target clicks each row by this (MekaOSUITests/SidebarUITests).
            .accessibilityIdentifier(ShellView.sidebarID(d))
    }

    /// Animates the switch; nil or the screen already showing does nothing.
    private func go(_ picked: ShellDestination?, via: String) {
        guard let to = ShellNav.sidebarPick(picked, current: model.destination) else { return }
        let name = to.label // read here, on the main actor, not inside the log's autoclosure
        ShellView.log.debug("sidebar \(via, privacy: .public) → \(name, privacy: .public)")
        model.go(to: to, reduced: reduceMotion)
    }

    /// Accessibility identifiers for the UI-test target: "sidebar-calendar", "screen-calendar".
    static func sidebarID(_ d: ShellDestination) -> String { "sidebar-" + String(describing: d) }
    static func screenID(_ d: ShellDestination) -> String { "screen-" + String(describing: d) }

    private static let log = Logger(subsystem: "os.meka.MekaOS", category: "shell")

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

/// NEEDS YOU on the Mac, in the approved look (`docs/design/needs-you/needs-you.dc.html`, Mac slice 5), grouped and
/// folding (`needs-you-grouped.dc.html`, slice 4): "Needs you" over "2 to decide · 4 to reply · 4 FYI · 14 mail" with
/// Open all / Close all, the chips All · Decide · Reply · FYI · Mail (a chip shows just its groups and opens them), then
/// every section as one box (To decide, Messages, FYI, Group chats, Mail to answer, Renewals found in mail, Mail tidied
/// today) with its icon, count and a one-line peek, a click to open or close it; To decide and Messages open at first and
/// this Mac remembers what he left open. To decide's foot is the after-work line; From your lists is its lead row with
/// Go through; FYI leads with Clear all; Messages and FYI show five a page. The detail sits beside it.
struct NeedsYouView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var filter: NeedsYouFilter = .all
    @AppStorage(NeedsYouOpenGroups.key) private var savedOpen: String?
    @State private var msgPage = 0
    @State private var fyiPage = 0
    @State private var mailPage = 0
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    private var decideCount: Int {
        model.approvals.count + model.looseRequests.count + model.needsYouAsks + model.needsYouCards.count
    }

    private var openGroups: Set<NeedsYouGroup> { NeedsYouOpenGroups.decode(savedOpen) }

    private func setOpen(_ open: Set<NeedsYouGroup>) {
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { savedOpen = NeedsYouOpenGroups.encode(open) }
    }

    /// The after-work line at To decide's foot: the held line at work, else who and what waited; nil when nothing did.
    private var afterWorkFoot: String? {
        guard let summary = model.afterWork, !summary.isEmpty else { return nil }
        let held = (model.work?.atWork ?? false) ? model.heldPreview?.label : nil
        return NeedsYouGroupRules.shared.afterWorkFoot(summary: summary, heldLabel: held)
    }

    /// The "From your lists" card, which leaves the stack to be To decide's lead row.
    private var listsCard: DecisionCard? { model.needsYouCards.first { $0.kind == .lists } }

    private func facts(_ chats: [TriageChat]) -> NeedsYouGroupFacts {
        let mailMomentOn = model.mailMoment != nil || model.mailMomentSession != nil
        let renewals = model.mailFinds.map { $0.rows.map(\.title) }
        let tiles = model.needsYouMail?.tiles ?? []
        let tidiedAny = model.needsYouMail != nil || model.archivedForYou != nil || model.mailUnopened != nil
        return NeedsYouGroupFacts(
            decideCount: Int32(decideCount),
            decidePeek: NeedsYouGroupRules.shared.decidePeekOf(
                approvals: Int32(model.approvals.count), requests: Int32(model.looseRequests.count),
                asks: Int32(model.needsYouAsks), cards: model.needsYouCards),
            afterWorkFoot: afterWorkFoot,
            messages: NeedsYouLayoutRules.shared.messages(chats: chats).map(\.title),
            fyi: NeedsYouLayoutRules.shared.fyi(chats: chats).map(\.title),
            chatsCount: Int32(model.digestGists.count + (model.discord?.cards.count ?? 0)),
            chatsPeek: NeedsYouGroupRules.shared.chatsPeek(groups: model.digestGists.map(\.title), discord: model.discord?.headline),
            mailAny: mailMomentOn || model.mailNeeds != nil || model.mailChases != nil,
            mailCount: Int32(Int(model.mailNeeds?.count ?? 0) + (model.mailChases?.rows.count ?? 0)),
            mailPeek: NeedsYouGroupRules.shared.mailPeek(momentDue: model.mailMoment != nil,
                                                         rows: model.mailNeeds?.every ?? [], chases: model.mailChases?.headline),
            // Slice 4b: every email that needs him pages in the box, except while the ten-minute inbox runs.
            mailRows: model.mailMomentSession == nil ? Int32(model.mailNeeds?.every.count ?? 0) : 0,
            renewals: renewals,
            tidiedAny: tidiedAny,
            tidiedCount: Int32(tiles.first.flatMap { Int($0.number) } ?? 0),
            tidiedPeek: tiles.isEmpty ? (model.archivedForYou == nil ? "" : "Archived for you")
                : NeedsYouGroupRules.shared.tidiedPeek(tiles: tiles))
    }

    var body: some View {
        let chats = model.triageChats
        let counts = NeedsYouHeaderCounts(decide: decideCount, chats: chats, mail: Int(model.mailNeeds?.count ?? 0))
        let f = facts(chats)
        let present = NeedsYouGroupRules.shared.present(facts: f, filter: filter)
        let open = openGroups
        HSplitView {
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        NeedsYouHeaderView(palette: palette, counts: counts, onClearFyi: {
                            model.clearFyiChats(NeedsYouLayoutRules.shared.fyi(chats: chats))
                        }, allLabel: present.isEmpty ? nil : NeedsYouGroupRules.shared.allLabel(open: open, shown: present),
                           onAll: { setOpen(NeedsYouGroupRules.shared.all(open: open, shown: present)) })
                        NeedsYouChipsView(palette: palette, chips: counts.chips, chosen: $filter)
                    }
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(0)
                    // "Handle her requests for me": what was done for him, above the boxes.
                    DoneForYouView(palette: palette, firstIndex: 1)
                    ForEach(Array(present.enumerated()), id: \.element.id) { i, group in
                        box(group, facts: f, open: open, chats: chats)
                            .staggeredAppear(1 + i)
                    }
                    if filter == .all { emptyState }
                    // Likely-scam texts, greyed at the foot (Mac presentation pass, item 6).
                    if NeedsYouGroupRules.shared.shows(filter: filter, group: .messages) { ScamTextsView(palette: palette) }
                    if !present.isEmpty {
                        Text(NeedsYouGroupRules.shared.HINT_MAC)
                            .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.xs).padding(.leading, MekaSpace.xxs)
                    }
                }
                .animation(MekaMotion.expand(reduced: reduceMotion), value: filter)
                .padding(.horizontal, MekaSpace.gutterWide)
                .padding(.vertical, MekaSpace.xl)
            }
            .frame(minWidth: 380, idealWidth: 520)
            DetailView(task: model.selected, palette: palette, growsFromRow: true)
                .frame(minWidth: 280, idealWidth: 360)
        }
        .background(palette.background)
        .onChange(of: filter) { _, chosen in setOpen(NeedsYouGroupRules.shared.pick(open: openGroups, filter: chosen)) }
    }

    /// One group's box: its head, then (while open) what it holds, fading in on the expand spring.
    @ViewBuilder private func box(_ group: NeedsYouGroup, facts f: NeedsYouGroupFacts, open: Set<NeedsYouGroup>,
                                  chats: [TriageChat]) -> some View {
        let head = NeedsYouGroupRules.shared.headOf(group: group, facts: f, open: open,
                                                    msgPage: Int32(msgPage), fyiPage: Int32(fyiPage), mailPage: Int32(mailPage))
        VStack(alignment: .leading, spacing: 0) {
            NeedsYouGroupHeadView(head: head, palette: palette) {
                setOpen(NeedsYouGroupRules.shared.toggle(open: open, group: group))
            }
            if head.open {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    contents(group, facts: f, chats: chats)
                }
                .padding(MekaSpace.xs)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surface.opacity(0.5), in: UnevenRoundedRectangle(
                    topLeadingRadius: 0, bottomLeadingRadius: MekaRadius.m, bottomTrailingRadius: MekaRadius.m,
                    topTrailingRadius: 0, style: .continuous))
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.bottom, MekaSpace.xs)
    }

    @ViewBuilder private func contents(_ group: NeedsYouGroup, facts f: NeedsYouGroupFacts, chats: [TriageChat]) -> some View {
        switch group {
        case .decide: decideSection
        case .messages:
            TriageCardsView(palette: palette, firstIndex: 0, rows: true, boxedPage: msgPage)
            pager(total: f.messages.count, page: msgPage) { msgPage = $0 }
        case .fyi:
            let fyi = NeedsYouLayoutRules.shared.fyi(chats: chats)
            NeedsYouGroupLeadView(line: NeedsYouGroupRules.shared.FYI_LEAD, button: NeedsYouGroupRules.shared.CLEAR_ALL,
                                  palette: palette) { model.clearFyiChats(fyi) }
            let page = NeedsYouGroupRules.shared.page(total: Int32(fyi.count), page: Int32(fyiPage),
                                                      size: NeedsYouGroupRules.shared.PAGE_SIZE)
            NeedsYouFyiRowsView(palette: palette, chats: needsYouPageRows(fyi, page), firstIndex: 0, boxed: true)
            pager(total: fyi.count, page: fyiPage) { fyiPage = $0 }
        case .chats:
            GroupGistsView(palette: palette, firstIndex: 0)
            DiscordCardsView(palette: palette, firstIndex: 0)
        case .mail:
            // Slice 4b: the due ten-minute inbox is the box's lead line (Start, Not today with Undo on the bar); once
            // under way it takes the box, one email at a time. Every email that needs him is a thin row, five a page.
            if let lead = NeedsYouGroupRules.shared.mailLead(momentDue: model.mailMoment != nil,
                                                             running: model.mailMomentSession != nil) {
                NeedsYouGroupLeadView(line: lead, button: NeedsYouGroupRules.shared.MAIL_START, palette: palette,
                                      action: { model.startMailMoment() },
                                      second: MailMomentRules.shared.NOT_TODAY, onSecond: { model.notTodayMailMoment() })
                    .accessibilityIdentifier("needs-you-mail-lead")
            }
            if model.mailMomentSession != nil { MailMomentView(palette: palette, firstIndex: 0) }
            MailNeedsView(palette: palette, firstIndex: 0, boxed: true, page: mailPage) { next in
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { mailPage = next }
            }
            MailChaseView(palette: palette, firstIndex: 0)
        case .renewals:
            MailFindView(palette: palette, firstIndex: 0)
        default:
            NeedsYouMailGroup(palette: palette, firstIndex: 0)
        }
    }

    /// "1–5 of 14" with ‹ › under a group longer than a page.
    @ViewBuilder private func pager(total: Int, page: Int, go: @escaping (Int) -> Void) -> some View {
        let p = NeedsYouGroupRules.shared.page(total: Int32(total), page: Int32(page), size: NeedsYouGroupRules.shared.PAGE_SIZE)
        if p.pages > 1 {
            NeedsYouPagerView(page: p, palette: palette) { next in
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { go(next) }
            }
        }
    }

    /// To decide: From your lists as one row with Go through, the approvals inbox (time-limited, nothing happens until
    /// Meka answers), school days off on office days, date night and appointments, requests from people he watches, the
    /// rest of the stack, then the after-work foot line.
    @ViewBuilder private var decideSection: some View {
        if let lists = listsCard {
            NeedsYouGroupLeadView(line: NeedsYouGroupRules.shared.listsLead(why: lists.why), button: lists.yesLabel,
                                  palette: palette) { model.decide(lists, .yes, reduced: reduceMotion) }
        }
        ApprovalCardsView(palette: palette, firstIndex: 0)
        SchoolCoversView(palette: palette, firstIndex: 0)
        RequestCardsView(palette: palette, firstIndex: 0)
        if model.needsYouCards.contains(where: { $0.id != listsCard?.id }) {
            NeedsYouStackView(palette: palette, leaveOut: listsCard?.id)
            MailWorkingView(palette: palette)
            // Under the stack, the next reminders (the gate's Needs you re-shot).
            NeedsYouMeanwhileView(palette: palette, underStack: true)
        }
        if let foot = afterWorkFoot {
            NeedsYouGroupFootView(line: foot, palette: palette) { model.showAfterWork = true }
        }
    }

    /// Nothing to decide or answer: the breathing check ring beside a light line and what lands here (catalogue
    /// "Empty states"), what Navilife is doing with his mail, then what's coming (habits, Waiting on, renewals).
    @ViewBuilder private var emptyState: some View {
        if model.needsYouCards.isEmpty && model.approvals.isEmpty && model.requests.isEmpty && model.triageChats.isEmpty && model.needsYouAsks == 0 {
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
            MailWorkingView(palette: palette)
            NeedsYouMeanwhileView(palette: palette)
        }
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
            // Family (Ask → More): his wife's link to the shopping list.
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
            // "Archived for you" shows which emails, slice 3: All tidied mail, opened from the card.
            .sheet(isPresented: Binding(get: { model.showArchivedAll }, set: { model.showArchivedAll = $0 })) { ArchivedAllSheet(palette: palette) }
            // The brief and the shutdown open from Today's cards and from Ask's More, so they live on the shell too.
            .sheet(isPresented: Binding(get: { model.showBrief }, set: { model.showBrief = $0 })) { BriefSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showShutdown }, set: { model.showShutdown = $0 })) { ShutdownSheet(palette: palette) }
            // The Tomorrow sheet (Meka 2026-10-26): from Today's Tomorrow peek or the date under the greeting.
            .sheet(isPresented: Binding(get: { model.showTomorrow }, set: { model.showTomorrow = $0 })) { TomorrowSheet(palette: palette) }
            // The Day list (approved 2026-11-08): from Today's "Day list ›" and ⌘N.
            .sheet(isPresented: Binding(get: { model.showDayList }, set: { model.showDayList = $0 })) { DayListSheet(palette: palette) }
            // Plan your week (Week board item (6)): from the Board, Sunday's Needs you card and "plan my week".
            .sheet(isPresented: Binding(get: { model.showPlanWeek }, set: { model.showPlanWeek = $0 })) { PlanWeekSheet(palette: palette) }
            // School (Ask → More): Theo's and Finn's days off, dates and weekly things.
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
            // Obi is working on (Ask → Daily): the standing errands he keeps an eye on (Ada plans with you, part 3 slice 1).
            .sheet(isPresented: Binding(get: { model.showWorkingOn }, set: { model.showWorkingOn = $0 })) { WorkingOnSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showRoutines }, set: { model.showRoutines = $0 })) { RoutinesSheet(palette: palette) }
            .sheet(isPresented: Binding(get: { model.showTeam }, set: { model.showTeam = $0 })) { TeamSheet(palette: palette) }
            // Emergency card (Ask → More): who to call, where things are, key accounts by name; his wife reads it.
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
            // Reading (Ask → Daily, reading and learning slice 1): books, podcasts and courses with how far through.
            .sheet(isPresented: Binding(get: { model.showReading }, set: { model.showReading = $0 })) { ReadingSheet(palette: palette) }
            // Fasting (Goals' Fasting tile, Fasting rebuild slice 3): the ring, Start/End, the stages, this week, History.
            .sheet(isPresented: Binding(get: { model.showFasting }, set: { model.showFasting = $0 })) { FastingSheet(palette: palette) }
            // Habits (Goals → Open Habits ›, Habits screen slice 2): the approved Habits screen.
            .sheet(isPresented: Binding(get: { model.showHabits }, set: { model.showHabits = $0 })) { HabitsSheet(palette: palette) }
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
            .sheet(isPresented: Binding(get: { model.showMoney }, set: { model.showMoney = $0 })) { MoneySheet(palette: palette) }
            // Gifts (Ask → More): birthdays and anniversaries, gift ideas and the order-by days.
            .sheet(isPresented: Binding(get: { model.showGifts }, set: { model.showGifts = $0 })) { GiftsSheet(palette: palette) }
            // Countdowns (Ask → Life): days or sleeps to holidays, birthdays and Christmas.
            .sheet(isPresented: Binding(get: { model.showCountdowns }, set: { model.showCountdowns = $0 })) { CountdownsSheet(palette: palette) }
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
            // Speaking up (the JARVIS step, slice 1): which moments Obi says out loud, how often, Mute.
            .sheet(isPresented: Binding(get: { model.showSpeakUp }, set: { model.showSpeakUp = $0 })) {
                SpeakUpSheet(palette: palette)
            }
            // About you (rule 13, slice 1): who's who, home and work, kept in his data.
            .sheet(isPresented: Binding(get: { model.showAboutYou }, set: { model.showAboutYou = $0 })) {
                AboutYouSheet(palette: palette)
            }
            // Mail (Inbox autopilot, slice 1): connect Gmail and Outlook, label each account.
            .sheet(isPresented: Binding(get: { model.showMail }, set: { model.showMail = $0 })) {
                MailSheet(palette: palette)
            }
            // Review opens its own list, slice 1: the Empty Junk card's review (who, a tick per sender, Approve).
            .sheet(isPresented: Binding(get: { model.showJunkReview }, set: { model.showJunkReview = $0 })) {
                JunkReviewSheet(palette: palette)
            }
    }
}

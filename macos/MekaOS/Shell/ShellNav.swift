@preconcurrency import MekaKit
import Foundation

// The app shell's rules (build plan M1, App shell; Four tabs, one front door). Matches
// android/.../shell/ShellNav.kt rule for rule.
//
// Four tabs (Today · Needs you · Calendar · Ask). Everything else stays a destination but sits behind Ask: reached
// from Ask's More list, cards on Today, search and notifications, with Ask lit. The order is the order content
// moves in: the places behind Ask come after it.

enum ShellDestination: Int, CaseIterable, Identifiable {
    case today, needsYou, calendar, ask, lists, goals, review, vault
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .today: "Today"
        case .needsYou: "Needs you"
        case .calendar: "Calendar"
        case .ask: "Ask"
        case .lists: "Lists"
        case .goals: "Goals"
        case .review: "Review"
        case .vault: "Vault"
        }
    }

    /// SF Symbol for the sidebar (the outline; the lit row shows its filled variant, the Fold's drawn tab icons
    /// match the four tabs: a sun on the horizon, a circle with "!", a calendar, a speech bubble).
    var symbol: String {
        switch self {
        case .today: "sun.horizon"
        case .needsYou: "exclamationmark.circle"
        case .calendar: "calendar"
        case .ask: "bubble.left"
        case .lists: "list.bullet"
        case .goals: "target"
        case .review: "chart.bar"
        case .vault: "lock"
        }
    }
}

enum ShellLayout { case bottomBar, rail }

/// The parts of Ask's More (Fold review 2026-10-12, item 7: the 30-row list was too long to scan). Life is a grid of
/// tiles with a live line each; Daily a short list of rows; Settings one row that unfolds every setting in place.
/// Setup and Health show as one status row at the top only when something needs a look.
enum MoreGroup: Int, CaseIterable, Identifiable {
    case life, daily, settings
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .life: "Life"
        case .daily: "Daily"
        case .settings: "Settings"
        }
    }
}

/// A row or tile in Ask's More: a place behind Ask (`destination`), a sheet over the current screen (nil), Appearance,
/// which shows its choices in the row itself (`ShellNav.unfoldsInPlace`), or MEKA's AI, which only says how it is
/// (`ShellNav.opens`). Life tiles carry their `tile` (the core's live line) and an SF Symbol.
enum MoreItem: Int, CaseIterable, Identifiable {
    case lists, goals, people, dinners, bins, dateNight, school, kids, appointments, leave, gifts, trips, errands, car, emergency, facts, brief, weekAhead, news, shutdown, trainQueue, voiceNotes, lifeAdmin, review, aboutYou, work, protectedTime, notifications, appearance, voice, talk, calendars, mail, family, watch, recovery, autonomy, ai, activity, setup, health, vault, yourData, settings
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .lists: "Lists"
        case .goals: "Goals and habits"
        case .people: "People"
        case .dinners: "Dinners"
        case .bins: "Bins"
        case .dateNight: "Date night"
        case .school: "School"
        case .kids: KidsRules.shared.TITLE
        case .appointments: "Appointments"
        case .leave: "Annual leave"
        case .gifts: "Gifts"
        case .trips: "Trips"
        case .errands: "Errands"
        case .car: ParkingRules.shared.TITLE
        case .emergency: "Emergency card"
        case .facts: "What Navilife knows"
        case .brief: "Morning brief"
        case .weekAhead: "Week ahead"
        case .news: "News"
        case .shutdown: "Shut down the day"
        case .trainQueue: "Train queue"
        case .voiceNotes: VoiceNoteRules.shared.TITLE
        case .lifeAdmin: "Life admin hour"
        case .review: "Review"
        case .aboutYou: AboutYouRules.shared.TITLE
        case .work: "Work mode"
        case .protectedTime: "Protected time"
        case .notifications: "Notifications"
        case .appearance: "Appearance"
        case .voice: "Navilife's voice"
        case .talk: "Talk"
        case .calendars: "Calendars"
        case .mail: MailAccountRules.shared.TITLE
        case .family: "Family"
        case .watch: "Watch"
        case .recovery: "Recovery key"
        case .autonomy: "What Navilife may do"
        case .ai: "Navilife's AI"
        case .activity: "Activity"
        case .setup: "Setup"
        case .health: "Health"
        case .vault: "Vault"
        case .yourData: "Your data"
        case .settings: "Settings"
        }
    }

    var line: String {
        switch self {
        case .lists: "Waiting for · Someday · Decisions · Renewals"
        case .goals: "Habits, goals and fasting"
        case .people: "Promises both ways, birthdays and last contact"
        case .dinners: "Favourites, the week's dinners and their shopping"
        case .bins: "Which bins go out, the evening before"
        case .dateNight: "An evening every two weeks, kept clear"
        case .school: "The children's days off, PE and trips"
        case .kids: KidsRules.shared.ROW_LINE
        case .appointments: "Dentist, optician, haircut · a nudge when one's due"
        case .leave: "Days booked, days left and the school holidays"
        case .gifts: "Birthdays, anniversaries and gift ideas"
        case .trips: "Trips and bookings, day by day"
        case .errands: "Things to get or do when you're at a place"
        // Car and parking, slice 1: where the car is parked stays on the phone; the Mac's tile has the cars' dates.
        case .car: "The parking timer, and each car's MOT, tax, insurance and service"
        case .emergency: "Who to call and where things are"
        case .facts: "Car reg, GP, sizes · the facts Navilife answers from"
        case .brief: "Your day, who you're waiting on and headlines"
        case .weekAhead: "The coming week day by day · Sunday at 18:00"
        case .news: "Barça, AI and the headlines · topics and sources"
        case .shutdown: "Tick off, carry over and see tomorrow"
        case .trainQueue: "Links and podcasts saved for the commute"
        case .voiceNotes: VoiceNoteRules.shared.ROW_LINE
        case .lifeAdmin: "One hour a week for forms, calls and renewals"
        case .review: "Your week, looked back on"
        case .aboutYou: AboutYouRules.shared.ROW_LINE
        case .work: "Work hours and the Work switch"
        case .protectedTime: "Evenings and weekends, kept free of tasks"
        case .notifications: "Quiet hours, digests and what reaches you"
        case .appearance: "Dark, Light or Auto"
        case .voice: "How Navilife sounds in Talk, the brief and calls"
        case .talk: "⌥Space and the mic"
        case .calendars: "Connected accounts and feeds"
        case .mail: MailAccountRules.shared.ROW_LINE
        case .family: "Share the shopping list with your partner"
        case .watch: "Link your Galaxy Watch to Navilife"
        case .recovery: RecoveryKeyRules.shared.ROW_LINE
        case .autonomy: "What it does on its own · the pause switch"
        case .ai: "Whether it's on and what it costs this month"
        case .activity: "What Navilife did and why"
        case .setup: "Everything Navilife can do, and what's left to set up"
        case .health: "Is everything Navilife needs working?"
        case .vault: "Documents and when they run out; your data"
        case .yourData: "Export everything as one file"
        case .settings: "Work mode, notifications, appearance, voice and Navilife's AI"
        }
    }

    var group: MoreGroup {
        switch self {
        case .lists, .goals, .people, .dinners, .bins, .dateNight, .school, .kids, .appointments, .leave, .gifts, .trips, .errands, .car, .emergency, .facts: .life
        case .brief, .weekAhead, .news, .shutdown, .trainQueue, .voiceNotes, .lifeAdmin, .review: .daily
        case .aboutYou, .work, .protectedTime, .notifications, .appearance, .voice, .talk, .calendars, .mail, .family, .watch, .recovery, .autonomy, .ai, .activity, .setup, .health, .vault, .yourData, .settings: .settings
        }
    }

    var destination: ShellDestination? {
        switch self {
        case .lists: .lists
        case .goals: .goals
        case .review: .review
        case .vault: .vault
        default: nil
        }
    }

    /// The Life tile this item is, for its live line; nil for rows.
    var tile: LifeTile? {
        switch self {
        case .lists: .lists
        case .goals: .goals
        case .people: .people
        case .dinners: .dinners
        case .bins: .bins
        case .dateNight: .dateNight
        case .school: .school
        case .kids: .kids
        case .appointments: .appointments
        case .leave: .leave
        case .gifts: .gifts
        case .trips: .trips
        case .errands: .errands
        case .car: .car
        case .emergency: .emergency
        case .facts: .facts
        default: nil
        }
    }

    /// A Life tile's SF Symbol (the Fold draws matching outlines, LifeIcon.kt).
    var symbol: String? {
        switch self {
        case .lists: "list.bullet"
        case .goals: "target"
        case .people: "person.2"
        case .dinners: "fork.knife"
        case .bins: "trash"
        case .dateNight: "moon.stars"
        case .school: "graduationcap"
        case .kids: "tshirt"
        case .appointments: "clock"
        case .leave: "sun.horizon"
        case .gifts: "gift"
        case .trips: "suitcase"
        case .errands: "mappin.and.ellipse"
        case .car: "car"
        case .emergency: "cross.case"
        case .facts: "book"
        default: nil
        }
    }
}

enum ShellNav {
    /// Same breakpoint the Fold uses for its two panes.
    static let wideWidth: Double = 600

    /// The four tabs: the Fold's bar and rail, and the top of the Mac sidebar.
    static let tabs: [ShellDestination] = [.today, .needsYou, .calendar, .ask]

    static func layout(forWidth width: Double) -> ShellLayout { width >= wideWidth ? .rail : .bottomBar }

    /// The same four tabs in the bar and the rail.
    static func destinations(_ layout: ShellLayout) -> [ShellDestination] { tabs }

    /// The tab a destination sits behind: Ask for the places in More, nil for the tabs.
    static func parent(_ d: ShellDestination) -> ShellDestination? { tabs.contains(d) ? nil : .ask }

    /// The lit tab: itself, or Ask for a place reached from More.
    static func barSelection(_ current: ShellDestination) -> ShellDestination { parent(current) ?? current }

    /// +1 forward (content arrives from the trailing edge), -1 back, 0 for no change.
    static func direction(from: ShellDestination, to: ShellDestination) -> Int {
        max(-1, min(1, to.rawValue - from.rawValue))
    }

    /// Everything reachable from Ask's More; Calendars, Mail, Family, Watch and Recovery key only once this device is
    /// connected (they live on the server).
    static func more(connected: Bool) -> [MoreItem] {
        MoreItem.allCases.filter { connected || !serverOnly.contains($0) }
    }

    private static let serverOnly: Set<MoreItem> = [.calendars, .mail, .family, .watch, .recovery]

    /// The Life grid's tiles, in the core's order (both apps).
    static func lifeTiles() -> [MoreItem] {
        LifeTileRules.shared.ORDER.compactMap { t in MoreItem.allCases.first { $0.tile == t } }
    }

    /// Life's tiles for these lines: today's first, the rest in the usual order (Fold review 17:56, item 2).
    static func lifeTiles(_ lines: LifeLines?) -> [MoreItem] {
        guard let lines else { return lifeTiles() }
        return LifeTileRules.shared.ordered(lines: lines).compactMap { t in MoreItem.allCases.first { $0.tile == t } }
    }

    /// Life's grid without the tiles still to set up (Ask polish, slice 1); today's first.
    static func lifeLive(_ lines: LifeLines?) -> [MoreItem] {
        guard let lines else { return lifeTiles() }
        return LifeTileRules.shared.live(lines: lines).compactMap { t in MoreItem.allCases.first { $0.tile == t } }
    }

    /// The tiles under "To set up", in the usual order (none before the core's first lines arrive).
    static func lifeToSetUp(_ lines: LifeLines?) -> [MoreItem] {
        guard let lines else { return [] }
        return LifeTileRules.shared.toSetUp(lines: lines).compactMap { t in MoreItem.allCases.first { $0.tile == t } }
    }

    /// Daily's rows on Ask.
    static func daily() -> [MoreItem] { MoreItem.allCases.filter { $0.group == .daily } }

    /// The core's row for a Daily item (Review has none: it keeps its words).
    static func dailyRow(_ item: MoreItem) -> DailyRow? {
        switch item {
        case .brief: .brief
        case .weekAhead: .weekAhead
        case .news: .news
        case .shutdown: .shutdown
        case .trainQueue: .trainQueue
        case .lifeAdmin: .lifeAdmin
        default: nil
        }
    }

    /// A Daily row's line (Ask polish, item 3): the core's live one ("Tomorrow 06:40 · 5 things", "2 saved"), lit when
    /// it's ready or wants a look; else the row's fixed words.
    static func dailyLine(_ item: MoreItem, lines: DailyLines?, voiceNotes: DailyLine? = nil) -> (text: String, lit: Bool) {
        // Voice notes (slice 3): the notes live on this Mac, so their line comes from its store, not the core's lines.
        if item == .voiceNotes, let live = voiceNotes { return (live.text, live.lit) }
        if let row = dailyRow(item), let live = lines?.of(row: row) { return (live.text, live.lit) }
        return (item.line, false)
    }

    /// What Settings lists, in order.
    static func settings(connected: Bool) -> [MoreItem] {
        more(connected: connected).filter { $0.group == .settings && $0 != .settings }
    }

    /// The status row at the top of More: Health when something needs a look, else Setup while steps are left.
    static func statusItem(healthAttention: Int, setupLeft: Int) -> MoreItem? {
        if healthAttention > 0 { return .health }
        if setupLeft > 0 { return .setup }
        return nil
    }

    /// Stagger steps for Ask (the title 0, the field 1, the status row 2): each part a step after the one before
    /// began, its label leading. Life: label 3, tiles a row at a time from 4 · Daily: label 5, rows 6–12 · Settings 7.
    static let statusStep = 2

    static func moreLabelStep(_ section: Int) -> Int { 3 + 2 * section }

    static func moreRowStep(_ section: Int, _ row: Int) -> Int { moreLabelStep(section) + 1 + row }

    /// Tiles arrive a row (two) at a time.
    static func tileStep(_ index: Int) -> Int {
        Int(LifeTileRules.shared.step(first: Int32(moreRowStep(0, 0)), index: Int32(index)))
    }

    static var settingsStep: Int { moreLabelStep(2) }

    /// Every item opens something except MEKA's AI, which only says how it is.
    static func opens(_ item: MoreItem) -> Bool { item != .ai }

    /// A tile's line: the core's live one (lit when it's about today), else (before the core's first lines arrive)
    /// the tile's short prompt, never its description.
    static func tileLine(_ item: MoreItem, live: LifeTileLine?) -> (text: String, lit: Bool) {
        if let live { return (live.text, live.lit) }
        if let tile = item.tile { return (LifeTileRules.shared.prompt(tile: tile), false) }
        return (item.line, false)
    }

    /// Lists says what's due when something is ("2 need you · …"), Health and Setup their summaries once checked,
    /// else the fixed words.
    static func moreLine(_ item: MoreItem, listsDue: Int, atWork: Bool = false, health: String? = nil, setup: String? = nil) -> String {
        if item == .work { return workLine(atWork: atWork) }
        if item == .health, let health { return health }
        if item == .setup, let setup { return setup }
        guard item == .lists, listsDue > 0 else { return item.line }
        return "\(listsDue) need\(listsDue == 1 ? "s" : "") you · \(item.line)"
    }

    /// Appearance shows its three choices in the row itself; every other row opens something.
    static func unfoldsInPlace(_ item: MoreItem) -> Bool { item == .appearance }

    /// Work mode's line says where you are now ("At work · Work hours and the Work switch"), as the header did.
    static func workLine(atWork: Bool) -> String { "\(atWork ? "At work" : "Off work") · \(MoreItem.work.line)" }

    static func moreLit(_ item: MoreItem, listsDue: Int, healthAttention: Int = 0, setupLeft: Int = 0) -> Bool {
        (item == .lists && listsDue > 0) || (item == .health && healthAttention > 0) || (item == .setup && setupLeft > 0)
    }

    /// Nothing at zero, the count up to 9, then "9+".
    static func badge(_ count: Int) -> String? {
        if count <= 0 { return nil }
        return count > 9 ? "9+" : "\(count)"
    }

    static func accessibilityLabel(_ d: ShellDestination, needsYouCount: Int) -> String {
        let n = d == .needsYou ? needsYouCount : 0
        return n > 0 ? "\(d.label), \(n) waiting" : d.label
    }

    /// Placeholder line for destinations whose feature hasn't landed yet.
    static func upcomingLine(_ d: ShellDestination) -> String? {
        switch d {
        case .vault: "Your documents and when they run out; Navilife reminds you in time to renew."
        case .today, .needsYou, .calendar, .ask, .lists, .goals, .review: nil
        }
    }
}

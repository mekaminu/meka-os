package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import os.meka.android.activity.ActivityPane
import os.meka.android.calendar.EventUndoBar
import os.meka.android.calendar.rememberEventUndo
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.export.YourData
import os.meka.android.news.NewsPane
import os.meka.android.news.TickerChoice
import os.meka.android.news.rememberTickerMode
import os.meka.core.domain.TickerMode
import os.meka.android.notify.NotificationsPane
import os.meka.android.search.SearchPane
import os.meka.android.shell.MoreGroup
import os.meka.android.shell.MoreItem
import os.meka.android.shell.PlaceVia
import os.meka.android.shell.SharedMotion
import os.meka.android.designsystem.sharedPlace
import os.meka.android.designsystem.sharedTitle
import os.meka.android.shell.OpenItem
import os.meka.android.shell.ShellDestination
import os.meka.android.shell.ShellNav
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.MotionChoice
import os.meka.core.domain.MotionCheckRules
import os.meka.core.domain.MotionRules
import os.meka.core.domain.AppUpdateRules
import os.meka.android.MekaApplication
import os.meka.android.designsystem.MotionControl
import os.meka.android.designsystem.MotionPrefs
import os.meka.android.today.BriefPane
import os.meka.android.today.CalendarsPane
import os.meka.android.today.ShutdownPane
import os.meka.android.work.WorkPane
import os.meka.core.facade.MekaCore

/**
 * ASK (build plan M1, Four tabs, one front door). The fourth tab and the front door to everything that isn't a tab.
 * One field asks MEKA in your own words (Return; [AskMekaSection], V1 AI layer slice 3b) and searches as you type, the
 * best matches under it and "See all" opening Search everything (Fold review 2026-10-09 07:26, item 8). Below it
 * one status row when Setup or Health needs a look, then the Ask hub (approved 2026-11-11, `docs/design/look/AskHub.dc.html`):
 * a **Life · Daily · Settings** switch (remembered on this phone) in place of one long page. LIFE: the tiles with an
 * icon and a live line each, a page at a time (eight folded, twelve open; ‹ › and a dot a page; today's first; Lists and
 * Goals open their places with the shell's forward slide and keep Ask lit, the rest spring up as panes), with "Places to
 * set up" folded under them naming what's in it. DAILY: one card of rows. SETTINGS: four boxes that fold open (You and
 * your day open the first time; remembered), every setting in one of them. Motion: the title, field, status row, switch
 * and tab stagger in 40 ms apart (tiles a row at a time); the switch's lit pill springs across with a tick; a page turn's
 * tiles fade into place; a box unfolds on the expand spring; lines cross-fade and lit ones blend to the accent; a row's
 * or tile's label travels into the title of what it opens (and back). Reduced motion cross-fades.
 */
@Composable
fun AskRoute(
    core: MekaCore, connected: Boolean, openPlace: (ShellDestination) -> Unit, openItem: (OpenItem) -> Unit,
    /** Appearance → Play the opening: back to Today, which replays its opening. */
    playOpening: () -> Unit = {},
) {
    val lists by core.listsView.collectAsState()
    val work by core.workMode.collectAsState()
    val health by core.healthView.collectAsState()
    val setup by core.setupView.collectAsState()
    // Appearance unfolds its three choices in its own row.
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var pane by rememberSaveable { mutableStateOf<MoreItem?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // What Ask's field hands Search everything: the query typed and a task to open over the results.
    var searchSeed by rememberSaveable { mutableStateOf("") }
    var searchTask by rememberSaveable { mutableStateOf<String?>(null) }
    var searchEpoch by rememberSaveable { mutableStateOf(0) }
    val closeSearch = { showSearch = false; searchEpoch += 1 }
    val coreLifeLines by core.lifeLines.collectAsState()
    // Car and parking, slice 1: while a car is parked the Car tile says where (kept on this phone, laid over the core's
    // line); read again when Ask comes back and when a pane closes.
    val parkContext = LocalContext.current
    val carsNow by core.carsView.collectAsState()
    val timersNow by core.parkingTimersView.collectAsState()
    var parkedLook by remember { mutableStateOf(0) }
    val lifeLines = remember(coreLifeLines, carsNow, timersNow, parkedLook, pane) {
        val nowMs = System.currentTimeMillis()
        val cal = os.meka.core.domain.LocalCalendar.fixedOffset(java.util.TimeZone.getDefault().getOffset(nowMs).toLong())
        val rows = os.meka.core.domain.ParkingRules.rows(ParkingStore.spots(parkContext), carsNow, nowMs, cal)
        // Two cars: each car on its own line (where it's parked from this phone, else the core's timer or next date).
        os.meka.core.domain.ParkingRules.withCars(coreLifeLines, carsNow, timersNow, rows, nowMs)
    }
    val dailyLines by core.dailyLines.collectAsState()
    // The Bedside clock row's "Screen saver on/off": read again each time Ask comes back (he may have just set it).
    val screenSaverContext = LocalContext.current
    var screenSaver by remember { mutableStateOf<Boolean?>(null) }
    val askLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(askLifecycle) {
        askLifecycle.currentStateFlow.collect { st ->
            if (st == androidx.lifecycle.Lifecycle.State.RESUMED) {
                parkedLook += 1
                screenSaver = runCatching { os.meka.android.fold.BedsideDreamService.isMeka(screenSaverContext) }.getOrNull()
            }
        }
    }
    // Voice notes, slice 1: the Daily row's line from the notes this phone keeps (read once Ask shows).
    val voiceNotes by os.meka.android.capture.VoiceNoteStore.notes.collectAsState()
    LaunchedEffect(Unit) { os.meka.android.capture.VoiceNoteStore.load(screenSaverContext) }
    val voiceNotesLine = voiceNotes?.let { n ->
        val zone = java.time.ZoneId.systemDefault()
        os.meka.core.domain.DailyRules.voiceNotes(
            n.size, n.firstOrNull()?.let { java.time.Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate().toEpochDay() },
            java.time.LocalDate.now(zone).toEpochDay(),
        )
    }
    // The Ask hub (approved 2026-11-11, docs/design/look/AskHub.dc.html): Life · Daily · Settings, remembered on this
    // device; Life's page and Settings' open boxes too (the boxes per device, the page for this visit).
    val hubContext = LocalContext.current.applicationContext
    var tab by rememberSaveable { mutableStateOf(AskPrefs.tab(hubContext)) }
    val pickTab: (os.meka.core.domain.AskTab) -> Unit = { t -> tab = t; AskPrefs.saveTab(hubContext, t) }
    var lifePage by rememberSaveable { mutableStateOf(0) }
    var boxes by remember { mutableStateOf(os.meka.core.domain.AskHubRules.openBoxes(AskPrefs.boxes(hubContext))) }
    val openBox: (os.meka.core.domain.SettingsBox) -> Unit = { b ->
        if (b !in boxes) { boxes = boxes + b; AskPrefs.saveBoxes(hubContext, boxes) }
    }
    val toggleBox: (os.meka.core.domain.SettingsBox) -> Unit = { b ->
        boxes = os.meka.core.domain.AskHubRules.toggle(boxes, b); AskPrefs.saveBoxes(hubContext, boxes)
    }
    var ai by remember { mutableStateOf<os.meka.core.domain.AiStatusView?>(null) }
    LaunchedEffect(tab) { if (tab == os.meka.core.domain.AskTab.SETTINGS) ai = runCatching { core.aiStatus() }.getOrNull() }
    val bedsideApp = LocalContext.current.applicationContext as? os.meka.android.MekaApplication
    val open: (MoreItem) -> Unit = { item ->
        val d = item.destination
        when {
            d != null -> openPlace(d)
            item == MoreItem.SETTINGS -> pickTab(os.meka.core.domain.AskTab.SETTINGS)
            // Bedside clock slice 1: the clock takes the screen, ‹ Back returns here.
            item == MoreItem.BEDSIDE -> bedsideApp?.openBedside?.value = true
            ShellNav.opens(item) -> pane = item
        }
    }
    // Ask knows how Navilife itself works: Take me there opens the screen a help answer names, or Android's own page.
    val helpContext = LocalContext.current
    val openHelp: (os.meka.core.domain.HelpPlace) -> Unit = { p ->
        when {
            p.system -> HelpSettingsPages.open(helpContext, p)
            // Settings, Navilife's AI and Appearance: the Settings tab with their box open (Appearance unfolded too).
            os.meka.core.domain.AskHubRules.settingsTarget(p) != null -> {
                pickTab(os.meka.core.domain.AskTab.SETTINGS)
                os.meka.core.domain.AskHubRules.settingsTarget(p)?.let(openBox)
                if (p == os.meka.core.domain.HelpPlace.APPEARANCE) appearanceOpen = true
            }
            else -> MoreItem.entries.firstOrNull { it.name == p.name }?.let { open(it) }
        }
    }
    val undo = rememberEventUndo()
    // Today's health line → Open Health (Reliability first, item 3).
    val healthApp = LocalContext.current.applicationContext as? os.meka.android.MekaApplication
    val openHealth by remember(healthApp) { healthApp?.openHealth ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openHealth) {
        if (openHealth) { pane = MoreItem.HEALTH; healthApp?.openHealth?.value = false }
    }
    // Today's setup card → Open Setup (Setup checklist).
    val openSetup by remember(healthApp) { healthApp?.openSetup ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openSetup) {
        if (openSetup) { pane = MoreItem.SETUP; healthApp?.openSetup?.value = false }
    }
    // The leave heads-up (annual leave, slice 2) → the Annual leave pane.
    val openLeave by remember(healthApp) { healthApp?.openLeave ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openLeave) {
        if (openLeave) { pane = MoreItem.LEAVE; healthApp?.openLeave?.value = false }
    }
    // Sunday's week-ahead heads-up (Sunday week ahead, slice 1) → the Week ahead pane.
    val openKids by remember(healthApp) { healthApp?.openKids ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openKids) {
        if (openKids) { pane = MoreItem.KIDS; healthApp?.openKids?.value = false }
    }
    // Routines, slice 3b: a routine's "has started" notice opens Routines with its Running now card.
    val openRoutines by remember(healthApp) { healthApp?.openRoutines ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openRoutines) {
        if (openRoutines) { pane = MoreItem.ROUTINES; healthApp?.openRoutines?.value = false }
    }
    // A card heads-up (Money manager, slice 7) → the Money pane, which asks for the fingerprint first.
    val openMoney by remember(healthApp) { healthApp?.openMoney ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openMoney) {
        if (openMoney) { pane = MoreItem.MONEY; healthApp?.openMoney?.value = false }
    }
    // Goals' READING GOAL row (reading and learning, slice 4) → the Reading pane.
    val openReading by remember(healthApp) { healthApp?.openReading ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openReading) {
        if (openReading) { pane = MoreItem.READING; healthApp?.openReading?.value = false }
    }
    // Review on a Needs you clean-up card (inbox fixes, slice 2) → the Mail pane, where the plan says what goes where.
    val openMail by remember(healthApp) { healthApp?.openMail ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openMail) {
        if (openMail) { pane = MoreItem.MAIL; healthApp?.openMail?.value = false }
    }
    // An Also today tile on Today (the Today rebuild, slice 2b-2b-2) → that tile's pane (bins, trips, gifts, dinners…).
    val openPaneAsked by remember(healthApp) { healthApp?.openPane ?: kotlinx.coroutines.flow.MutableStateFlow<MoreItem?>(null) }.collectAsState()
    LaunchedEffect(openPaneAsked) {
        openPaneAsked?.let { pane = it; healthApp?.openPane?.value = null }
    }
    val openWeekAhead by remember(healthApp) { healthApp?.openWeekAhead ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openWeekAhead) {
        if (openWeekAhead) { pane = MoreItem.WEEK_AHEAD; healthApp?.openWeekAhead?.value = false }
    }
    // A call's notification (call notifications, slice 5) → the Activity pane, on Calls with that call unfolded (the
    // pane clears the request once its Calls section has it).
    val openCall by remember(healthApp) { healthApp?.openCall ?: kotlinx.coroutines.flow.MutableStateFlow<os.meka.android.activity.CallOpen?>(null) }.collectAsState()
    LaunchedEffect(openCall) {
        if (openCall != null) pane = MoreItem.ACTIVITY
    }
    // "Near Tesco" (errands by location, slice 2) → the Errands pane.
    val openErrands by remember(healthApp) { healthApp?.openErrands ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openErrands) {
        if (openErrands) { pane = MoreItem.ERRANDS; healthApp?.openErrands?.value = false }
    }
    // "Where did I park?" said to Talk (car and parking, slice 1) → the Car pane with Find my car open.
    var carFindFirst by remember { mutableStateOf(false) }
    var carFindCar by remember { mutableStateOf<String?>(null) }
    val openCar by remember(healthApp) { healthApp?.openCar ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openCar) {
        if (openCar) { carFindCar = healthApp?.openCarFind?.value; healthApp?.openCarFind?.value = null; carFindFirst = true; pane = MoreItem.CAR; healthApp?.openCar?.value = false }
    }
    // "Parked the Golf?" (car and parking, slice 3) → the Car pane, saving that car's spot (or to add the bay).
    var carParkFirst by remember { mutableStateOf<String?>(null) }
    var carPhotoFirst by remember { mutableStateOf<String?>(null) }
    val openCarFrom by remember(healthApp) { healthApp?.openCarFrom ?: kotlinx.coroutines.flow.MutableStateFlow<CarOpen?>(null) }.collectAsState()
    LaunchedEffect(openCarFrom) {
        openCarFrom?.let { o ->
            carFindFirst = false; carFindCar = null; carParkFirst = o.carId.takeIf { o.save }; carPhotoFirst = o.carId.takeIf { o.photo }; pane = MoreItem.CAR; healthApp?.openCarFrom?.value = null }
    }
    // An errand found by Search (errands by location, slice 2b) → the Errands pane.
    val foundItem by remember(healthApp) { healthApp?.openItem ?: kotlinx.coroutines.flow.MutableStateFlow<OpenItem?>(null) }.collectAsState()
    LaunchedEffect(foundItem) {
        when (foundItem?.target) {
            os.meka.core.domain.SearchTarget.ERRANDS -> { pane = MoreItem.ERRANDS; healthApp?.openItem?.value = null }
            // An appointment found by Search (appointment nudges, slice 2a) → the Appointments pane.
            os.meka.core.domain.SearchTarget.APPOINTMENTS -> { pane = MoreItem.APPOINTMENTS; healthApp?.openItem?.value = null }
            // A help entry found by Search or Ask's field (Ask knows how Navilife works, slice 2) → where it lives.
            os.meka.core.domain.SearchTarget.HELP -> {
                val id = foundItem?.id.orEmpty().removePrefix("help:")
                healthApp?.openItem?.value = null
                showSearch = false
                os.meka.core.domain.HelpRules.entry(id)?.fold?.let(openHelp)
            }
            else -> {}
        }
    }
    // Back closes whatever sprang up over Ask before it leaves the app.
    BackHandler(enabled = pane != null || showSearch) {
        when {
            showSearch -> closeSearch()
            else -> pane = null
        }
    }

    val context = LocalContext.current.applicationContext
    // "To set up" open or closed, remembered on this device (null until he taps it: open on the first run only).
    var setUpChoice by remember { mutableStateOf(AskPrefs.setUpOpen(context)) }
    // Meka 2026-10-24 14:34: the conversation lives in the field at the list's top. Scrolled far down (Health,
    // say), the field wasn't composed, so a tap on the orb found nothing listening and its stage closed: a hello or a
    // listen waiting brings the field back first (it is pinned from then on).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val talkWaiting = (healthApp?.talkNow?.collectAsState()?.value != null) || (healthApp?.talkListenNow?.collectAsState()?.value == true)
    LaunchedEffect(talkWaiting) {
        if (talkWaiting && os.meka.core.domain.StageRules.fieldHidden(listState.firstVisibleItemIndex)) listState.scrollToItem(0)
    }
    // Your team, slice 3: Ask the team… brings the field back to the top and puts the cursor in it.
    var askFocus by remember { mutableStateOf(0) }
    LaunchedEffect(askFocus) { if (askFocus > 0) listState.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
        val columns = os.meka.core.domain.LifeTileRules.columns(maxWidth.value)
        LazyColumn(
            Modifier.fillMaxSize().testTag(ASK_LIST_TAG),
            state = listState,
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "title") {
                Text("Ask", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(bottom = MekaSpace.m).appear(rememberAppearance(0)))
            }
            item(key = "field") {
                // Ask MEKA (V1 AI layer, slice 3b): one field asks in your own words (Return) and searches as you type
                // (Fold review 2026-10-09 07:26, item 8); the matches open where they live, "See all" opens Search.
                AskMekaSection(
                    core, undo,
                    openSearch = { q, task -> searchSeed = q; searchTask = task; showSearch = true },
                    modifier = Modifier.appear(rememberAppearance(1)),
                    openItem = openItem,
                    searchEpoch = searchEpoch,
                    openHelp = openHelp,
                    focusEpoch = askFocus,
                )
            }
            // Setup or Health, one row, only when something needs a look (Fold review 2026-10-12, item 7).
            val status = ShellNav.statusItem(health?.attention ?: 0, setup?.toDo ?: 0)
            if (status != null) {
                item(key = "status") {
                    MoreRow(status, ShellNav.moreLine(status, 0, health = health?.summary, setup = setup?.summary), lit = true,
                        paneOpen = false, travel = false, modifier = Modifier.padding(bottom = MekaSpace.xs).appear(rememberAppearance(ShellNav.STATUS_STEP))) {
                        pane = status
                    }
                }
            }
            // The Ask hub's switch: Life · Daily · Settings in place of one long page.
            item(key = "tabs") {
                AskTabSwitch(tab, Modifier.testTag(ASK_TABS_TAG).padding(vertical = MekaSpace.xs)
                    .appear(rememberAppearance(ShellNav.moreLabelStep(0))), onPick = pickTab)
            }
            when (tab) {
                os.meka.core.domain.AskTab.LIFE -> {
                    // LIFE: the places as tiles, a page at a time (eight on the phone, twelve on the open Fold), today's
                    // first; a row keyed by its tiles glides to its new place. Tiles still to set up fold under them.
                    val live = ShellNav.lifeLive(lifeLines)
                    val toSetUp = ShellNav.lifeToSetUp(lifeLines)
                    val welcome = os.meka.core.domain.LifeTileRules.welcome(lifeLines)
                    if (welcome != null) {
                        item(key = "life-welcome") {
                            Text(welcome, style = MekaType.caption, color = Meka.colors.textSecondary,
                                modifier = Modifier.padding(bottom = MekaSpace.xxs).animateItem().appear(rememberAppearance(ShellNav.tileStep(0))))
                        }
                    }
                    val start = os.meka.core.domain.AskHubRules.pageStart(lifePage, live.size, columns)
                    val end = os.meka.core.domain.AskHubRules.pageEnd(lifePage, live.size, columns)
                    tileRows(live.subList(start, end), columns, "tiles", lifeLines, pane, step = { ShellNav.tileStep(it, columns) }) { open(it) }
                    if (os.meka.core.domain.AskHubRules.pageLine(lifePage, live.size, columns) != null) {
                        item(key = "life-pages") {
                            LifePageTurner(lifePage, live.size, columns,
                                Modifier.animateItem().appear(rememberAppearance(ShellNav.tileStep(end - start, columns) + 1))) { lifePage = it }
                        }
                    }
                    if (toSetUp.isNotEmpty()) {
                        val setUpOpen = os.meka.core.domain.LifeTileRules.setUpOpen(setUpChoice, lifeLines)
                        item(key = "label-setup") {
                            SetUpLabel(
                                os.meka.core.domain.LifeTileRules.setUpLabel(toSetUp.size), setUpOpen,
                                Modifier.animateItem().appear(rememberAppearance(ShellNav.tileStep(end - start, columns) + 1)),
                                peek = os.meka.core.domain.AskHubRules.setUpPeek(toSetUp.map { it.label }),
                            ) {
                                setUpChoice = !setUpOpen
                                AskPrefs.saveSetUpOpen(context, !setUpOpen)
                            }
                        }
                        if (setUpOpen) tileRows(toSetUp, columns, "setup", lifeLines, pane, step = { ShellNav.tileStep(it, columns) }) { open(it) }
                    }
                }
                // DAILY: the things you run through a day or a week, as one card of rows.
                os.meka.core.domain.AskTab.DAILY -> item(key = "daily-card") {
                    RowsCard(Modifier.animateItem().appear(rememberAppearance(ShellNav.moreRowStep(0, 0)))) {
                        ShellNav.daily().forEachIndexed { i, item ->
                            if (i > 0) CardHairline()
                            // Ask polish, item 3: each row says what it holds now; lit when it's ready or wants a look.
                            val line = ShellNav.dailyLine(item, dailyLines, screenSaver, voiceNotesLine)
                            MoreRow(item, line.text, line.lit, pane == item) { open(item) }
                        }
                    }
                }
                // SETTINGS: four boxes that fold open (You and your day, Obi, Phone watch and look, Privacy and data).
                os.meka.core.domain.AskTab.SETTINGS -> settingsBoxes(connected, boxes, toggleBox, ShellNav.moreRowStep(0, 0)) { item ->
                    SettingRow(item, core, ai, pane, appearanceOpen, { appearanceOpen = !appearanceOpen },
                        openTopics = { pane = MoreItem.NEWS }, playOpening = playOpening, open = { open(it) })
                }
            }
        }
        MekaPane(visible = pane == MoreItem.BRIEF) {
            // Slice 7: the read ran to its end: the pane drops and Ask's orb listens for 8 s.
            BriefPane(core, onClose = { pane = null }, onTalkAfter = { pane = null; healthApp?.talkAfterBrief?.value = os.meka.android.shell.ShellDestination.ASK })
        }
        MekaPane(visible = pane == MoreItem.NEWS) { NewsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.SHUTDOWN) { ShutdownPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.WEEK_AHEAD) {
            // Listen on Sunday's heads-up (slice 2): the pane reads the week aloud as it opens.
            val listenWeek by remember(healthApp) { healthApp?.listenWeekAhead ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
            WeekAheadPane(core, onClose = { pane = null }, listenNow = listenWeek, onListened = { healthApp?.listenWeekAhead?.value = false })
        }
        MekaPane(visible = pane == MoreItem.SCHOOL) { SchoolPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.MONEY) { MoneyPane(core, onClose = { pane = null }, onNeedsYou = { pane = null; openPlace(ShellDestination.NEEDS_YOU) }) }
        MekaPane(visible = pane == MoreItem.KIDS) { KidsPane(core, onClose = { pane = null }, onAboutYou = { pane = MoreItem.ABOUT_YOU }) }
        MekaPane(visible = pane == MoreItem.DINNERS) { MealsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.DATE_NIGHT) { DateNightPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.LIFE_ADMIN) { LifeAdminPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.EMERGENCY) { EmergencyPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.TRAIN_QUEUE) { TrainQueuePane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.READING) { ReadingPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.VOICE_NOTES) { VoiceNotesPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.ERRANDS) { ErrandsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.CAR) {
            CarPane(
                core, onClose = { pane = null; carFindFirst = false; carFindCar = null; carParkFirst = null; carPhotoFirst = null },
                onDates = { pane = null; carFindFirst = false; carFindCar = null; carParkFirst = null; carPhotoFirst = null; open(MoreItem.LISTS) },
                findFirst = carFindFirst, findCar = carFindCar, parkFirst = carParkFirst, photoFirst = carPhotoFirst,
            )
        }
        MekaPane(visible = pane == MoreItem.APPOINTMENTS) { AppointmentsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.BINS) { BinsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.GIFTS) { GiftsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.COUNTDOWNS) { CountdownsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.PEOPLE) { PeoplePane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.TRIPS) { TripsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.FACTS) { FactsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.LEAVE) { LeavePane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.ABOUT_YOU) { AboutYouPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.WORK) { WorkPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.PROTECTED) { ProtectedTimePane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.NOTIFICATIONS) { NotificationsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.SPEAK_UP) { SpeakUpPane(core, onClose = { pane = null }) }
        // Ada plans with you, part 3 slice 1: what Obi keeps an eye on; a card's button opens the screen that holds it.
        MekaPane(visible = pane == MoreItem.WORKING_ON) {
            WorkingOnPane(core, onClose = { pane = null }, onOpen = { place -> pane = null; openHelp(place) })
        }
        // Routines, slice 1: his routines with their switches and the starters.
        MekaPane(visible = pane == MoreItem.ROUTINES) { RoutinesPane(core, onClose = { pane = null }) }
        // Your team, slice 1: each helper's day and what waits on him; Review opens Needs you or Money.
        MekaPane(visible = pane == MoreItem.TEAM) {
            TeamPane(core, onClose = { pane = null }, onReview = { target ->
                // "Team, simpler": a helper's Open goes to its place (Mail, Activity's calls, Money's Trading tab).
                when (target) {
                    os.meka.core.domain.TeamTarget.MONEY -> pane = MoreItem.MONEY
                    os.meka.core.domain.TeamTarget.TRADING -> {
                        healthApp?.openMoneyTab?.value = os.meka.core.domain.MoneyTab.TRADING
                        pane = MoreItem.MONEY
                    }
                    os.meka.core.domain.TeamTarget.MAIL -> pane = MoreItem.MAIL
                    os.meka.core.domain.TeamTarget.CALLS -> pane = MoreItem.ACTIVITY
                    os.meka.core.domain.TeamTarget.ASK -> { pane = null; askFocus += 1 }
                    os.meka.core.domain.TeamTarget.NEEDS_YOU -> { pane = null; openPlace(ShellDestination.NEEDS_YOU) }
                }
            }, onAsk = { listen ->
                // Slice 3: Ask the team… is Obi in Ask's field (or listening), the team in what he sees.
                pane = null
                if (listen) healthApp?.talkListenNow?.value = true else askFocus += 1
            })
        }
        MekaPane(visible = pane == MoreItem.AUTONOMY) { AutonomyPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.ACTIVITY) { ActivityPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.YOUR_DATA) { YourData(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.CALENDARS) { CalendarsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.MAIL) { os.meka.android.mail.MailPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.FAMILY) { FamilyPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.WATCH) { WatchPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.RECOVERY) { RecoveryKeyPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.VOICE) { VoicePane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.TALK) { TalkPane(onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.HEALTH) {
            HealthPane(core, onClose = { pane = null }, openPane = { f ->
                pane = when (f) {
                    os.meka.core.domain.HealthFix.CALENDARS -> MoreItem.CALENDARS
                    os.meka.core.domain.HealthFix.WORK -> MoreItem.WORK
                    else -> pane
                }
            }, openPlace = { p ->
                pane = when (p) {
                    os.meka.core.domain.HealthPlace.VOICE -> MoreItem.VOICE
                    os.meka.core.domain.HealthPlace.CALENDARS -> MoreItem.CALENDARS
                    os.meka.core.domain.HealthPlace.MAIL -> MoreItem.MAIL
                    os.meka.core.domain.HealthPlace.MONEY -> MoreItem.MONEY
                    os.meka.core.domain.HealthPlace.ABOUT_YOU -> MoreItem.ABOUT_YOU
                    os.meka.core.domain.HealthPlace.WORK -> MoreItem.WORK
                    os.meka.core.domain.HealthPlace.NOTIFICATIONS -> MoreItem.NOTIFICATIONS
                    os.meka.core.domain.HealthPlace.SYSTEM_BATTERY, os.meka.core.domain.HealthPlace.SYSTEM_LISTENER -> pane
                }
            })
        }
        MekaPane(visible = pane == MoreItem.SETUP) {
            SetupPane(core, onClose = { pane = null }, openPane = { f ->
                pane = when (f) {
                    os.meka.core.domain.SetupFix.CALENDARS -> MoreItem.CALENDARS
                    os.meka.core.domain.SetupFix.WORK -> MoreItem.WORK
                    os.meka.core.domain.SetupFix.VOICE -> MoreItem.VOICE
                    os.meka.core.domain.SetupFix.RECOVERY -> MoreItem.RECOVERY
                    os.meka.core.domain.SetupFix.ABOUT_YOU -> MoreItem.ABOUT_YOU
                    os.meka.core.domain.SetupFix.MAIL -> MoreItem.MAIL
                    else -> pane
                }
            })
        }
        MekaPane(visible = showSearch) {
            SearchPane(core, onClose = closeSearch, openItem = { item -> showSearch = false; openItem(item) },
                initialQuery = searchSeed, initialTask = searchTask)
        }
        // An Ask card's undo bar ("Added “Milk” · Undo") rises at the foot of Ask.
        EventUndoBar(undo, Modifier.align(Alignment.BottomCenter))
    }
}

/** A small section label over a part of More (and the Settings pane's groups). */
@Composable
internal fun SectionLabel(label: String, first: Boolean, step: Int) {
    Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
        modifier = Modifier
            .padding(top = if (first) 0.dp else MekaSpace.m, bottom = MekaSpace.xxs)
            .semantics { heading() }
            .appear(rememberAppearance(step)))
}

/**
 * A row in More or the Settings pane. Its label travels into what it opens ([travel]; off for the status row, whose
 * pane's title already travels from the Settings pane's row). MEKA's AI opens nothing, so it has no chevron.
 */
@Composable
internal fun MoreRow(
    item: MoreItem, line: String, lit: Boolean, paneOpen: Boolean, modifier: Modifier = Modifier, travel: Boolean = true,
    onClick: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(
        if (lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "more-line",
    )
    val opens = ShellNav.opens(item)
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .then(if (opens) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Places and settings you open, not things you act on: the regular body weight (type weight, 2026-10-06).
            // The label travels into what it opens: a place's title across the shell's slide, a pane's title as it
            // springs up (it steps aside while the pane has it).
            val d = item.destination
            val move = when {
                !travel -> Modifier
                d != null -> Modifier.sharedPlace(SharedMotion.placeKey(d, PlaceVia.MORE))
                else -> Modifier.sharedTitle(SharedMotion.paneKey(item), visible = !paneOpen)
            }
            Text(item.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = move)
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "more-row-line",
            ) { l -> Text(os.meka.core.domain.LoneWordGlue.kept(l), style = MekaType.caption, color = lineColor, maxLines = 2) }
        }
        if (opens) Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.padding(start = MekaSpace.xs))
    }
}

/** Ask's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val ASK_LIST_TAG = "ask-list"

/** The box round a Life tile's icon, so the quality gate's shots can check it sits on the name's first line. */
internal const val TILE_ICON_TAG = "life-tile-icon"

/**
 * A Life tile (Fold review 2026-10-12, item 7): its icon, its name and its live line ("Black bin out tonight", lit in
 * the accent when it's about today). Half the width; the name travels into the pane it opens. The line cross-fades as
 * it changes and its colour blends. Reduced motion: cross-fades only.
 */
@Composable
private fun LifeTileCard(item: MoreItem, line: os.meka.core.domain.LifeTileLine, paneOpen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val lineColor by animateColorAsState(
        if (line.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "tile-line",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button) { haptics.tick(); onClick() }
            .semantics { contentDescription = "${item.label}, ${line.text}" }
            // Ask polish, slice 1: compact — the icon beside the name, about 30% shorter than before (104 dp).
            .heightIn(min = 72.dp)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        val d = item.destination
        val move = if (d != null) Modifier.sharedPlace(SharedMotion.placeKey(d, PlaceVia.MORE))
        else Modifier.sharedTitle(SharedMotion.paneKey(item), visible = !paneOpen)
        // The quality gate's Ask re-shot (2026-10-22): a name on two lines ("Goals and / habits") keeps its icon on the
        // first line, not centred between the two; the icon sits in a box one line of the name tall.
        // The hub's colour tint (AskHub.dc.html): the icon in a small rounded box, warm with a brass glyph when the tile
        // is about today, quiet grey otherwise; both colours blend as the line changes.
        val iconLit = os.meka.core.domain.LifeTileRules.iconLit(line)
        val boxFill by animateColorAsState(
            if (iconLit) Meka.colors.accent.copy(alpha = os.meka.core.domain.LifeTileRules.ICON_BOX_LIT_TINT).compositeOver(Meka.colors.surface)
            else Meka.colors.surfaceRaised,
            MekaMotion.appear(reduced), label = "tile-icon-box",
        )
        val glyph by animateColorAsState(if (iconLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "tile-icon")
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            val lineDp = with(androidx.compose.ui.platform.LocalDensity.current) { MekaType.itemMeta.lineHeight.toDp() }
            item.tile?.let {
                Box(
                    Modifier.size(os.meka.core.domain.LifeTileRules.ICON_BOX.dp)
                        .clip(RoundedCornerShape(os.meka.core.domain.LifeTileRules.ICON_BOX_RADIUS.dp))
                        .background(boxFill).testTag(TILE_ICON_TAG),
                    contentAlignment = Alignment.Center,
                ) { LifeIcon(it, glyph, size = os.meka.core.domain.LifeTileRules.ICON_GLYPH.dp) }
            }
            val inset = if (item.tile != null) os.meka.core.domain.LifeTileRules.nameInset(lineDp.value).dp else 0.dp
            Text(item.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = os.meka.core.domain.LifeTileRules.NAME_LINES,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = move.padding(top = inset))
        }
        AnimatedContent(
            targetState = line.text,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "tile-text",
        ) { t ->
            // Two lines at most on every tile, so the rows keep one height.
            Text(t, style = MekaType.caption, color = lineColor, maxLines = os.meka.core.domain.LifeTileRules.MAX_LINES,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * Appearance (Today clarity, slice 2: the theme moved here from Today's header). The row unfolds Dark · Light · Auto
 * in place; the chosen chip's colour blends across with a tick haptic and every colour on screen blends with it
 * (`themeBlend`). Motion (motion pass 2): Expressive · Subtle · Off, the line under it cross-fades. Reduced motion:
 * the chips appear at once and colours cross-fade.
 */
@Composable
internal fun AppearanceRow(
    open: Boolean, modifier: Modifier, openTopics: () -> Unit, playOpening: () -> Unit, toggle: () -> Unit,
    /** Calmer Today, slice 1: Done tasks on Today (Folded · Hidden · Shown), synced; null: the choice isn't shown. */
    core: os.meka.core.facade.MekaCore? = null,
) {
    val theme = Meka.theme
    val context = LocalContext.current.applicationContext
    val tickerMode = rememberTickerMode()
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(Meka.reducedMotion))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = toggle)
                .semantics { stateDescription = "${theme.choice.label}, ${if (open) "expanded" else "collapsed"}" }
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(MoreItem.APPEARANCE.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                Text("${theme.choice.label} · ${MoreItem.APPEARANCE.line}", style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.appear(Meka.reducedMotion), label = "appearance-chevron")
            Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(start = MekaSpace.xs).rotate(turn))
        }
        if (open) {
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                ThemeChoice.entries.forEach { c ->
                    Chip(c.label, theme.choice == c) { haptics.tick(); theme.set(c) }
                }
            }
            // Motion (motion pass 2): MEKA's own motion on this phone, whatever the phone's animator scale says.
            val motion = Meka.motion
            Text("Motion", style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                MotionChoice.entries.forEach { m ->
                    // Nothing chosen shows Expressive lit: it is what plays (Meka, 2026-10-08).
                    Chip(m.label, MotionRules.lit(motion.stored) == m) { haptics.tick(); motion.set(m) }
                }
            }
            AnimatedContent(
                targetState = motion.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "motion-line", modifier = Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
            ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
            MotionCheckSection(motion, playOpening)
            if (core != null) DoneTodayChoiceSection(core)
            // News ticker (news ticker, slice 2): how the strip under Today's header moves, on this device; Topics
            // opens News (the topics and their sources).
            Text("News ticker", style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                TickerMode.entries.forEach { m ->
                    Chip(m.label, tickerMode == m) { haptics.tick(); TickerChoice.set(context, m) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = tickerMode.line,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "ticker-line", modifier = Modifier.weight(1f),
                ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
                Text("Topics ›", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = openTopics)
                        // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                        .minTouch().padding(horizontal = MekaSpace.xxs))
            }
            // How the Fold reports it is held (bedside clock slice 1), live, so the bedside clock's trigger can be seen.
            os.meka.android.fold.FoldPostureLines(Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.xxs))
            // The name and tagline (the rename to Navilife, 2026-10-13), over the build line.
            Text(AppUpdateRules.TAGLINE, style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.m, end = MekaSpace.m))
            // The build on this phone (Meka, 2026-10-08), so we can tell whether it has the latest motion work.
            val app = context as? MekaApplication
            if (app != null) {
                val latest by app.updater.latestCode.collectAsState()
                LaunchedEffect(Unit) { runCatching { app.updater.check() } }
                AnimatedContent(
                    targetState = AppUpdateRules.versionLine(app.updater.installedName, app.updater.installedCode, latest),
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "build-line", modifier = Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.xs),
                ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
        }
    }
}

/**
 * Motion check (Meka, 2026-10-08: "the animation is something I have not seen work"): what MEKA sees on this phone
 * (its own Motion choice, the phone's animator scale, power saving) and the result; when animations are off the
 * result is lit and a tap turns MEKA's own Expressive motion on. "Play the opening" goes back to Today and replays
 * its opening. The values are read each time Appearance unfolds; the result cross-fades as the choice changes.
 */
@Composable
private fun MotionCheckSection(motion: MotionControl, playOpening: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scale = remember { MotionPrefs.animatorScale(context) }
    val saving = remember { MotionPrefs.powerSave(context) }
    val check = MotionCheckRules.phone(motion.stored, scale, saving)
    Text("Motion check", style = MekaType.caption, color = Meka.colors.textSecondary,
        modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
    check.rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.m)) {
            Text(row.label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
            Text(row.value, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
    }
    val fix = check.fix
    AnimatedContent(
        targetState = check.result,
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "motion-check", modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    ) { line ->
        Text(
            line, style = MekaType.caption,
            color = if (fix != null) Meka.colors.accent else Meka.colors.textPrimary,
            modifier = if (fix != null) Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button) { haptics.tick(); motion.set(fix) }.minTouch() else Modifier,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(MotionCheckRules.PLAY_OPENING_LINE, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
        Text(MotionCheckRules.PLAY_OPENING, style = MekaType.caption, color = Meka.colors.onAccent,
            // Works by hand (quality gate (3)): the touch is 48 dp tall round the pill, the pill drawn as before.
            modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button) { haptics.light(); playOpening() }
                .minTouch().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}

/**
 * Calmer Today, slice 1 (Meka 2026-10-25 15:05): how Today shows the day's done tasks, Folded (the default) · Hidden ·
 * Shown, synced with the Mac. The chosen chip lights at once (tick haptic) while the core's write follows; the line
 * under it cross-fades.
 */
@Composable
private fun DoneTodayChoiceSection(core: os.meka.core.facade.MekaCore) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val stored by core.doneTodayChoice.collectAsState()
    var picked by remember { mutableStateOf<os.meka.core.domain.DoneShow?>(null) }
    LaunchedEffect(stored.show) { if (picked == stored.show) picked = null }
    val show = picked ?: stored.show
    Text(os.meka.core.domain.DoneTodayRules.HEADING, style = MekaType.caption, color = Meka.colors.textSecondary,
        modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
    Row(
        Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        os.meka.core.domain.DoneShow.entries.forEach { c ->
            Chip(c.label, show == c) {
                haptics.tick()
                picked = c
                scope.launch { runCatching { core.setDoneTodayShow(c.code) } }
            }
        }
    }
    AnimatedContent(
        targetState = show.line,
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "done-choice-line", modifier = Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
    ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
}

/** A choice chip: the chosen one's colour blends across (`themeBlend`). */
@Composable
private fun Chip(label: String, chosen: Boolean, choose: () -> Unit) {
    val bg by animateColorAsState(
        if (chosen) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.themeBlend(Meka.reducedMotion), label = "chip-bg",
    )
    val fg by animateColorAsState(
        if (chosen) Meka.colors.onAccent else Meka.colors.textPrimary, MekaMotion.themeBlend(Meka.reducedMotion), label = "chip-fg",
    )
    Text(
        label, style = MekaType.caption, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.RadioButton) { if (!chosen) choose() }
            .semantics { selected = chosen }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** Life tiles in rows of [columns]; each row is keyed by its tiles so it glides when the order changes. */
private fun LazyListScope.tileRows(
    tiles: List<MoreItem>, columns: Int, prefix: String, lines: os.meka.core.domain.LifeLines, pane: MoreItem?,
    step: (Int) -> Int, open: (MoreItem) -> Unit,
) {
    tiles.chunked(columns).forEachIndexed { r, row ->
        item(key = "$prefix-" + row.joinToString("-") { it.name }) {
            Row(
                Modifier.fillMaxWidth().animateItem().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                row.forEachIndexed { c, tile ->
                    LifeTileCard(
                        tile, ShellNav.tileLine(tile, tile.tile?.let { lines.of(it) }), pane == tile,
                        Modifier.weight(1f).fillMaxHeight().appear(rememberAppearance(step(r * columns + c))),
                    ) { open(tile) }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * "Places to set up · 9" (Ask polish, slice 1; named so since walk-through 2026-11-08 A8): a small label that opens and closes the tiles still waiting to be set up; its
 * chevron turns on the expand spring (reduced motion: at once). TalkBack reads it as a button with its state.
 */
@Composable
private fun SetUpLabel(text: String, open: Boolean, modifier: Modifier, peek: String? = null, toggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.expand(reduced), label = "setup-chevron")
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = MekaSpace.xs)
            .clip(RoundedCornerShape(MekaRadius.s))
            .clickable(role = Role.Button) { haptics.tick(); toggle() }
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Open" else "Closed" }
            .minTouch()
            .padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.semantics { heading() })
        Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.rotate(if (reduced) (if (open) 90f else 0f) else turn))
        // The Ask hub: folded, the places' names follow ("Bins, Gifts, Annual leave").
        if (!open && !peek.isNullOrEmpty()) {
            Text(peek, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** Ask's per-device choices (Ask polish, slice 1): whether "To set up" is open; null until he first taps it. */
internal object AskPrefs {
    private const val PREFS = "meka.ask"
    private const val SET_UP_OPEN = "setUpOpen"
    private const val TAB = "tab"
    private const val BOXES = "settingsBoxes"

    /** The Ask hub's tab last picked on this device (Life the first time). */
    fun tab(context: android.content.Context): os.meka.core.domain.AskTab =
        os.meka.core.domain.AskTab.of(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(TAB, null))

    fun saveTab(context: android.content.Context, tab: os.meka.core.domain.AskTab) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(TAB, tab.name).apply()
    }

    /** Settings' open boxes on this device, as stored (null until he first folds or unfolds one). */
    fun boxes(context: android.content.Context): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(BOXES, null)

    fun saveBoxes(context: android.content.Context, open: Set<os.meka.core.domain.SettingsBox>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(BOXES, os.meka.core.domain.AskHubRules.stored(open)).apply()
    }

    fun setUpOpen(context: android.content.Context): Boolean? {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        return if (p.contains(SET_UP_OPEN)) p.getBoolean(SET_UP_OPEN, false) else null
    }

    fun saveSetUpOpen(context: android.content.Context, open: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(SET_UP_OPEN, open).apply()
    }
}

/** Android's own settings pages a help answer can open (Fold only); the Settings app itself if a page won't open. */
internal object HelpSettingsPages {
    fun open(context: android.content.Context, place: os.meka.core.domain.HelpPlace) {
        val pkg = context.packageName
        val intent = when (place) {
            os.meka.core.domain.HelpPlace.SYSTEM_SCREEN_SAVER -> android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)
            os.meka.core.domain.HelpPlace.SYSTEM_DEFAULT_APPS -> android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            os.meka.core.domain.HelpPlace.SYSTEM_APP_NOTIFICATIONS -> android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
            else -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
        }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

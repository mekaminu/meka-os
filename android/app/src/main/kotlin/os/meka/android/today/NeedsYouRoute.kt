package os.meka.android.today

import os.meka.android.designsystem.ContainerOrigins
import os.meka.android.designsystem.LocalContainerOrigins
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.BreathingRing
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.footFade
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaSharedLayout
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.work.AfterWorkCard
import os.meka.android.work.AfterWorkHost
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import os.meka.android.calendar.EventUndoBar
import os.meka.android.calendar.rememberEventUndo
import os.meka.core.domain.DecisionCard
import os.meka.core.domain.RequestCard
import os.meka.core.domain.SchoolCover
import os.meka.core.domain.TriageCard
import os.meka.core.domain.CardFinish
import os.meka.core.domain.ChatFinishTap
import os.meka.core.domain.ChatShadeRules
import os.meka.core.domain.TriageChat
import os.meka.core.domain.InstantTapRules
import os.meka.core.domain.ChatSummaryRules
import os.meka.core.domain.TriageChatRules
import os.meka.core.domain.TriageReplyRules
import os.meka.android.work.ChatSummarise
import os.meka.android.work.LiveReplies
import os.meka.core.domain.DecisionEffect
import os.meka.core.domain.BoardFeedRules
import os.meka.core.domain.DecisionMove
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.domain.NeedsYouMeanwhileRules
import os.meka.core.domain.HabitItem
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.Task
import os.meka.core.domain.NeedsYouStack
import os.meka.core.domain.CommandCentreRules
import os.meka.android.calendar.EventUndo
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import os.meka.core.facade.MekaCore
import androidx.compose.runtime.LaunchedEffect
import os.meka.android.MekaApplication
import androidx.compose.ui.platform.LocalContext

/**
 * NEEDS YOU: a stack of decisions (four tabs, slice 2): conflicts, overdue, due today but unscheduled, then "From your
 * lists" when chases, reviews or renewals are due. Right = yes/do, left = later, up = open, each card with its why
 * ([DecisionStackView]). Approvals join the stack in V1. Same layout rules as Today: two panes when wide, the detail
 * springs up over the stack when narrow.
 */
@Composable
fun NeedsYouRoute(core: MekaCore, openLists: () -> Unit = {}) {
    val today by core.today.collectAsState()
    val stack by core.needsYouStack.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val undo = rememberEventUndo()
    val actions = todayActions(core, scope, { selectedId }, { selectedId = it }, undo)
    val selected = today.needsYou.map { it.task }.firstOrNull { it.id == selectedId }
    var showAfterWork by rememberSaveable { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as MekaApplication
    val openAfterWork by app.openAfterWork.collectAsState()
    LaunchedEffect(openAfterWork) {
        if (openAfterWork) { showAfterWork = true; app.openAfterWork.value = false }
    }
    val moves = rememberDecisionMoves(core, undo, openTask = { selectedId = it }, openLists = openLists)

    // Where the top card sits, so Open grows the task's detail out of it on the closed Fold (container transform).
    val origins = remember { ContainerOrigins() }
    MekaSharedLayout(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalContainerOrigins provides origins) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
            val twoPane = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { m ->
                NeedsYouColumn(core, stack, moves, m, openAfterWork = { showAfterWork = true })
            }
            // Opening the Fold grows the detail out beside the stack; closed, the detail springs up over it.
            TwoPaneMorph(
                twoPane,
                list = list,
                detail = { m -> DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, m) },
            )
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null && !twoPane, origin = { origins[shown?.id] }) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
            MekaPane(visible = showAfterWork) { AfterWorkHost(onClose = { showAfterWork = false }) }
            RequestChangePane(core, moves)
            ArchivedAllHost(core, moves)
            JunkReviewHost(core, moves)
            EventUndoBar(undo, Modifier.align(Alignment.BottomCenter))
        }
      }
    }
}


/** The stack's moves, and the cards set aside with "Later" on this screen (nothing is written; back of the stack). */
internal class DecisionMoves(
    val onMove: (DecisionCard, DecisionMove) -> Unit,
    private val aside: MutableState<List<String>>,
    /** Opens Lists (a Waiting on or renewal row under "Nothing needs you"). */
    val openLists: () -> Unit = {},
    /** Add · Change · Not a task on a request card (V1, requests slice 4). */
    val onRequest: (RequestCard, RequestChoice) -> Unit = { _, _ -> },
    /** The task Change just made, open over the screen ([RequestChangePane]); null when none. */
    val changed: MutableState<RequestChange?> = mutableStateOf(null),
    /**
     * Send · Open chat · Edit · Not now · Seen on a triaged message (V1, messages slice 3). [single]: the card is its
     * chat's only one, so Send and Seen also clear its notification from the shade (messages grouped per chat, slice 2b).
     */
    val onTriage: (TriageCard, TriageChoice, Boolean) -> Unit = { _, _, _ -> },
    /**
     * Done, Mark read or Open … to mark read on a chat's card (messages grouped per chat, slices 1–2): every message in
     * it seen on both devices, the notification's own Mark as read on Meka's tap, then its notifications cleared.
     */
    val onTriageChat: (TriageChat, ChatFinishTap) -> Unit = { _, _ -> },
    /** "Clear this chat from the shade as it comes" switched on a chat's card (slice 2b). */
    val onChatArrival: (TriageChat, Boolean) -> Unit = { _, _ -> },
    /** The chat read to its last message under Show all (slice 2b): its notifications leave the shade, nothing else. */
    val onChatReadToEnd: (TriageChat) -> Unit = {},
    /** "Send all 3": each easy reply's draft, as its card shows it, on Meka's one tap. */
    val onSendAll: (List<TriageCard>) -> Unit = {},
    /** The undo bar, for the group digest's Caught up and group switches (slice 4). */
    val undoLine: (String, (suspend () -> Unit)?) -> Unit = { _, _ -> },
    /** I'll work from home · Covered on a school day off's cover question (school rhythm, slice 1). */
    val onSchoolCover: (SchoolCover, Boolean) -> Unit = { _, _ -> },
    /** Opens a task (a reminder under Coming up, Fold review 2026-10-12 17:56 item 3). */
    val openTask: (String) -> Unit = {},
    /** "Archived for you" shows which emails, slice 3: All tidied mail open over the screen ([ArchivedAllHost]). */
    val archivedAll: MutableState<Boolean> = mutableStateOf(false),
    /** Review opens its own list, slice 1: the Empty Junk card whose review is open over the screen ([JunkReviewHost]). */
    val junkReview: MutableState<String?> = mutableStateOf(null),
    /** Week board item (5) part 3: To a day… on an overdue or due-today card; Undo on the bar. */
    val onToDay: (DecisionCard, Long) -> Unit = { _, _ -> },
    /**
     * Taps feel instant (Meka 2026-10-25): the message and card ids decided on this screen, hidden in the next frame
     * while the core's write follows ([InstantTapRules]); a failure brings them back with a line saying why.
     */
    val decided: MutableState<Set<String>> = mutableStateOf(emptySet()),
) {
    val setAside: List<String> get() = aside.value
    val hidden: Set<String> get() = decided.value
    fun hide(ids: Collection<String>) { decided.value = InstantTapRules.hide(decided.value, ids) }
    fun restore(ids: Collection<String>) { decided.value = InstantTapRules.restore(decided.value, ids) }
    /** The views changed: forget the decided ids they no longer hold (their writes landed). */
    fun settle(present: Collection<String>) {
        val next = InstantTapRules.settle(decided.value, present)
        if (next != decided.value) decided.value = next
    }
}

/**
 * What each move on a card does, shared by the Needs you tab and the command centre on the open Fold: Done and
 * Tomorrow go through the core with an undo; Open and Choose open the task ([openTask]); Go through opens Lists; Later
 * sets a card aside on this screen.
 */
@Composable
internal fun rememberDecisionMoves(core: MekaCore, undo: EventUndo, openTask: (String?) -> Unit, openLists: () -> Unit): DecisionMoves {
    val scope = rememberCoroutineScope()
    val aside = remember { mutableStateOf(listOf<String>()) }
    val changed = remember { mutableStateOf<RequestChange?>(null) }
    val junkReview = remember { mutableStateOf<String?>(null) }
    val decided = remember { mutableStateOf(emptySet<String>()) }
    fun hide(ids: Collection<String>) { decided.value = InstantTapRules.hide(decided.value, ids) }
    fun restore(ids: Collection<String>) { decided.value = InstantTapRules.restore(decided.value, ids) }
    val latestOpenTask by rememberUpdatedState(openTask)
    val latestOpenLists by rememberUpdatedState(openLists)
    val context = LocalContext.current.applicationContext
    return remember(core, undo, scope) {
        DecisionMoves({ card, move ->
            when (val effect = card.effect(move)) {
                DecisionEffect.COMPLETE_TASK, DecisionEffect.SNOOZE_TASK -> {
                    val id = card.taskId
                    // Taps feel instant: the card leaves in the next frame; the write follows.
                    if (id != null) hide(listOf(card.id))
                    if (id != null) scope.launch {
                        val done = runCatching { core.decide(id, effect) }.getOrElse { e ->
                            restore(listOf(card.id))
                            undo.show(InstantTapRules.failedLine(card.yesLabel, e.message), null)
                            return@launch
                        }
                        if (done != null) undo.show(NeedsYouStackRules.message(card, move)) { restore(listOf(card.id)); core.undoDecision(done) }
                    }
                }
                DecisionEffect.OPEN_TASK -> latestOpenTask(card.taskId)
                DecisionEffect.OPEN_LISTS -> latestOpenLists()
                // Top up (the call assistant's credit): the billing page in the browser; MEKA never pays anything.
                DecisionEffect.OPEN_LINK -> card.link?.let { link ->
                    val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(view) }
                }
                // Inbox fixes, slice 2: Approve on an account's clean-up (synced; the server moves it from its next
                // check); Undo withdraws it while nothing has moved.
                // Taps feel instant (4b, Meka 2026-10-25 01:50): the card leaves at once and never comes back while the
                // write catches up; a failure brings it back with the reason.
                DecisionEffect.APPROVE_BACKLOG -> {
                    hide(listOf(card.id))
                    scope.launch {
                        val r = runCatching { core.approveMailBacklog(card.id) }.getOrElse { e ->
                            restore(listOf(card.id))
                            undo.show(InstantTapRules.failedLine(card.yesLabel, e.message), null)
                            return@launch
                        }
                        // Approve must land (2026-10-25): nothing written → the card comes back with the reason.
                        if (r.refused) {
                            restore(listOf(card.id))
                            undo.show(r.line, null)
                            return@launch
                        }
                        undo.show(r.line) { restore(listOf(card.id)); core.unapproveMailBacklog(card.id); Unit }
                    }
                }
                // Clean-ups (2026-10-19): Allow or Not this one on a new phone or Mac asking to join; the server answers,
                // the card leaves on every device as the ask's state syncs. Nothing to undo (revoke from the server).
                DecisionEffect.ALLOW_DEVICE, DecisionEffect.REFUSE_DEVICE -> scope.launch {
                    val line = core.answerDeviceJoin(card.id, effect == DecisionEffect.ALLOW_DEVICE)
                    undo.show(line, null)
                }
                // Review: Ask with Mail up, where the plan lists what goes where.
                DecisionEffect.OPEN_MAIL -> (context as? os.meka.android.MekaApplication)?.let { app ->
                    app.openMail.value = true
                    app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
                }
                // Review opens its own list, slice 1: the card's own sheet (Empty Junk: who, ticks, Approve).
                DecisionEffect.OPEN_REVIEW -> { junkReview.value = card.id }
                // Week board item (6) part 2: Plan now on Sunday's card opens Calendar on the Board with Plan your week up.
                DecisionEffect.OPEN_PLAN_WEEK -> (context as? os.meka.android.MekaApplication)?.let { app ->
                    app.openDestination.value = os.meka.android.shell.ShellDestination.CALENDAR
                    app.openPlanWeek.value = true
                }
                DecisionEffect.SET_ASIDE -> {
                    aside.value = aside.value - card.id + card.id
                    undo.show(NeedsYouStackRules.message(card, move)) { aside.value = aside.value - card.id }
                }
            }
        }, aside, { latestOpenLists() }, onRequest = { card, choice ->
            scope.launch {
                when (choice) {
                    // The card leaves on both devices; Undo takes back what Add made (the card stays answered). A reply
                    // card's Copy reply puts the draft on the clipboard: nothing was sent or added, so no Undo.
                    RequestChoice.ADD -> runCatching { core.acceptRequest(card.id) }.getOrNull()?.let { done ->
                        done.copyText?.let { os.meka.android.work.LiveReplies.copyText(context, it) }
                        val undoIt: (suspend () -> Unit)? = if (done.undoable) suspend { core.undoRequest(done); Unit } else null
                        undo.show(done.line, undoIt)
                    }
                    RequestChoice.CHANGE -> runCatching { core.changeRequest(card.id) }.getOrNull()?.let { done ->
                        undo.show(done.line) { core.undoRequest(done); changed.value = null }
                        done.taskId?.let { changed.value = RequestChange(card.proposal.title, it) }
                    }
                    RequestChoice.DECLINE -> if (runCatching { core.declineRequest(card.id) }.getOrDefault(false)) {
                        undo.show("${card.declineLabel} · ${card.from.removePrefix("From ").substringBefore(" · ")}", null)
                    }
                }
                Unit
            }
        }, changed = changed, onTriage = { card, choice, single ->
            val rules = TriageReplyRules
            scope.launch {
                // The keys first: Send's reply re-posts the notification and Seen may follow a removal.
                val keys = if (single) LiveReplies.keysFor(listOf(card.id)) else emptySet()
                val finish = when (choice) {
                    // Meka's tap is the approval (Level 3): the words on the card go through the notification's own Reply.
                    is TriageChoice.Send -> if (LiveReplies.send(context, card.id, choice.reply)) {
                        runCatching { core.sentTriage(card.id) }
                        undo.show(rules.sentLine(card), null)
                        CardFinish.SENT
                    } else {
                        // The notification went meanwhile: WhatsApp opens with the reply typed when the chat's number is
                        // known (WhatsApp replies, slice 1), else the reply is copied and the chat opened; Meka sends it there.
                        val link = rules.replyLink(card, choice.reply)
                        val typed = link != null && LiveReplies.openLink(context, link)
                        if (!typed) LiveReplies.open(context, card.id, card.app, choice.reply)
                        runCatching { core.seenTriage(card.id) }
                        undo.show(if (typed) TriageReplyRules.SEND_FAILED_TO_APP_LINE else TriageReplyRules.SEND_FAILED_LINE, null)
                        CardFinish.OPENED
                    }
                    // Reply in WhatsApp (slice 1): the chat opens with his words typed; he taps WhatsApp's send.
                    is TriageChoice.ReplyInApp -> {
                        val link = rules.replyLink(card, choice.reply)
                        val typed = link != null && LiveReplies.openLink(context, link)
                        if (!typed) LiveReplies.open(context, card.id, card.app, choice.reply)
                        runCatching { core.seenTriage(card.id) }
                        undo.show(if (typed) rules.replyInAppLine(card) else rules.openChatLine(card, rules.appName(card.app)), null)
                        CardFinish.OPENED
                    }
                    TriageChoice.OpenChat -> {
                        LiveReplies.open(context, card.id, card.app, card.draft)
                        runCatching { core.seenTriage(card.id) }
                        undo.show(rules.openChatLine(card, rules.appName(card.app)), null)
                        CardFinish.OPENED
                    }
                    TriageChoice.NotNow -> {
                        hide(listOf(card.id))
                        runCatching { core.dismissTriage(card.id) }.fold(
                            { if (it) undo.show(rules.notNowLine(card), null) },
                            { e -> restore(listOf(card.id)); undo.show(InstantTapRules.failedLine("Not now", e.message), null) },
                        )
                        CardFinish.NOT_NOW
                    }
                    TriageChoice.Seen -> {
                        hide(listOf(card.id))
                        runCatching { core.seenTriage(card.id) }.fold(
                            { if (it) undo.show(rules.seenLine(card), null) },
                            { e -> restore(listOf(card.id)); undo.show(InstantTapRules.failedLine("Seen", e.message), null) },
                        )
                        CardFinish.SEEN
                    }
                }
                // A one-message card finished: its notification leaves the shade too (hidden only, after the reply went).
                if (single && ChatShadeRules.clearsAfter(finish)) os.meka.android.work.WorkCaptureService.clear(keys)
                Unit
            }
        }, onTriageChat = { chat, tap ->
            // Taps feel instant (Meka 2026-10-25 01:45: "When I click on Done it takes at least 4 seconds"): the chat's
            // card leaves in this frame; Mark read, the shade and the one batched write follow.
            hide(chat.ids)
            scope.launch {
                val markable = ChatShadeRules.markable(chat, LiveReplies.markable.value)
                val onDone = (context as? MekaApplication)?.captures?.triageSettings?.value?.markReadOnDone == true
                var finish = ChatShadeRules.finish(tap, markable, onDone)
                // The keys first: a fired Mark as read makes WhatsApp take its notification (and LiveReplies forgets it).
                val keys = LiveReplies.keysFor(chat.ids)
                val marked = finish.markRead && LiveReplies.markRead(context, chat.ids) > 0
                // Mark read asked for but WhatsApp refused it: open the chat so Meka marks it there.
                if (tap == ChatFinishTap.MARK_READ && !marked) finish = finish.copy(markRead = false, clear = false, openChat = true)
                if (finish.openChat) LiveReplies.open(context, chat.cards.last().id, chat.app, null)
                // Hidden from the shade only, after any Mark read (its action goes with the notification).
                if (finish.clear) os.meka.android.work.WorkCaptureService.clear(keys)
                val seen = runCatching { core.seenTriageChat(chat.ids) }.getOrElse { e ->
                    restore(chat.ids)
                    undo.show(InstantTapRules.failedLine("Done", e.message), null)
                    return@launch
                }
                // The group's digest chatter rode in this card (slice 3b): finishing the chat catches the group up too,
                // here and in the Mac's digest.
                chat.groupKey?.takeIf { chat.chatter > 0 }?.let { k ->
                    (context as? MekaApplication)?.captures?.caughtUp(listOf(k))
                    runCatching { core.catchUpGroupDigest(listOf(k)) }
                }
                if (seen > 0 || marked || finish.openChat) undo.show(ChatShadeRules.line(chat, finish, marked), null)
                Unit
            }
        }, onChatArrival = { chat, on ->
            val store = (context as? MekaApplication)?.captures
            if (store != null) {
                store.setClearOnArrival(ChatShadeRules.setClearOnArrival(store.triageSettings.value.clearOnArrival, chat, on))
                undo.show(ChatShadeRules.arrivalLine(chat.title, on), null)
            }
        }, onChatReadToEnd = { chat ->
            if (ChatShadeRules.readToEndClears(chat)) os.meka.android.work.WorkCaptureService.clear(LiveReplies.keysFor(chat.ids))
        }, onSendAll = { cards ->
            scope.launch {
                var sent = 0
                cards.forEach { card ->
                    val reply = TriageReplyRules.cleanReply(card.draft) ?: return@forEach
                    if (LiveReplies.send(context, card.id, reply)) {
                        sent++
                        runCatching { core.sentTriage(card.id) }
                    }
                }
                // Any that couldn't go stay on their cards, with Open chat.
                if (sent > 0) undo.show(TriageReplyRules.sentAllLine(sent), null)
                Unit
            }
        }, undoLine = { line, back -> undo.show(line, back) }, onSchoolCover = { card, home ->
            scope.launch {
                // The question leaves both apps; Undo opens it again and takes back the work-from-home days it made.
                runCatching { core.coverSchool(card.id, home) }.getOrNull()
                    ?.let { done -> undo.show(done.line) { core.undoSchoolCover(done) } }
                Unit
            }
        }, openTask = { latestOpenTask(it) }, junkReview = junkReview, onToDay = { card, day ->
            // Week board item (5) part 3: the overdue or due-today task to that day of the Board; Undo puts it back.
            val id = card.taskId
            if (id != null) scope.launch {
                val line = runCatching { core.needsYouToDay(id, day) }.getOrNull() ?: return@launch
                if (line != BoardFeedRules.CANT_MOVE) undo.show(line) { core.undoBoardMove(); Unit } else undo.show(line, null)
            }
        }, decided = decided)
    }
}

/**
 * The Needs you list: its title, the after-work card, then the stack (or "Nothing needs you" beside the breathing
 * check ring). The tab shows it with the big title; the command centre with a smaller heading that carries the count.
 */
@Composable
internal fun NeedsYouColumn(
    core: MekaCore, stack: NeedsYouStack, moves: DecisionMoves, modifier: Modifier, openAfterWork: () -> Unit,
    compact: Boolean = false, play: Boolean = true,
) {
    // Taps feel instant (2026-10-25): what was just decided here is hidden at once, until the views no longer hold it.
    val hidden = moves.hidden
    val cards = NeedsYouStackRules.ordered(NeedsYouStack(InstantTapRules.stack(stack.cards, hidden)), moves.setAside)
    val requests by core.requests.collectAsState()
    val approvals by core.approvals.collectAsState()
    val allTriage by core.triage.collectAsState()
    val triage = remember(allTriage, hidden) { InstantTapRules.triage(allTriage, hidden) }
    LaunchedEffect(allTriage, stack) { moves.settle(allTriage.map { it.id } + stack.cards.map { it.id }) }
    val school by core.schoolView.collectAsState()
    val covers = school.covers
    // Date night's week-before card (slice 2) sits with the school's questions: Meka's own plans to make.
    val dateNight by core.dateNightView.collectAsState()
    val nudge = dateNight.nudge
    // Appointments that have come due (appointment nudges, slice 1) follow date night: Meka's own bookings to make.
    val appointments by core.appointmentsView.collectAsState()
    val due = appointments.nudges
    var bookRefused by remember { mutableStateOf<Pair<String, String>?>(null) }
    val appContext = LocalContext.current.applicationContext
    val asks = covers.size + (if (nudge != null) 1 else 0) + due.size
    val live by LiveReplies.live.collectAsState()
    val markable by LiveReplies.markable.collectAsState()
    val sendAll = remember(triage, live) { TriageReplyRules.sendAll(triage, live) }
    // A person's requests sit inside their chat's card (slice 3); the rest keep their own cards below.
    val grouped = remember(triage, requests) { TriageChatRules.chatsWith(triage, requests) }
    val looseRequests = remember(grouped, requests) { TriageChatRules.looseRequests(grouped, requests) }
    // Meka's edits of MEKA's drafts, by message id, so Send and "Send all" send what each card shows.
    val edits = remember { mutableStateMapOf<String, String>() }
    // Busy groups' chatter, kept on this phone (V1, messages slice 4): open at the top from 12:30 and 18:30, else a line.
    val captures = (LocalContext.current.applicationContext as MekaApplication).captures
    // A group with a chat card here carries its digest chatter in that card (slice 3b): the digest leaves it out.
    // A group with a card in "While you were at work" is shown there, never twice (Meka, 2026-10-15 20:28).
    val heldGroups = core.afterWork.collectAsState().value.groupKeys
    val chatGroups = remember(grouped, heldGroups) { (TriageChatRules.groupKeys(grouped) + heldGroups).distinct() }
    val digestNow = rememberGroupDigest(captures, core, chatGroups)
    val digest = digestNow.view
    val archived by core.archivedForYou.collectAsState()
    val unopened by core.mailUnopened.collectAsState()
    val mailNeeds by core.mailNeeds.collectAsState()
    val mailChases by core.mailChases.collectAsState()
    val mailFinds by core.mailFinds.collectAsState()
    val discord by core.discord.collectAsState()
    val mailMoment by core.mailMoment.collectAsState()
    val mailMomentSession by core.mailMomentSession.collectAsState()
    val gists by core.groupGists.collectAsState()
    // A busy chat's one-line summary (slice 3c), made on this phone and synced; asked for when the chats change.
    val summaries by core.chatSummaries.collectAsState()
    val chats = remember(grouped, digestNow.chatter, gists, summaries) {
        ChatSummaryRules.withSummaries(TriageChatRules.withDigest(grouped, digestNow.chatter, gists), summaries)
    }
    val summaryApp = LocalContext.current.applicationContext as MekaApplication
    LaunchedEffect(grouped.map { it.key to it.cards.size }) { ChatSummarise.run(summaryApp) }
    val triageSettings by captures.triageSettings.collectAsState()
    val peopleLists by captures.lists.collectAsState()
    // Taps feel instant: a habit's tick shows in the same frame ([HabitTaps]).
    val goals = os.meka.android.goals.shownGoals(core.goalsView)
    val lists by core.listsView.collectAsState()
    // Coming up (Fold review 2026-10-12 17:56, item 3): the next two reminders, worked out by the core from every task.
    val comingUp = core.today.collectAsState().value.comingUp
    val meanwhile = remember(goals, lists, comingUp) { NeedsYouMeanwhileRules.build(goals, lists, comingUp) }
    // Messages still waiting turn "Nothing needs you" into "Nothing to decide · 9 messages to catch up on".
    val afterWork by core.afterWork.collectAsState()
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val tickHabit: (HabitItem) -> Unit = { h -> haptics.light(); os.meka.android.goals.HabitTaps.tick(scope, core, h.id, !h.doneToday) }
    val openMail: () -> Unit = {
        summaryApp.openMail.value = true
        summaryApp.openDestination.value = os.meka.android.shell.ShellDestination.ASK
    }
    // The foot fades into the tabs rather than cutting a card in half; the xl bottom padding clears the fade.
    LazyColumn(
        modifier.footFade(),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "title") {
            if (compact) {
                SectionLabel(CommandCentreRules.needsYouHeading(cards.size + approvals.size + looseRequests.size + chats.size + asks), Modifier.animateItem().appear(rememberAppearance(0, play)))
            } else {
                Text("Needs you", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0, play)))
            }
        }
        // The approvals inbox (V1, slice 1) first: time-limited, and nothing happens until Meka answers.
        approvals.forEachIndexed { i, card ->
            item(key = "approval-${card.id}") {
                ApprovalCardView(card, core, { line -> moves.undoLine(line, null) },
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(1 + i, play)))
            }
        }
        item(key = "after-work") {
            AfterWorkCard(core, Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(1, play)), moves.undoLine) { openAfterWork() }
        }
        digest?.takeIf { it.due }?.let { d ->
            item(key = "group-digest") {
                GroupDigestSection(d, captures, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Inbox autopilot, slice 7: the ten-minute inbox — from the moment, Start goes through the emails one by one.
        if (mailMoment != null || mailMomentSession != null) {
            item(key = "mail-moment") {
                MailMomentSection(mailMoment, mailMomentSession, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Inbox autopilot, slice 6a: the emails that need Meka, newest first, each with Done and Snooze.
        // While the ten-minute inbox runs, it shows them one at a time instead.
        mailNeeds?.takeIf { mailMomentSession == null }?.let { n ->
            item(key = "mail-needs") {
                MailNeedsSection(n, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Inbox autopilot, slice 6b-3: Waiting for items from email whose chase is due, a chase drafted for each.
        mailChases?.let { c ->
            item(key = "mail-chases") {
                MailChaseSection(c, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Promises, receipts and renewals, slice 1: renewals read from his mail, each added to Renewals only on his tap.
        mailFinds?.let { f ->
            item(key = "mail-finds") {
                MailFindSection(f, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Discord channel watch, slice 1: one card per channel he follows, its posts since he last looked, Done.
        discord?.let { d ->
            item(key = "discord") {
                DiscordSection(d, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Inbox autopilot, slice 4: what Autopilot archived, with the digests (12:30, 18:30) until Got it.
        archived?.let { a ->
            item(key = "archived-for-you") {
                ArchivedForYouSection(a, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)),
                    openAll = { moves.archivedAll.value = true })
            }
        }
        // Inbox autopilot, slice 5c: the weekly "Not opened in 30 days", from Saturday 09:00 until answered.
        unopened?.let { u ->
            item(key = "mail-unopened") {
                UnopenedOfferSection(u, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2, play)))
            }
        }
        // Messages that need a reply (with MEKA's draft), then FYIs (V1, messages slice 3), above the requests: "Send all"
        // first when two or more short replies can go; each card staggers in after the after-work card and folds away
        // with the list's item motion once answered.
        sendAll?.let { all ->
            item(key = "triage-send-all") {
                TriageSendAllRow(all, {
                    val chosen = triage.filter { it.id in all.ids }.sortedBy { it.atMs }
                    moves.onSendAll(chosen.map { c -> edits[c.id]?.let { e -> c.copy(draft = e) } ?: c })
                },
                    Modifier.animateItem().appear(rememberAppearance(2, play)))
            }
        }
        // One card per conversation (messages grouped per chat, slice 1): a chat with one message shows its card as
        // before; two or more show the chat's card, with every message's own card under Show all.
        chats.forEachIndexed { i, chat ->
            item(key = "triage-chat-${chat.key}") {
                val cardFor: @Composable (TriageCard, Modifier) -> Unit = { card, m ->
                    TriageCardView(card, card.id in live, edits[card.id] ?: card.draft.orEmpty(), { edits[card.id] = it }, { moves.onTriage(card, it, false) }, m)
                }
                val m = Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2 + i, play))
                val chatMarkable = ChatShadeRules.markable(chat, markable)
                if (chat.single) {
                    // One message (slice 2b): its own Mark read while the notification offers it; Send and Seen clear it.
                    val card = chat.cards.single()
                    TriageCardView(card, card.id in live, edits[card.id] ?: card.draft.orEmpty(), { edits[card.id] = it },
                        { moves.onTriage(card, it, true) }, m,
                        markLabel = ChatShadeRules.singleMarkButton(chat.app, chatMarkable),
                        onMark = { moves.onTriageChat(chat, ChatFinishTap.MARK_READ) })
                } else {
                    val arrival = if (ChatShadeRules.canClearOnArrival(chat, peopleLists)) chat.key in triageSettings.clearOnArrival else null
                    TriageChatView(chat, chatMarkable, { tap -> moves.onTriageChat(chat, tap) }, cardFor, m,
                        requestFor = { card, rm -> RequestCardView(card, { moves.onRequest(card, it) }, rm) },
                        chatter = chat.groupKey?.takeIf { chat.chatter > 0 }?.let { k -> { digestNow.thread(k) } },
                        arrival = arrival, onArrival = { on -> moves.onChatArrival(chat, on) },
                        onReadToEnd = { moves.onChatReadToEnd(chat) })
                }
            }
        }
        // School days off on office days, a week ahead (school rhythm, slice 1): soonest first, above the requests.
        covers.forEachIndexed { i, card ->
            item(key = "school-cover-${card.id}") {
                SchoolCoverCardView(card, { home -> moves.onSchoolCover(card, home) },
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2 + triage.size + i, play)))
            }
        }
        nudge?.let { card ->
            item(key = "date-night-${card.day}") {
                DateNightCardView(card, onBooked = {
                    scope.launch {
                        runCatching { core.bookDateNight(card.day, true) }.getOrNull()
                            ?.let { line -> moves.undoLine(line) { core.bookDateNight(card.day, false) } }
                    }
                }, onSkip = {
                    scope.launch {
                        runCatching { core.skipDateNight(card.day, true) }.getOrNull()
                            ?.let { line -> moves.undoLine(line) { core.skipDateNight(card.day, false) } }
                    }
                }, Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2 + triage.size + covers.size, play)))
            }
        }
        due.forEachIndexed { i, card ->
            item(key = "appointment-${card.id}") {
                AppointmentCardView(card, bookRefused?.takeIf { it.first == card.id }?.second, onBooked = { typed ->
                    scope.launch {
                        val r = runCatching { core.bookAppointment(card.id, typed) }.getOrNull() ?: return@launch
                        if (r.refused) bookRefused = card.id to r.line
                        else { bookRefused = null; moves.undoLine(r.line) { core.undoAppointment(r) } }
                    }
                }, onWent = {
                    scope.launch {
                        runCatching { core.appointmentWent(card.id) }.getOrNull()
                            ?.let { r -> moves.undoLine(r.line) { core.undoAppointment(r) } }
                    }
                }, onNotYet = {
                    scope.launch {
                        runCatching { core.appointmentNotYet(card.id) }.getOrNull()
                            ?.let { r -> moves.undoLine(r.line) { core.undoAppointment(r) } }
                    }
                }, onCall = {
                    scope.launch {
                        runCatching { core.appointmentCallToBook(card.id) }.getOrNull()
                            ?.let { c -> moves.undoLine(c.line) { core.undoAppointmentCall(c) } }
                    }
                }, onEmail = {
                    scope.launch {
                        // Slice 2b: the email goes on the clipboard; nothing was sent or changed, so no Undo.
                        runCatching { core.appointmentBookingEmail(card.id) }.getOrNull()?.let { text ->
                            os.meka.android.work.LiveReplies.copyText(appContext, text)
                            moves.undoLine(os.meka.core.domain.AppointmentRules.EMAIL_COPIED, null)
                        }
                    }
                }, modifier = Modifier.animateItem().padding(bottom = MekaSpace.m)
                    .appear(rememberAppearance(2 + triage.size + covers.size + (if (nudge != null) 1 else 0) + i, play)))
            }
        }
        // Requests from people Meka watches, oldest first, above the stack (V1, requests slice 4): each staggers in
        // after the after-work card and folds away with the list's item motion once answered.
        looseRequests.forEachIndexed { i, card ->
            item(key = "request-${card.id}") {
                RequestCardView(card, { moves.onRequest(card, it) },
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2 + triage.size + asks + i, play)))
            }
        }
        // Between digests, groups with news are one quiet line under the cards that Meka can open on demand.
        digest?.takeIf { !it.due }?.let { d ->
            item(key = "group-digest") {
                GroupDigestSection(d, captures, core, moves.undoLine,
                    Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(2 + triage.size + asks + requests.size, play)))
            }
        }
        if (cards.isEmpty() && approvals.isEmpty() && requests.isEmpty() && triage.isEmpty() && asks == 0) {
            item(key = "clear") {
                // The breathing check ring beside a light line (catalogue "Empty states"; Fold review 2026-10-08,
                // item 7), on the full page and in the open Fold's column alike (Fold review 2026-10-09 00:10, item 6);
                // the caption saying what lands here only on the full page.
                Row(Modifier.animateItem().appear(rememberAppearance(1, play)).semantics(mergeDescendants = true) { },
                    verticalAlignment = Alignment.CenterVertically) {
                    BreathingRing(Modifier.padding(end = MekaSpace.m), size = if (compact) 22.dp else 28.dp, check = true)
                    Column {
                        Text(NeedsYouStackRules.emptyLine(afterWork.toCatchUp), style = if (compact) MekaType.itemMeta else MekaType.body, color = Meka.colors.textPrimary)
                        if (!compact) Text(NeedsYouStackRules.EMPTY_CAPTION, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                    }
                }
            }
            // Approve must land: what Navilife is doing with his mail since his Approve, counting up.
            workingItems(stack.working, play, index = 2, open = openMail)
            // Then what's coming for Meka instead of a blank page: today's habits (ticked inline), Waiting on and the
            // next renewals (NeedsYouMeanwhile; Fold reviews 2026-10-09, 00:10 item 6 and 07:26 item 10).
            meanwhileItems(meanwhile, play, firstIndex = 2, tick = tickHabit, open = moves.openLists, openTask = moves.openTask)
        } else if (cards.isNotEmpty()) {
            item(key = "stack") {
                DecisionStackView(cards, moves.onMove, Modifier.animateItem().appear(rememberAppearance(1, play)),
                    toDays = { core.needsYouFeedDays() }, onToDay = moves.onToDay)
            }
            workingItems(stack.working, play, index = 2, open = openMail)
            // Under the stack, the next reminders (the gate's Needs you re-shot: the page below the stack was blank).
            meanwhileItems(NeedsYouMeanwhileRules.underStack(meanwhile), play, firstIndex = 2, tick = tickHabit, open = moves.openLists, openTask = moves.openTask)
        }
    }
}

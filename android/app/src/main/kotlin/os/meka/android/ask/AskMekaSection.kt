package os.meka.android.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import os.meka.android.shell.OpenItem
import os.meka.core.domain.AskFieldRules
import os.meka.core.domain.AskMatch
import os.meka.core.domain.AskMatches
import os.meka.core.domain.AskReturn
import os.meka.core.domain.SearchTarget
import os.meka.android.calendar.EventUndo
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.AskAutoDone
import os.meka.core.domain.AskCard
import os.meka.core.domain.TrustOffer
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskRules
import os.meka.core.domain.HelloOrb
import os.meka.core.domain.TalkOrb
import os.meka.core.domain.TalkPhase
import os.meka.core.facade.MekaCore

/** What Ask is showing under its field: nothing yet, MEKA thinking, an answer, or why there is none. */
private sealed interface AskShown {
    data object Idle : AskShown
    data class Thinking(val question: String) : AskShown
    data class Answer(val question: String, val outcome: AskOutcome, val n: Int) : AskShown
}

/**
 * Ask MEKA on the Fold (build plan V1, AI layer slice 3b). One field (Fold review 2026-10-09 07:26, item 8): Return
 * asks MEKA in your own words, and while typing the best matches from Search everything show under it ([AskFieldRules];
 * a task opens its detail over Search, a list item, goal or habit opens its place, "See all 12 matches" opens Search);
 * the mic sits inside the field at its right end. While asking can't work (AI off, the month's budget used up, not
 * connected) the field only searches and Return opens Search everything.
 * Under the field, MEKA's AI only when it needs a look ("Off", the month's budget used up; the spend itself is in
 * Settings → MEKA's AI). Asking
 * shows the question, a thinking shimmer, then the answer's lines fading in one after another and up to three cards
 * rising under them; a card does nothing until tapped (light haptic), then leaves and the undo bar rises with what it
 * did and Undo. Motion per the catalogue's Assistant row; reduced motion cross-fades.
 *
 * Talk to MEKA (V1 voice slice 2): the mic in the field starts a spoken conversation ([TalkController]; the
 * microphone permission is asked the first time). The voice orb takes the field's place under it, the live transcript
 * types in, MEKA's answers show here as typed ones do (their cards can still be tapped) and are said aloud; a spoken
 * yes folds the cards away with one undo bar. Tapping the orb while MEKA speaks interrupts it; otherwise it ends.
 */
@Composable
fun AskMekaSection(
    core: MekaCore, undo: EventUndo,
    /** Opens Search everything with [query] typed, and the task [taskId]'s detail over it when given. */
    openSearch: (query: String, taskId: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a list item, goal or habit where it lives. */
    openItem: (OpenItem) -> Unit = {},
    /** False where the field only asks (the bedside's Talk pane). */
    matches: Boolean = true,
    /** Bumped when Search everything closes, so the field searches its own text again. */
    searchEpoch: Int = 0,
    /** Take me there under a help answer (Ask knows how Navilife itself works): opens that screen. */
    openHelp: (os.meka.core.domain.HelpPlace) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    var status by remember { mutableStateOf<AiStatusView?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var shown by remember { mutableStateOf<AskShown>(AskShown.Idle) }
    var done by remember { mutableStateOf(setOf<Int>()) }
    // Earned trust: the offer after the fifth yes in a row, and MEKA's own actions taken back in this answer.
    var offer by remember { mutableStateOf<TrustOffer?>(null) }
    var autoUndone by remember { mutableStateOf(setOf<String>()) }
    var asks by remember { mutableStateOf(0) }
    // The question MEKA is answering (or last answered); the matches step aside for it until the field changes.
    var asked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(asks) { status = core.aiStatus() }
    val canAsk = status?.canAsk != false
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    // After the first ask on this phone the paragraph under the field becomes a one-line hint (Fold review 17:56, item 2).
    var usedAsk by remember { mutableStateOf(AskUse.used(context)) }
    fun markUsed() { if (!usedAsk) { usedAsk = true; AskUse.mark(context) } }
    val talk = remember {
        TalkController(
            context, core, scope,
            onAnswer = { q, out ->
                markUsed()
                asked = q
                done = emptySet()
                autoUndone = emptySet()
                offer = null
                shown = AskShown.Answer(q, out, asks + 1)
                asks += 1
            },
            onDid = { did, cards ->
                val answer = (shown as? AskShown.Answer)?.outcome as? AskOutcome.Answered
                val idx = cards.mapNotNull { c -> answer?.answer?.cards?.indexOf(c)?.takeIf { it >= 0 } }.toSet()
                done = done + idx
                if (did.done.isNotEmpty()) haptics.light()
                did.offer?.let { offer = it }
                val undos = did.undos
                undo.show(did.barLine, if (undos.isEmpty()) null else suspend { core.undoTalk(undos); done = done - idx })
            },
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                talk.stop()
                // Money manager (16): an unlock from Ask ends when Navilife goes to the background.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { runCatching { core.lockMoneyAsk() } }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); talk.release() }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) talk.start() else talk.refused()
    }
    fun startTalking() {
        keyboard?.hide()
        haptics.light()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) talk.start()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    // "Hello, Navilife": the resting orb's tap says the greeting, then listens (the mic is asked for first if needed).
    val helloMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) talk.hello() else talk.refused()
    }
    fun sayHello() {
        keyboard?.hide()
        haptics.light()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) talk.hello()
        else helloMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val needsYouStack by core.needsYouStack.collectAsState()

    // Talk without tapping the mic (slice 1): the side button or the headphones' button opened MEKA on Ask; start
    // listening once MEKA is in front (a start before that would be stopped by the lifecycle observer above).
    val app = context.applicationContext as? os.meka.android.MekaApplication
    val talkNow = app?.talkNow?.collectAsState()?.value
    LaunchedEffect(talkNow) {
        if (talkNow == null) return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        app?.talkNow?.value = null
        if (talk.active) return@LaunchedEffect
        if (talkNow == os.meka.core.domain.TalkStart.HELLO) {
            // "Hello, Navilife" slice 2: the bedside orb was tapped; the greeting, then listening.
            sayHello()
        } else if (talkNow == os.meka.core.domain.TalkStart.OPEN) {
            // "Listen when I open MEKA" (slice 4): the room check, then a short window; nothing said → Today again.
            talk.startOnOpen { app?.openDestination?.value = os.meka.android.shell.ShellDestination.TODAY }
        } else startTalking()
    }
    // After the brief's Listen ran to its end (slice 7): listen for 8 s; nothing said → back where the brief was.
    val talkAfterBrief = app?.talkAfterBrief?.collectAsState()?.value
    LaunchedEffect(talkAfterBrief) {
        val back = talkAfterBrief ?: return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        app?.talkAfterBrief?.value = null
        if (talk.active) return@LaunchedEffect
        keyboard?.hide()
        talk.startAfterBrief { if (back != os.meka.android.shell.ShellDestination.ASK) app?.openDestination?.value = back }
    }

    /** Asks what was typed; [again] asks that question of the AI past the help ("Still stuck? Ask Navilife"). */
    fun ask(again: String? = null) {
        val q = again ?: AskRules.question(text) ?: return
        keyboard?.hide()
        haptics.light()
        shown = AskShown.Thinking(q)
        markUsed()
        asked = q
        done = emptySet()
        autoUndone = emptySet()
        offer = null
        scope.launch {
            // "Where I am now": a question about here takes one approximate fix first (only with the switch on).
            os.meka.android.today.HereLocation.refresh(context, core, q)
            val out = if (again != null) core.askBeyondHelp(q) else core.askMeka(q)
            shown = AskShown.Answer(q, out, asks + 1)
            asks += 1
            if (out is AskOutcome.Answered) text = ""
        }
    }

    /**
     * Money manager (16): "Unlock Money to answer" under a money question asked while Money is locked — the fingerprint
     * (or the phone's PIN), then the same question again, answered on the phone from his own Money.
     */
    fun unlockMoney(q: String) {
        haptics.tick()
        os.meka.android.security.FoldBiometric.unlock(context, os.meka.core.domain.MoneyAskRules.UNLOCK_REASON, "Your spending, read-only · on this phone") { ok ->
            if (!ok) { haptics.tick(); return@unlock }
            haptics.light()
            shown = AskShown.Thinking(q)
            scope.launch {
                runCatching { core.unlockMoneyAsk() }
                val out = core.askMeka(q)
                shown = AskShown.Answer(q, out, asks + 1)
                asks += 1
            }
        }
    }

    fun tap(i: Int, card: AskCard) {
        haptics.light()
        talk.cardTapped(card)
        done = done + i
        scope.launch {
            try {
                val d = core.doAsk(card)
                d.offer?.let { offer = it }
                val back = d.undo
                undo.show(d.line, back?.let { u -> suspend { core.undoAsk(u); done = done - i } })
            } catch (e: Exception) {
                done = done - i
                undo.show(e.message ?: "That can't be done now", null)
            }
        }
    }

    // Earned trust: Undo on something MEKA did on its own; that kind goes back to asking first.
    fun undoAuto(a: AskAutoDone) {
        haptics.tick()
        autoUndone = autoUndone + a.activityId
        scope.launch { undo.show(core.undoAutoAsk(a.activityId), null) }
    }

    fun answerOffer(o: TrustOffer, yes: Boolean) {
        if (yes) haptics.light() else haptics.tick()
        offer = null
        scope.launch { undo.show(core.answerTrustOffer(o.kindKey, yes), null) }
    }

    // The field searches as you type (Fold review 2026-10-09 07:26, item 8): the best matches show under it.
    val view by core.searchView.collectAsState()
    var lastMatches by remember { mutableStateOf<AskMatches?>(null) }
    val searching = matches && AskFieldRules.showMatches(text, asked)
    LaunchedEffect(text, searching, searchEpoch) {
        if (!searching) { if (lastMatches != null) { lastMatches = null; core.search("") }; return@LaunchedEffect }
        delay(AskFieldRules.SETTLE_MS)
        core.search(text)
    }
    val fresh = AskFieldRules.matches(view, text, canAsk)
    // Until the new matches arrive the last ones stay (no flicker while typing).
    LaunchedEffect(fresh, searching) { if (searching && fresh != null) lastMatches = fresh }

    fun openMatch(m: AskMatch) {
        if (!m.opens) return
        keyboard?.hide()
        haptics.light()
        if (m.hit.target == SearchTarget.TASK) openSearch(text, m.hit.id)
        else { scope.launch { core.search("") }; openItem(OpenItem(m.hit.target, m.hit.id)) }
    }

    fun onReturn() {
        when (AskFieldRules.onReturn(text, canAsk)) {
            AskReturn.ASK -> ask()
            AskReturn.SEARCH -> { keyboard?.hide(); openSearch(text, null) }
            AskReturn.NOTHING -> Unit
        }
    }

    Column(modifier.fillMaxWidth()) {
        // One field: it asks (Return) and searches (as you type); the mic sits inside it at the right end.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clip(RoundedCornerShape(MekaRadius.pill))
                .background(Meka.colors.surfaceRaised)
                .padding(start = MekaSpace.l, end = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(AskFieldRules.placeholder(canAsk), style = MekaType.body, color = Meka.colors.textTertiary)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(AskRules.MAX_QUESTION) },
                    singleLine = true,
                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                    cursorBrush = SolidColor(Meka.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = if (canAsk) ImeAction.Send else ImeAction.Search),
                    keyboardActions = KeyboardActions(onSend = { onReturn() }, onSearch = { onReturn() }),
                    modifier = Modifier.fillMaxWidth().testTag(ASK_FIELD_TAG)
                        .semantics { contentDescription = AskFieldRules.fieldLabel(canAsk) },
                )
            }
            if (canAsk) {
                // The mic: starts talking (or ends it), the orb's resting look, inside the field.
                VoiceOrb(
                    TalkPhase.ENDED, 0f, size = 36.dp,
                    modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button) { if (talk.active) { haptics.tick(); talk.stop() } else startTalking() }
                        .testTag(ASK_MIC_TAG)
                        .semantics { contentDescription = if (talk.active) "Stop talking to Navilife" else "Talk to Navilife" },
                )
            }
        }
        // The matches unfold under the field (expand spring) and glide as they narrow.
        AnimatedVisibility(
            visible = searching && lastMatches != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            lastMatches?.let { m ->
                AskMatchList(m, onOpen = { openMatch(it) }, onSeeAll = { keyboard?.hide(); haptics.tick(); openSearch(text, null) })
            }
        }
        // MEKA's AI and the month's spend live in Settings → MEKA's AI (Fold review 2026-10-12, item 7); here only when
        // it needs a look (off, the budget used up), since it's why Return searches instead of asking. Unfolds in place.
        AnimatedVisibility(
            visible = status?.lit == true,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Text(
                "Navilife's AI · " + (status?.line ?: ""), style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.padding(top = MekaSpace.xs, start = MekaSpace.xxs),
            )
        }
        // Talking: the orb, what it's doing, the live transcript and what MEKA said; or why it can't listen.
        AnimatedVisibility(
            visible = talk.active || talk.problem != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            TalkPanel(talk, needsYouStack.cards.size) { haptics.tick(); talk.tapOrb() }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = MekaSpace.m, bottom = MekaSpace.l)
                .animateContentSize(MekaMotion.expand(reduced))
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            when (val s = shown) {
                AskShown.Idle -> if (!searching) {
                    // "Hello, Navilife": the orb at rest, glowing warm while Needs you has cards; a tap greets him.
                    if (canAsk && !talk.active && talk.problem == null) {
                        val line = HelloOrb.restingLine(needsYouStack.cards.size, mac = false)
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = MekaSpace.m).appear(rememberAppearance(0)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                        ) {
                            HelloOrbView(
                                HelloOrb.state(TalkPhase.ENDED, needsYouStack.cards.size), 0f,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                    .clickable(role = Role.Button) { sayHello() }
                                    .testTag(ASK_HELLO_TAG)
                                    .semantics { contentDescription = "Navilife · $line" },
                            )
                            Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                    }
                    Text(
                        AskFieldRules.idleLine(canAsk, mac = false, used = usedAsk),
                        style = MekaType.caption, color = Meka.colors.textTertiary,
                    )
                    // Ask polish, item 5: two things to ask beside the one-line hint; a tap asks it.
                    val examples = AskFieldRules.examples(canAsk, usedAsk)
                    if (examples.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        examples.forEach { q -> ExampleChip(q) { text = q; ask() } }
                    }
                }
                is AskShown.Thinking -> {
                    QuestionLine(s.question)
                    SkeletonRows(count = 2, rowHeight = 18.dp)
                }
                is AskShown.Answer -> key(s.n) {
                    QuestionLine(s.question)
                    when (val o = s.outcome) {
                        is AskOutcome.Unavailable -> Text(o.line, style = MekaType.body, color = Meka.colors.textSecondary,
                            modifier = Modifier.appear(rememberAppearance(0)))
                        is AskOutcome.Answered -> {
                            val lines = AskRules.answerLines(o.answer.text)
                            lines.forEachIndexed { i, line ->
                                Text(line, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(i)))
                            }
                            // Earned trust: what MEKA already did on its own, each with Undo; an undone one folds away.
                            o.answer.did.forEachIndexed { k, a ->
                                AnimatedVisibility(
                                    visible = a.activityId !in autoUndone,
                                    enter = fadeIn(MekaMotion.appear(reduced)),
                                    exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                                ) {
                                    AskAutoRow(a, Modifier.appear(rememberAppearance(lines.size + k))) { undoAuto(a) }
                                }
                            }
                            o.answer.cards.forEachIndexed { j, card ->
                                AnimatedVisibility(
                                    visible = j !in done,
                                    enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                                    exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                                ) {
                                    AskCardRow(card, Modifier.appear(rememberAppearance(lines.size + o.answer.did.size + j))) { tap(j, card) }
                                }
                            }
                            // Ask knows how Navilife itself works: Take me there under an answer from the help.
                            o.answer.help?.let { link ->
                                HelpLinkRow(link, Modifier.appear(rememberAppearance(lines.size + o.answer.did.size + o.answer.cards.size))) {
                                    keyboard?.hide()
                                    haptics.light()
                                    openHelp(link.place)
                                }
                            }
                            // Slice 2: "Still stuck? Ask Navilife" asks the same question of the AI, past the help.
                            o.answer.stillStuck?.let { label ->
                                StillStuckRow(label, Modifier.appear(rememberAppearance(lines.size + o.answer.did.size + o.answer.cards.size + 1))) {
                                    haptics.tick()
                                    ask(again = s.question)
                                }
                            }
                            // Money manager (16): a money question while Money is locked; the fingerprint, then the answer.
                            o.answer.unlockMoney?.let { label ->
                                UnlockMoneyRow(label, Modifier.appear(rememberAppearance(lines.size))) { unlockMoney(s.question) }
                            }
                            // Earned trust: "want me to just do these?" rises under the answer after the fifth yes in a row.
                            AnimatedVisibility(
                                visible = offer != null,
                                enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                                exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                            ) {
                                // Kept while it folds away (the state is already null by then).
                                var last by remember { mutableStateOf<TrustOffer?>(null) }
                                LaunchedEffect(offer) { offer?.let { last = it } }
                                (offer ?: last)?.let { shownOffer ->
                                    TrustOfferRow(shownOffer, onYes = { answerOffer(shownOffer, true) }, onNo = { answerOffer(shownOffer, false) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

const val ASK_FIELD_TAG = "ask-field"
const val ASK_MIC_TAG = "ask-mic"
const val ASK_HELLO_TAG = "ask-hello"
const val ASK_MATCH_TAG = "ask-match"
const val ASK_AUTO_TAG = "ask-auto"
const val ASK_TRUST_TAG = "ask-trust"
const val ASK_HELP_TAG = "ask-help"
const val ASK_STILL_STUCK_TAG = "ask-still-stuck"
const val ASK_UNLOCK_MONEY_TAG = "ask-unlock-money"

/**
 * The best matches for what's typed: the title, then "Task · Planned today 14:00"; a task, list item, goal or habit
 * opens with a tap; "See all 12 matches" opens Search everything; a row joining slides in with the item motion.
 */
@Composable
internal fun AskMatchList(m: AskMatches, onOpen: (AskMatch) -> Unit, onSeeAll: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).animateContentSize(MekaMotion.replan(reduced)),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        m.rows.forEachIndexed { i, row ->
            key(row.hit.kind.name + row.hit.id) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                        .then(if (row.opens) Modifier.clickable(role = Role.Button) { onOpen(row) } else Modifier)
                        .testTag(ASK_MATCH_TAG)
                        .semantics(mergeDescendants = true) { contentDescription = row.spoken }
                        .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
                        .appear(rememberAppearance(i)),
                ) {
                    Text(row.hit.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1)
                    Text(row.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1)
                }
            }
        }
        m.seeAll?.let { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onSeeAll)
                    .minTouch().padding(horizontal = MekaSpace.xxs))
        }
        m.empty?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(horizontal = MekaSpace.xxs, vertical = MekaSpace.xs)) }
    }
}

/** The orb (tap: interrupt while MEKA speaks, else end), its line, the live transcript and MEKA's words. */
@Composable
private fun TalkPanel(talk: TalkController, needsYou: Int, onOrb: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.m).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        HelloOrbView(
            HelloOrb.state(talk.phase, needsYou), talk.level,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(enabled = talk.active, role = Role.Button, onClick = onOrb)
                .semantics { contentDescription = "Navilife, ${TalkOrb.label(talk.phase, mac = false, talkOver = talk.talkOver)}" },
        )
        AnimatedContent(
            targetState = talk.problem?.line ?: TalkOrb.label(talk.phase, mac = false, talkOver = talk.talkOver),
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "talk-line",
        ) { line ->
            Text(line, style = MekaType.caption, color = if (talk.problem != null) Meka.colors.accent else Meka.colors.textTertiary)
        }
        if (talk.heard.isNotBlank() && talk.problem == null) {
            Text(talk.heard, style = MekaType.body, color = if (talk.phase == TalkPhase.LISTENING) Meka.colors.textSecondary else Meka.colors.textPrimary,
                maxLines = 3, modifier = Modifier.animateContentSize(MekaMotion.expand(reduced)))
        }
        if (talk.said.isNotBlank() && talk.phase == TalkPhase.SPEAKING) {
            Text(talk.said, style = MekaType.itemMeta, color = Meka.colors.accent, maxLines = 4)
        }
    }
}

@Composable
private fun QuestionLine(q: String) {
    Text("“$q”", style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 3)
}

/** Something MEKA did on its own (earned trust): "Added “Milk” · done for you" and an Undo pill. */
@Composable
private fun AskAutoRow(a: AskAutoDone, modifier: Modifier, onUndo: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .testTag(ASK_AUTO_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.line, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 3)
        Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button, onClick = onUndo)
                .semantics { contentDescription = "Undo: ${a.line}" }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}

/** Earned trust's offer: its line, then "Just do it" (accent) and "Keep asking". */
@Composable
private fun TrustOfferRow(o: TrustOffer, onYes: () -> Unit, onNo: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.xs)
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m)
            .testTag(ASK_TRUST_TAG),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(o.line, style = MekaType.body, color = Meka.colors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(o.yes, style = MekaType.itemMeta, color = Meka.colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button, onClick = onYes).minTouch()
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
            Text(o.no, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                    .clickable(role = Role.Button, onClick = onNo).minTouch()
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
        }
    }
}

/** Take me there under a help answer: one accent pill, a 48 dp target; opens the screen the answer names. */
@Composable
private fun HelpLinkRow(link: os.meka.core.domain.HelpLink, modifier: Modifier, onTap: () -> Unit) {
    Text(
        link.button + " ›", style = MekaType.itemMeta, color = Meka.colors.onAccent,
        modifier = modifier.padding(top = MekaSpace.xxs).minTouch().clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.accent)
            .clickable(role = Role.Button, onClick = onTap)
            .testTag(ASK_HELP_TAG)
            .semantics { contentDescription = link.button }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** Under a help answer: a quiet accent line, a 48 dp target; asks the same question of the AI. */
@Composable
private fun StillStuckRow(label: String, modifier: Modifier, onTap: () -> Unit) {
    Text(
        "$label ›", style = MekaType.itemMeta, color = Meka.colors.accent,
        modifier = modifier.minTouch()
            .clickable(role = Role.Button, onClick = onTap)
            .testTag(ASK_STILL_STUCK_TAG)
            .semantics { contentDescription = label }
            .padding(vertical = MekaSpace.xs),
    )
}

/** Money manager (16): "Unlock Money to answer" in an accent pill, like Take me there. */
@Composable
private fun UnlockMoneyRow(label: String, modifier: Modifier, onTap: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = Meka.colors.onAccent,
        modifier = modifier.padding(top = MekaSpace.xxs).minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button, onClick = onTap)
            .testTag(ASK_UNLOCK_MONEY_TAG)
            .semantics { contentDescription = label }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** A proposal: its line and one pill; nothing happens until it is tapped. */
@Composable
private fun AskCardRow(card: AskCard, modifier: Modifier, onTap: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(card.line, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 3)
        Text(card.button, style = MekaType.itemMeta, color = Meka.colors.onAccent,
            modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = "${card.button}: ${card.line}" }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}

/** Whether Meka has asked MEKA anything on this phone yet (the field's long first-use paragraph goes after that). */
internal object AskUse {
    private const val PREFS = "meka.ask"
    private const val KEY = "used"
    fun used(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun mark(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

/** An example question under Ask's hint: a raised pill, a 48 dp target; tapping asks it. */
@Composable
private fun ExampleChip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClickLabel = "Ask") { onClick() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

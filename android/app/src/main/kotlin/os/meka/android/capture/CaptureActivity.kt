package os.meka.android.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import os.meka.android.designsystem.mekaRecomposer
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.RequestChoice
import os.meka.core.domain.FormAnswersView
import os.meka.core.domain.ParkingConfirmationRules
import os.meka.core.domain.ParkingShareView
import os.meka.core.domain.DocumentShareRules
import os.meka.core.domain.DocumentShareView
import os.meka.core.domain.FormFillRules
import os.meka.core.domain.HandleThisRules
import os.meka.core.domain.QueueRules
import os.meka.core.domain.RequestCard
import os.meka.core.facade.HandleOutcome
import os.meka.core.facade.VoiceNoteRead
import os.meka.core.domain.PictureTextRules
import os.meka.core.domain.QuickCapture
import os.meka.core.domain.SpokenCaptureRules
import os.meka.core.domain.SpokenCaptured
import os.meka.core.domain.VoiceNoteRules

/**
 * The capture sheet over whatever Meka was doing (build plan M1, "Capture from anywhere"). Opened by the share
 * sheet, "Capture in MEKA" on selected text, the home-screen widget and the quick-settings tile. Springs up from the
 * bottom with the field ready; Add saves one task (title + notes, [QuickCapture]) and the sheet drops away.
 * Typed or shared text is saved only when Meka taps Add; what he says into the 🎙 on an empty field goes straight in
 * with Undo and Edit ("the mic should just add it", slice 2). Voice uses the phone's speech recogniser.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = CaptureRequests.from(
            intent?.action, intent?.type,
            intent?.getCharSequenceExtra(CaptureRequests.EXTRA_TEXT) ?: intent?.getCharSequenceExtra(CaptureRequests.EXTRA_PROCESS_TEXT),
            intent?.getCharSequenceExtra(CaptureRequests.EXTRA_SUBJECT),
            intent?.getBooleanExtra(CaptureRequests.EXTRA_VOICE, false) == true,
            voiceNote = intent?.getBooleanExtra(CaptureRequests.EXTRA_VOICE_NOTE, false) == true,
            hasPicture = sharedPicture() != null,
        )
        if (request == null) { finish(); return }
        // Screenshots and photos, slice 2: opened from a "Screenshot · looks like …" notification, which goes now.
        ScreenshotWatch.opened(this, intent)
        val picture = if (request.picture) sharedPicture() else null
        enableEdgeToEdge()
        val core = (application as MekaApplication).core
        // MEKA follows its own Motion setting, not the phone's animator scale (MotionClock.kt).
        setContent(parent = mekaRecomposer()) {
            MekaTheme {
                CaptureSheet(
                    request = request,
                    // First launch only: rotation or unfolding must not re-open the recogniser.
                    listenOnOpen = request.startVoice && savedInstanceState == null,
                    // Voice notes, slice 1: Talk's "take a voice note" opens the sheet recording (first launch only).
                    noteOnOpen = request.voiceNote && savedInstanceState == null,
                    // Screenshots and photos, slice 1: a shared picture (or a letter photographed here) read on the phone.
                    picture = picture,
                    readPicture = { uri -> PictureReader.read(this@CaptureActivity, uri) },
                    cameraUri = { PictureReader.cameraUri(this@CaptureActivity) },
                    forgetCameraPhoto = { PictureReader.forgetCameraPhoto(this@CaptureActivity) },
                    save = { text -> core.capture(text, null) != null },
                    // "The mic should just add it", slice 2: what he says on an empty field goes in without ↑ Add.
                    spoken = { heard -> core.spokenCapture(heard) },
                    undoSpoken = { c -> core.spokenCaptureUndo(c) },
                    // The mic fix, step 3: end of his words → Added, for Activity's "Speech to Added" line (memory only).
                    heardTiming = { ms, engine -> core.recordHeardTiming(ms, engine) },
                    // Train-time queue, slice 1: a shared link can be saved for the train instead of becoming a task.
                    saveForTrain = { text, subject -> !core.addToQueue(text, subject).refused },
                    // Reading and learning, slice 3: a shared book, podcast or course offers Add to Reading (read here).
                    readingOffer = { text, subject -> core.readingFromShared(text, subject) },
                    addToReading = { text, subject -> core.addReadingFromShared(text, subject).takeUnless { it.refused }?.line },
                    // "Handle this" (V1): the shared text goes to MEKA's AI on Meka's tap only; cards come back.
                    handle = { text, subject -> core.handleThis(text, subject) },
                    // Voice notes, slice 2: a note's words (never the recording) are tidied by the AI; cards come back.
                    tidyNote = { words, subject -> core.tidyVoiceNote(words, subject) },
                    // What MEKA knows, slice 4: a shared form's fields answered from the facts page, on the phone only.
                    formAnswers = { text, subject -> core.formAnswers(text, subject) },
                    // Car and parking, slice 5: a shared RingGo/JustPark confirmation offers its parking timer (read here).
                    parking = { text, subject -> core.parkingFromShared(text, subject) },
                    startParking = { carId, text, subject -> core.startParkingFromShared(carId, text, subject) },
                    // Screenshots and photos, slice 3: a picture (or text) of a passport, licence, GHIC… offers a Vault row.
                    vault = { text, subject -> core.documentFromShared(text, subject) },
                    addToVault = { text, subject -> core.addDocumentFromShared(text, subject)?.line },
                    copy = { text -> os.meka.android.work.LiveReplies.copyText(this@CaptureActivity, text, "Navilife fact") },
                    answer = { card, add ->
                        // A reply card's Copy reply: the draft goes on the clipboard; Meka sends it himself.
                        if (add) core.acceptRequest(card.id)?.let { done ->
                            done.copyText?.let { os.meka.android.work.LiveReplies.copyText(this@CaptureActivity, it) }
                            done.line
                        }
                        else if (core.declineRequest(card.id)) "${card.declineLabel} · ${card.proposal.title}" else null
                    },
                    done = { saved -> setResult(if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED); finish() },
                )
            }
        }
    }

    /** The picture a share carries (EXTRA_STREAM), if any; only ever read on the phone, never kept. */
    private fun sharedPicture(): android.net.Uri? = intent?.let {
        if (it.action != CaptureRequests.ACTION_SEND || it.type?.startsWith("image/") != true) null
        else androidx.core.content.IntentCompat.getParcelableExtra(it, CaptureRequests.EXTRA_STREAM, android.net.Uri::class.java)
    }

    override fun onStop() {
        super.onStop()
        // Voice notes, slice 1: leaving the sheet mid-recording stops and keeps the note (Android cuts an app's
        // microphone off in the background anyway); unfolding or rotating carries on recording.
        if (!isChangingConfigurations) VoiceNoteSessions.current?.stop()
    }

    override fun finish() {
        super.finish()
        // The sheet already animated itself away.
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

@Composable
private fun CaptureSheet(
    request: CaptureRequest,
    listenOnOpen: Boolean,
    noteOnOpen: Boolean,
    picture: android.net.Uri?,
    readPicture: suspend (android.net.Uri) -> PictureRead,
    cameraUri: () -> android.net.Uri,
    forgetCameraPhoto: () -> Unit,
    save: suspend (String) -> Boolean,
    spoken: suspend (String?) -> SpokenCaptured?,
    undoSpoken: suspend (SpokenCaptured) -> Boolean,
    heardTiming: suspend (Long, os.meka.core.domain.ListenEngine) -> Unit,
    saveForTrain: suspend (String, String?) -> Boolean,
    readingOffer: (String, String?) -> os.meka.core.domain.ReadingShareView?,
    addToReading: suspend (String, String?) -> String?,
    handle: suspend (String, String?) -> HandleOutcome,
    tidyNote: suspend (String, String) -> VoiceNoteRead,
    formAnswers: suspend (String, String?) -> FormAnswersView,
    parking: suspend (String, String?) -> ParkingShareView?,
    startParking: suspend (String, String, String?) -> String?,
    vault: suspend (String, String?) -> DocumentShareView?,
    addToVault: suspend (String, String?) -> String?,
    copy: (String) -> Unit,
    answer: suspend (RequestCard, Boolean) -> String?,
    done: (Boolean) -> Unit,
) {
    // Screenshots and photos, slice 1: the words read off a picture take the shared text's place (the picture is gone).
    var pictureText by rememberSaveable { mutableStateOf<String?>(null) }
    var pictureLine by rememberSaveable { mutableStateOf<String?>(null) }
    var pictureReading by remember { mutableStateOf(false) }
    val shared = remember(request, pictureText) {
        pictureText?.let { CaptureRequest(it, null, startVoice = false) } ?: request
    }
    val initial = remember(shared) { QuickCapture.draft(shared.text, shared.subject) }
    // A shared web link offers "For the train" (train-time queue, slice 1).
    val hasLink = remember(shared) { (QueueRules.link(shared.text) ?: QueueRules.link(shared.subject)) != null }
    // Reading and learning, slice 3: "📖 Add to Reading · Dune by Frank Herbert" for a shared book, podcast or course.
    val readingShare = remember(shared) { readingOffer(shared.text, shared.subject) }
    var doneLabel by rememberSaveable { mutableStateOf("Captured") }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    val notes = initial?.notes
    var captured by rememberSaveable { mutableStateOf(false) }
    // The mic fix, step 3: why the 🎙 couldn't listen (no signal and no offline speech, the microphone refused…).
    var listenProblem by remember { mutableStateOf<String?>(null) }
    // What was in the field when the 🎙 started listening; his words show after it as he speaks.
    var listenBase by remember { mutableStateOf("") }
    // Slice 2: what the 🎙 just added, shown with Undo and Edit until the sheet drops away.
    var spokenAdded by remember { mutableStateOf<SpokenCaptured?>(null) }
    var spokenLine by remember { mutableStateOf<String?>(null) }
    // "Handle this" (V1, slice 1): offered for shared text with words in it (never for the widget or tile).
    val canHandle = remember(shared) { HandleThisRules.worthAsking(shared.text, shared.subject) }
    var handling by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var handleLine by remember { mutableStateOf<String?>(null) }
    var handleCards by remember { mutableStateOf<List<RequestCard>>(emptyList()) }
    var answered by remember { mutableStateOf<Set<String>>(emptySet()) }
    var answers by remember { mutableStateOf(FormFillRules.EMPTY) }
    var copiedLine by remember { mutableStateOf<String?>(null) }
    // Car and parking, slice 5: read on the phone when the sheet opens; nothing starts until he taps it.
    var parkingOffer by remember { mutableStateOf<ParkingShareView?>(null) }
    var pickingCar by remember { mutableStateOf(false) }
    // Screenshots and photos, slice 3: a document's name and expiry, read here; nothing is written until he taps it.
    var vaultOffer by remember { mutableStateOf<DocumentShareView?>(null) }
    // Voice notes, slice 1: holding the mic records a voice note; it survives unfolding (the session lives outside the sheet).
    val context = LocalContext.current
    var noting by rememberSaveable { mutableStateOf(false) }
    var session by remember { mutableStateOf(VoiceNoteSessions.current) }
    val idleNote = remember { kotlinx.coroutines.flow.MutableStateFlow<VoiceNoteState>(VoiceNoteState.Idle) }
    val noteState by (session?.state ?: idleNote).collectAsState()
    var micAsks by remember { mutableStateOf(0) }
    val levels = remember { mutableStateListOf<Float>() }
    var heard by rememberSaveable { mutableStateOf<String?>(null) }
    // Voice notes, slice 2: the AI's tidy note and who it mentions, shown over his own words once it answers.
    var tidied by rememberSaveable { mutableStateOf<String?>(null) }
    var mentions by rememberSaveable { mutableStateOf<String?>(null) }
    var noteMessage by remember { mutableStateOf<String?>(null) }
    // The camera's scratch file for Photo of a letter (kept across the camera app taking over the screen).
    var lastCameraUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val rise = with(density) { 160.dp.toPx() }
    // 0 = below the screen, 1 = in place. Reduced motion: fades instead of moving.
    val shown = remember { Animatable(0f) }

    suspend fun leave(saved: Boolean) {
        shown.animateTo(0f, MekaMotion.expand(reduced))
        done(saved)
    }

    // The mic fix, step 3: the words he said (the field showed them as he spoke). On an empty field they go straight in.
    fun heardWords(heard: String, engine: os.meka.core.domain.ListenEngine, endedAt: Long) {
        val base = listenBase
        val words = CaptureRequests.spoken(arrayListOf(heard))
        when {
            words == null -> { title = base; runCatching { focus.requestFocus() } }
            // Something already in the field (typed or shared): the words join it and ↑ Add saves, as before.
            base.isNotBlank() -> { title = "$base $words"; runCatching { focus.requestFocus() } }
            else -> scope.launch {
                title = words
                val c = spoken(words)
                if (c == null || c.fill) {
                    // Ask first (or paused, or only day words): the words wait in the field for ↑ Add.
                    title = c?.words ?: words
                    runCatching { focus.requestFocus() }
                } else {
                    heardTiming(android.os.SystemClock.elapsedRealtime() - endedAt, engine)
                    keyboard?.hide()
                    title = ""
                    spokenLine = null
                    spokenAdded = c
                    haptics.light()
                }
            }
        }
    }

    val listener = remember {
        CaptureListener(
            context, scope,
            onHeard = { words, engine, endedAt -> heardWords(words, engine, endedAt) },
            onNothing = { line ->
                title = listenBase
                listenProblem = line
                runCatching { focus.requestFocus() }
            },
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { listener.release() } }
    // His words in the field as he speaks.
    LaunchedEffect(listener.partial) {
        if (listener.listening && listener.partial.isNotBlank()) title = "$listenBase ${listener.partial}".trim()
    }

    fun undoSpokenAdd(edit: Boolean) {
        val c = spokenAdded ?: return
        spokenAdded = null
        haptics.tick()
        scope.launch {
            undoSpoken(c)
            if (edit) { title = c.words; keyboard?.show() } else spokenLine = SpokenCaptureRules.UNDONE
            runCatching { focus.requestFocus() }
        }
    }

    // Slice 2: "Added …" stays a few seconds with Undo and Edit, then the sheet drops away as after ↑ Add.
    LaunchedEffect(spokenAdded) {
        val c = spokenAdded ?: return@LaunchedEffect
        delay(SpokenCaptureRules.SHOW_MS)
        if (spokenAdded === c) leave(true)
    }
    fun listenNow() {
        listenProblem = null
        spokenLine = null
        listenBase = title
        keyboard?.hide()
        haptics.tick()
        listener.start()
    }

    val askListenMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenNow() else listenProblem = os.meka.core.domain.TalkProblem.NO_PERMISSION.line
    }

    // The 🎙 tapped: listen in the sheet (tapped again while listening: take what he said so far).
    fun startListening() {
        if (captured || handling) return
        if (listener.listening) { listener.finishNow(); return }
        if (!VoiceNoteRecorder.micAllowed(context)) { askListenMic.launch(android.Manifest.permission.RECORD_AUDIO); return }
        listenNow()
    }

    fun startNote() {
        if (captured || handling) return
        val app = context.applicationContext as? MekaApplication ?: return
        keyboard?.hide()
        noteMessage = null
        levels.clear()
        val s = VoiceNoteSession(app, app.appScope, System::currentTimeMillis)
        VoiceNoteSessions.current = s
        session = s
        noting = true
        s.start()
        if (s.recording) haptics.light()
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        micAsks += 1
        // Refused: the session says the microphone is needed, with Allow.
        startNote()
    }

    fun holdMic() {
        if (captured || handling) return
        if (!VoiceNoteRecorder.micAllowed(context)) { haptics.tick(); askMic.launch(android.Manifest.permission.RECORD_AUDIO); return }
        startNote()
    }

    fun add() {
        if (captured || title.isBlank()) return
        scope.launch {
            if (save(CaptureRequests.compose(title, notes))) {
                keyboard?.hide()
                captured = true
                haptics.light()
                delay(if (reduced) 250 else 520)
                leave(true)
            }
        }
    }

    fun forTheTrain() {
        if (captured) return
        scope.launch {
            if (saveForTrain(shared.text, shared.subject)) {
                keyboard?.hide()
                doneLabel = "Saved for the train"
                captured = true
                haptics.light()
                delay(if (reduced) 250 else 520)
                leave(true)
            }
        }
    }

    fun toReading() {
        if (captured) return
        scope.launch {
            val line = addToReading(shared.text, shared.subject) ?: return@launch
            keyboard?.hide()
            doneLabel = line
            captured = true
            haptics.light()
            delay(if (reduced) 400 else 900) // long enough to read where it went
            leave(true)
        }
    }

    fun startTimer(carId: String) {
        if (captured) return
        scope.launch {
            val line = startParking(carId, shared.text, shared.subject) ?: return@launch
            keyboard?.hide()
            doneLabel = line
            captured = true
            haptics.light()
            delay(if (reduced) 400 else 900) // the reminder time stays long enough to read
            leave(true)
        }
    }

    fun vaultTapped() {
        val offer = vaultOffer ?: return
        if (offer.already || captured) return
        scope.launch {
            val line = addToVault(shared.text, shared.subject) ?: return@launch
            keyboard?.hide()
            doneLabel = line
            captured = true
            haptics.light()
            delay(if (reduced) 400 else 900) // the reminder date stays long enough to read
            leave(true)
        }
    }

    fun parkingTapped() {
        val offer = parkingOffer ?: return
        if (offer.ended) return
        val car = offer.carId
        if (car != null) startTimer(car) else { haptics.tick(); pickingCar = !pickingCar }
    }

    fun handleIt() {
        if (captured || handling) return
        keyboard?.hide()
        haptics.light()
        handling = true
        reading = true
        scope.launch {
            // Worked out on the phone before MEKA's AI answers (and even when it can't).
            answers = formAnswers(shared.text, shared.subject)
            val out = handle(shared.text, shared.subject)
            when (out) {
                is HandleOutcome.Cards -> { handleCards = out.cards; handleLine = out.line }
                is HandleOutcome.NothingToDo -> handleLine = out.line
                is HandleOutcome.Unavailable -> handleLine = out.line
            }
            reading = false
        }
    }

    fun answerCard(card: RequestCard, choice: RequestChoice) {
        if (card.id in answered) return
        scope.launch {
            val line = answer(card, choice == RequestChoice.ADD) ?: return@launch
            answered = answered + card.id
            handleLine = line
            // The form's answers keep the sheet open: Meka is still copying from it.
            if (handleCards.all { it.id in answered } && answers.answers.isEmpty()) {
                doneLabel = HandleThisRules.DONE_LINE
                delay(if (reduced) 400 else 900) // the last line stays long enough to read
                captured = true
                haptics.medium()
                delay(if (reduced) 250 else 520)
                leave(true)
            }
        }
    }

    LaunchedEffect(shared) {
        if (shared.text.isNotBlank()) parkingOffer = parking(shared.text, shared.subject)
        if (shared.text.isNotBlank()) vaultOffer = vault(shared.text, shared.subject)
    }

    // Screenshots and photos, slice 1: read the picture on the phone; its words fill the field (first line) and notes.
    suspend fun read(uri: android.net.Uri, source: PictureTextRules.Source) {
        pictureReading = true
        keyboard?.hide()
        when (val r = readPicture(uri)) {
            is PictureRead.Words -> {
                pictureText = r.text
                title = QuickCapture.draft(r.text, null)?.title.orEmpty()
                pictureLine = PictureTextRules.readLine(r.text, source)
                haptics.light()
            }
            is PictureRead.NoWords -> { pictureLine = r.line; haptics.reject() }
        }
        pictureReading = false
        if (pictureText == null) { runCatching { focus.requestFocus() }; keyboard?.show() }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = lastCameraUri
        lastCameraUri = null
        scope.launch {
            if (ok && uri != null) read(uri, PictureTextRules.Source.CAMERA)
            forgetCameraPhoto() // read and gone, or never taken
        }
    }
    fun photographLetter() {
        if (captured || handling || pictureReading) return
        haptics.light()
        val uri = cameraUri()
        lastCameraUri = uri
        try { takePhoto.launch(uri) } catch (_: ActivityNotFoundException) { pictureLine = PictureTextRules.NO_CAMERA_LINE }
    }

    // The voice note's progress: the level strip follows the voice; once kept, its words go to Handle this.
    LaunchedEffect(noteState) {
        when (val st = noteState) {
            is VoiceNoteState.Recording -> { levels.add(st.level); while (levels.size > LEVEL_BARS) levels.removeAt(0) }
            is VoiceNoteState.Done -> {
                VoiceNoteSessions.current = null
                val note = st.note
                haptics.light()
                heard = if (st.noWords == null) note.transcript else null
                handling = true
                if (VoiceNoteRules.worthAsking(note)) {
                    reading = true
                    val minute = java.time.Instant.ofEpochMilli(note.atMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                        .let { it.hour * 60 + it.minute }
                    val read = tidyNote(note.transcript, VoiceNoteRules.subject(minute))
                    tidied = read.tidy.ifEmpty { null }
                    mentions = VoiceNoteRules.peopleLine(read.people)
                    val out = read.cards
                    val count = when (out) {
                        is HandleOutcome.Cards -> { handleCards = out.cards; handleLine = out.line; out.cards.size }
                        is HandleOutcome.NothingToDo -> { handleLine = out.line; 0 }
                        // Offline or the AI is off: Make cards on the note in Voice notes later.
                        is HandleOutcome.Unavailable -> { handleLine = out.line + " · " + VoiceNoteRules.MAKE_CARDS.lowercase() + " in Voice notes later"; null }
                    }
                    if (count != null || read.tidy.isNotEmpty()) VoiceNoteStore.update(context, note.id) { n ->
                        n.copy(cards = count ?: n.cards, tidy = read.tidy.ifEmpty { n.tidy }, people = read.people.ifEmpty { n.people })
                    }
                    reading = false
                } else {
                    handleLine = st.noWords ?: HandleThisRules.NOTHING_LINE
                }
            }
            VoiceNoteState.Cancelled -> { VoiceNoteSessions.current = null; noting = false; runCatching { focus.requestFocus() } }
            VoiceNoteState.TooShort, VoiceNoteState.CouldNotStart -> {
                VoiceNoteSessions.current = null
                haptics.reject()
                delay(if (reduced) 1_200 else 1_800) // the line stays long enough to read
                noteMessage = if (st == VoiceNoteState.TooShort) VoiceNoteRules.TOO_SHORT else "The microphone is busy · try again in a moment"
                noting = false
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        shown.animateTo(1f, MekaMotion.expand(reduced))
        if (captured) { leave(true); return@LaunchedEffect } // rotated or unfolded mid-goodbye
        when {
            noting -> {} // unfolded mid-note: the session carries on
            picture != null && pictureText == null && pictureLine == null -> read(picture, PictureTextRules.Source.SHARED)
            request.picture -> {} // already read (rotated or unfolded): the field stays as it was
            noteOnOpen -> holdMic()
            listenOnOpen -> startListening()
            else -> { runCatching { focus.requestFocus() }; keyboard?.show() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { scope.launch { leave(false) } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .graphicsLayer {
                    if (!reduced) translationY = (1f - shown.value) * rise
                    alpha = shown.value
                }
                .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                .background(Meka.colors.surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) {} // taps inside don't dismiss
                .navigationBarsPadding()
                .padding(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            AnimatedContent(
                // 0 the field, 1 Handle this, 2 the goodbye tick, 3 a voice note recording, 4 reading a picture, 5 what
                // the 🎙 added with Undo and Edit: each step cross-fades (the ticks also pop in).
                targetState = when { captured -> 2; spokenAdded != null -> 5; handling -> 1; noting -> 3; pictureReading -> 4; else -> 0 },
                transitionSpec = {
                    if (targetState == 2 || targetState == 5) {
                        (fadeIn(MekaMotion.appear(reduced)) + (if (reduced) fadeIn() else scaleIn(MekaMotion.approve(false), 0.6f)))
                            .togetherWith(fadeOut(MekaMotion.appear(reduced)))
                    } else {
                        // The field gives way to Handle this; the sheet's height follows on the expand spring.
                        fadeIn(MekaMotion.appear(reduced)).togetherWith(fadeOut(MekaMotion.appear(reduced)))
                            .using(SizeTransform(clip = false) { _, _ -> MekaMotion.expand(reduced) })
                    }
                },
                label = "captured",
            ) { step ->
                if (step == 5) {
                    val c = spokenAdded
                    if (c != null) SpokenAddedView(c, onUndo = { undoSpokenAdd(edit = false) }, onEdit = { undoSpokenAdd(edit = true) })
                } else if (step == 2) {
                    Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.m), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Meka.colors.success), contentAlignment = Alignment.Center) {
                            Text("✓", color = Meka.colors.background, style = MekaType.itemMeta)
                        }
                        Spacer(Modifier.width(MekaSpace.xs))
                        Text(doneLabel, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    }
                } else if (step == 4) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text("PICTURE", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
                        Text(PictureTextRules.READING_LINE, style = MekaType.body, color = Meka.colors.textSecondary)
                        os.meka.android.designsystem.SkeletonRows(count = 2, rowHeight = 18.dp)
                    }
                } else if (step == 3) {
                    VoiceNoteRecordingView(
                        noteState, levels,
                        onPause = { haptics.tick(); session?.pause() },
                        onResume = { haptics.tick(); session?.resume() },
                        onDone = { haptics.light(); session?.stop() },
                        onCancel = { haptics.tick(); session?.cancel() },
                        onAllowMic = {
                            // Android stops asking after two refusals: then Navilife's app page, where Microphone is.
                            if (micAsks >= 2) HelpSettingsLink.appDetails(context)
                            else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                    )
                } else if (step == 1) {
                    HandleThisSection(
                        reading, handleLine, handleCards, answered, onChoice = ::answerCard,
                        heard = heard, voiceNote = noting, tidied = tidied, mentions = mentions,
                        onDone = if (noting) ({ scope.launch { leave(true) } }) else null,
                        answers = answers, copiedLine = copiedLine,
                        onCopy = { a -> copy(a.copyText); haptics.light(); copiedLine = FormFillRules.copiedLine(a) },
                        onCopyAll = { copy(answers.copyAllText); haptics.light(); copiedLine = FormFillRules.copiedAllLine(answers) },
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text("CAPTURE", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MekaRadius.pill))
                                    .background(Meka.colors.surfaceRaised)
                                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
                            ) {
                                if (title.isEmpty()) Text("Capture anything…", style = MekaType.body, color = Meka.colors.textTertiary)
                                BasicTextField(
                                    value = title,
                                    onValueChange = { title = it.replace('\n', ' ') },
                                    singleLine = true,
                                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                                    cursorBrush = SolidColor(Meka.colors.accent),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { add() }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Capture" },
                                )
                            }
                            Spacer(Modifier.width(MekaSpace.xs))
                            RoundButton(
                                "🎙", if (listener.listening) "Done speaking" else SpokenCaptureRules.MIC_LABEL,
                                if (listener.listening) Meka.colors.accent else Meka.colors.surfaceRaised,
                                if (listener.listening) Meka.colors.onAccent else Meka.colors.textPrimary,
                                onLongClick = ::holdMic,
                            ) { startListening() }
                            Spacer(Modifier.width(MekaSpace.xs))
                            RoundButton("↑", "Add", if (title.isBlank()) Meka.colors.hairline else Meka.colors.accent, Meka.colors.onAccent) { add() }
                        }
                        // The mic fix, step 3: which speech is listening, with a dot that breathes with his voice.
                        AnimatedVisibility(
                            listener.listening,
                            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                        ) {
                            ListeningLine(listener.engine, listener.resting, listener.level, reduced)
                        }
                        val offer = parkingOffer
                        AnimatedVisibility(
                            offer != null,
                            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                        ) {
                            if (offer != null) ParkingOfferRow(offer, pickingCar, ::parkingTapped, ::startTimer)
                        }
                        val doc = vaultOffer
                        AnimatedVisibility(
                            doc != null,
                            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                        ) {
                            if (doc != null) VaultOfferRow(doc, ::vaultTapped)
                        }
                        if (readingShare != null) {
                            Text(
                                readingShare.icon + " " + readingShare.line, style = MekaType.itemMeta, color = Meka.colors.accent,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                    .clickable(role = Role.Button) { toReading() }
                                    .semantics { contentDescription = readingShare.line + ", up next in Ask, Daily, Reading" }
                                    .padding(vertical = MekaSpace.xs),
                            )
                        }
                        if (hasLink) {
                            Text(
                                "🚆 " + QueueRules.ACTION, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                    .clickable(role = Role.Button) { forTheTrain() }
                                    .semantics { contentDescription = "Save for the train instead" }
                                    .padding(vertical = MekaSpace.xs),
                            )
                        }
                        if (canHandle) {
                            Text(
                                "✦ " + HandleThisRules.ACTION, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                    .clickable(role = Role.Button) { handleIt() }
                                    .semantics { contentDescription = "Handle this: Navilife reads it and offers what to add" }
                                    .padding(vertical = MekaSpace.xs),
                            )
                        }
                        if (notes != null) {
                            Text("Kept in notes: $notes", style = MekaType.caption, color = Meka.colors.textSecondary,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        pictureLine?.let {
                            Text(it, style = MekaType.caption, color = Meka.colors.textTertiary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        val message = noteMessage ?: spokenLine
                        if (message != null) {
                            Text(message, style = MekaType.caption, color = Meka.colors.textSecondary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        } else if (title.isEmpty() && shared.text.isBlank()) {
                            Text(SpokenCaptureRules.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                        // Photo of a letter: on a sheet opened empty (widget, tile, Today's capture), the camera app
                        // takes a letter, ticket or sign and Navilife reads it here.
                        if (request.text.isBlank() && request.subject == null && pictureText == null) {
                            Text(
                                "📷 " + PictureTextRules.PHOTO_ACTION, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                    .clickable(role = Role.Button) { photographLetter() }
                                    .semantics { contentDescription = PictureTextRules.PHOTO_LABEL }
                                    .padding(vertical = MekaSpace.xs),
                            )
                            // Slice 2: new screenshots noticed by themselves (off until turned on here).
                            ScreenshotSwitchLine(onLine = { pictureLine = it })
                        }
                        listenProblem?.let {
                            Text(it, style = MekaType.caption, color = Meka.colors.textSecondary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The mic fix, step 3: "Listening · Google's speech" (or "· on this phone, no signal", or "· on this phone · Google's
 * speech is resting" after it failed) under the field while the 🎙
 * listens, after a brass dot that grows with his voice (still with reduced motion).
 */
@Composable
internal fun ListeningLine(engine: os.meka.core.domain.ListenEngine?, resting: Boolean, level: Float, reduced: Boolean) {
    val line = os.meka.core.domain.ListenRules.listeningLine(engine ?: os.meka.core.domain.ListenEngine.ONLINE, os.meka.core.domain.ListenRules.GOOGLE, resting = resting)
    Row(
        Modifier.padding(vertical = MekaSpace.xs).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = line },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val scale = if (reduced) 1f else 0.7f + 0.6f * level.coerceIn(0f, 1f)
        Box(
            Modifier.size(8.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(Meka.colors.accent),
        )
        Spacer(Modifier.width(MekaSpace.xs))
        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

/**
 * "The mic should just add it", slice 2: "✓ Added “Buy milk” · Tomorrow" with Undo and Edit (brass pills) for a few
 * seconds after the 🎙; read out once by a screen reader.
 */
@Composable
private fun SpokenAddedView(c: SpokenCaptured, onUndo: () -> Unit, onEdit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xs)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = SpokenCaptureRules.spoken(c) },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Text("CAPTURE", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Meka.colors.success), contentAlignment = Alignment.Center) {
                Text("✓", color = Meka.colors.background, style = MekaType.itemMeta)
            }
            Spacer(Modifier.width(MekaSpace.xs))
            Text(c.line, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            listOf(SpokenCaptureRules.UNDO to onUndo, SpokenCaptureRules.EDIT to onEdit).forEach { (label, tap) ->
                Text(
                    label, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.Button, onClick = tap)
                        .semantics { contentDescription = if (label == SpokenCaptureRules.EDIT) "Edit: take it back and change the words" else "Undo: take it back" }
                        .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(glyph: String, label: String, fill: Color, ink: Color, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(fill)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Record a voice note" else null)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MekaType.itemTitle, color = ink) }
}

/**
 * Screenshots and photos, slice 3: "🗂 Add to the Vault · Alex passport · expires Thu 20 May 2027" (brass, a button;
 * "Update the Vault · …" for a renewed one), or the quiet "Already in the Vault · …" line.
 */
@Composable
private fun VaultOfferRow(offer: DocumentShareView, onTap: () -> Unit) {
    val line = DocumentShareRules.ICON + " " + offer.line
    if (offer.already) {
        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
    } else {
        Text(
            line, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = offer.line + " · only its name and date are kept, never its number or the picture" }
                .padding(vertical = MekaSpace.xs),
        )
    }
}

/**
 * Car and parking, slice 5: "🅿 Parking timer until 15:30 · RingGo 81234" for a shared parking confirmation (brass, a
 * button), or the quiet "That parking ended at 15:30 …" line. With two cars and no registration in the text, the tap
 * unfolds the cars as chips ("Which car?") on the expand spring; a chip starts it.
 */
@Composable
private fun ParkingOfferRow(offer: ParkingShareView, picking: Boolean, onTap: () -> Unit, onCar: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        if (offer.ended) {
            Text("🅿 " + offer.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        } else {
            Text(
                "🅿 " + offer.line, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button, onClick = onTap)
                    .semantics { contentDescription = "Start a parking timer: ${offer.line}" }
                    .padding(vertical = MekaSpace.xs),
            )
        }
        AnimatedVisibility(
            picking && offer.choices.isNotEmpty(),
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(ParkingConfirmationRules.WHICH_CAR, style = MekaType.caption, color = Meka.colors.textTertiary)
                offer.choices.forEach { (id, title) ->
                    Text(
                        title, style = MekaType.caption, color = Meka.colors.textPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) { onCar(id) }
                            .semantics { contentDescription = "Start it on $title" }
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    )
                }
            }
        }
    }
}

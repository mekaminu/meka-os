package os.meka.android.today

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.MailLinkCheck
import os.meka.core.domain.MailReaderLinks
import os.meka.core.domain.MailReaderRules
import os.meka.core.domain.MailReaderView

/** One answer at the reader's foot: the same ones the email's card offers. */
internal class MailReaderAction(val label: String, val spoken: String, val critical: Boolean = false, val onClick: () -> Unit)

/**
 * Read the full email inside Navilife (2026-10-20), slice 1: the reader. It rises from the email he tapped (a spring up
 * and a slight grow, the screen behind dimmed; reduced motion: a cross-fade) and shows who it's from with the address,
 * To and Cc, when and the account, the subject, the whole email, its attachments by name and size, and the card's own
 * answers at the foot. The email is untrusted (ADR-006): it's drawn in a WebView with JavaScript, file and content access
 * off, no network at all until Show images (then pictures only, the page's CSP allowing nothing else), and a tapped link
 * never opens by itself: a panel says where it really goes, and only Open hands it to the browser. A plain-text email (or
 * one that couldn't be read) is shown as selectable text. Reading it doesn't mark it read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MailReaderSheet(
    load: suspend (images: Boolean) -> MailReaderView,
    actions: List<MailReaderAction>,
    openLabel: String? = null,
    onOpenInMail: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    var view by remember { mutableStateOf<MailReaderView?>(null) }
    var images by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<MailLinkCheck?>(null) }
    var linkLine by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    LaunchedEffect(Unit) { rise.animateTo(1f, MekaMotion.appear(reduced)) }
    LaunchedEffect(images) { view = runCatching { load(images) }.getOrNull() ?: view }
    val close: () -> Unit = { scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose() } }
    fun act(a: MailReaderAction) { scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose(); a.onClick() } }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = rise.value
                    if (!reduced) {
                        translationY = (1f - rise.value) * 48.dp.toPx()
                        val s = 0.94f + 0.06f * rise.value
                        scaleX = s; scaleY = s
                    }
                }
                .background(Meka.colors.background)
                .safeDrawingPadding()
                .padding(horizontal = MekaSpace.m)
                .testTag("mail-reader"),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(view?.account?.let { "EMAIL · ${it.uppercase()}" } ?: "EMAIL", Modifier.weight(1f))
                Text(
                    MailReaderRules.CLOSE, style = MekaType.metaStrong, color = Meka.colors.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .clickable(role = Role.Button) { haptics.light(); close() }
                        .semantics { contentDescription = "Close the email" }
                        .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
                )
            }
            val v = view
            if (v == null) {
                Text(MailReaderRules.LOADING, style = MekaType.body, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.m))
                Box(Modifier.weight(1f))
            } else {
                // Who, when and what, as one block for TalkBack.
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = v.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(v.subject, style = MekaType.nowTitle, color = Meka.colors.textPrimary, maxLines = 3)
                    Text(v.from, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
                    v.fromAddress?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
                    v.toLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 2) }
                    v.ccLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 2) }
                    Text("${v.whenLine} · ${v.account}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                }
                v.problem?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical, modifier = Modifier.testTag("mail-reader-problem")) }
                // "2 pictures hidden to stop tracking" with Show images; it folds away once shown.
                AnimatedVisibility(v.canShowImages, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(v.imagesLine.orEmpty(), style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f, fill = false))
                        MailNeedsPill(MailReaderRules.SHOW_IMAGES, spoken = "Show this email's pictures") { haptics.light(); images = true }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))) {
                    val page = v.page
                    if (page != null) {
                        MailWebView(page, images) { url ->
                            haptics.tick()
                            val c = MailReaderLinks.check(url)
                            link = c
                            linkLine = if (c == null) MailReaderLinks.NOT_OPENED else null
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                v.text, style = MekaType.body, color = Meka.colors.textPrimary,
                                modifier = Modifier.fillMaxSize().background(Meka.colors.surfaceRaised).verticalScroll(rememberScrollState())
                                    .padding(MekaSpace.m).testTag("mail-reader-text"),
                            )
                        }
                    }
                }
                linkLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                // The link he tapped: where it really goes, and Open only on his tap (expand + fade; reduced: fade).
                AnimatedVisibility(link != null, enter = unfold(), exit = fold()) {
                    val l = link
                    if (l != null) Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                            .padding(MekaSpace.m).testTag("mail-reader-link"),
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    ) {
                        Text(l.title, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
                        Text(l.shown, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 4)
                        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            MailNeedsPill(l.action, spoken = "${l.action}: ${l.shown}") {
                                haptics.light()
                                runCatching { uri.openUri(l.url) }
                                link = null
                            }
                            MailNeedsPill(MailReaderLinks.CANCEL, quiet = true, spoken = "Don't open the link") { haptics.tick(); link = null }
                        }
                    }
                }
                if (v.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs), modifier = Modifier.testTag("mail-reader-files")) {
                        SectionLabel(v.attachmentsLine.orEmpty().uppercase())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            v.attachments.forEach { a ->
                                Column(
                                    Modifier.clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)
                                        .clickable(role = Role.Button) { haptics.tick(); linkLine = MailReaderRules.ATTACHMENT_OPEN }
                                        .semantics(mergeDescendants = true) { contentDescription = "${a.name}, ${a.meta}" }
                                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                                ) {
                                    Text(a.name, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 1)
                                    Text(a.meta, style = MekaType.caption, color = Meka.colors.textTertiary)
                                }
                            }
                        }
                    }
                }
                // The card's own answers, then Open in Gmail/Outlook as the quieter way out.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    actions.forEach { a -> MailNeedsPill(a.label, spoken = a.spoken, critical = a.critical) { haptics.medium(); act(a) } }
                    if (openLabel != null && onOpenInMail != null) {
                        MailNeedsPill(openLabel, quiet = true, spoken = "$openLabel, ${v.subject}") { haptics.light(); onOpenInMail() }
                    }
                }
                AnimatedContent(v.note, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-reader-note") {
                    Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(bottom = MekaSpace.xs))
                }
            }
        }
    }
}

/**
 * The email's page: a WebView with JavaScript, files, content and storage off, network off (on for pictures only after
 * Show images; the page's CSP refuses everything else), and every navigation stopped and handed to [onLink].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MailWebView(page: String, images: Boolean, onLink: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize().testTag("mail-reader-web"),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(AndroidColor.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        onLink(request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { w ->
            w.settings.blockNetworkLoads = !images
            w.settings.blockNetworkImage = !images
            if (w.tag != page) {
                w.tag = page
                w.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
        onRelease = { w -> w.stopLoading(); w.destroy() },
    )
}

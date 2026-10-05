package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.ConnectedAccount
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Connected calendars. Connecting opens the provider's own sign-in page in the browser; MEKA OS never sees the
 * password, and the server keeps the resulting access (read-only) encrypted. The list refreshes whenever the app
 * comes back to the foreground, so it updates as soon as the owner returns from the browser.
 */
@Composable
fun CalendarsPane(core: MekaCore, onClose: () -> Unit) {
    var accounts by remember { mutableStateOf<List<ConnectedAccount>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(reload) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { accounts = core.connectedAccounts() }
    }

    fun connect(provider: String) = scope.launch {
        message = null
        when (val r = core.startConnect(provider)) {
            is ConnectStart.OpenBrowser -> runCatching { uri.openUri(r.url) }.onFailure { message = "Couldn't open the browser." }
            ConnectStart.NotSetUp -> message = "${providerLabel(provider)} isn't set up on your server yet. Finish the registration steps, then try again."
            is ConnectStart.Failed -> message = r.reason
        }
    }

    Column(Modifier.fillMaxSize().padding(MekaSpace.gutter), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Calendars", style = MekaType.greeting, color = Meka.colors.textPrimary)
        Text("Read-only. Events appear in Today on all your devices.", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        Spacer(Modifier.height(MekaSpace.m))

        when (val list = accounts) {
            null -> SkeletonRows(count = 2, rowHeight = 56.dp)
            else -> {
                if (list.isEmpty()) Text("No calendars connected yet.", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                list.forEachIndexed { i, a -> AccountRow(a, Modifier.appear(rememberAppearance(i)), onReconnect = { connect(a.provider) }) }
            }
        }
        Spacer(Modifier.height(MekaSpace.l))
        ConnectButton("Connect Google Calendar") { connect("google") }
        ConnectButton("Connect Outlook Calendar") { connect("microsoft") }
        message?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical) }
        Text("Refresh", style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.padding(top = MekaSpace.m).clickable(role = Role.Button) { reload++ })
    }
}

private val syncedFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AccountRow(a: ConnectedAccount, modifier: Modifier = Modifier, onReconnect: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m)) {
        Column(Modifier.weight(1f)) {
            Text(a.email, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            val status = when {
                a.needsReconnect -> "Access expired · tap Reconnect"
                a.status == "error" -> "Couldn't sync last time · retrying"
                a.lastSyncAtMs != null -> "${providerLabel(a.provider)} · synced " +
                    syncedFmt.format(Instant.ofEpochMilli(a.lastSyncAtMs!!).atZone(ZoneId.systemDefault()))
                else -> "${providerLabel(a.provider)} · first sync in progress"
            }
            Text(status, style = MekaType.caption, color = if (a.needsReconnect) Meka.colors.critical else Meka.colors.textTertiary)
        }
        if (a.needsReconnect) {
            Text("Reconnect", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onReconnect() }.padding(start = MekaSpace.m))
        }
    }
}

@Composable
private fun ConnectButton(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = Meka.colors.onAccent,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}

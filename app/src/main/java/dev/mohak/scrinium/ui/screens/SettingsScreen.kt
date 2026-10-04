package dev.mohak.scrinium.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.BuildConfig
import dev.mohak.scrinium.R
import dev.mohak.scrinium.data.remote.ApiTokenDto
import dev.mohak.scrinium.ui.ConfirmDialog
import dev.mohak.scrinium.ui.Divider
import dev.mohak.scrinium.ui.FillButton
import dev.mohak.scrinium.ui.GroupHeader
import dev.mohak.scrinium.ui.InputBox
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.MarkdownText
import dev.mohak.scrinium.ui.MonoFont
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.TextAction
import dev.mohak.scrinium.ui.TopBar
import dev.mohak.scrinium.ui.Type
import dev.mohak.scrinium.ui.relativeTime
import java.text.DateFormat
import java.util.Date

private enum class Page(val title: String) { Index("Settings"), Sync("Sync"), Devices("Devices"), Template("New note template"), Reminders("Reminders") }

/** Settings: a list with one status line per row, each opening its own page. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    var page by rememberSaveable { mutableStateOf(Page.Index) }
    BackHandler(enabled = page != Page.Index) { page = Page.Index }
    LaunchedEffect(Unit) {
        vm.loadDevices()
        vm.refreshTrash()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(page.title, onBack = { if (page == Page.Index) vm.closeSettings() else page = Page.Index })
        Box(Modifier.weight(1f)) {
            when (page) {
                Page.Index -> IndexPage(vm) { page = it }
                Page.Sync -> SyncPage(vm)
                Page.Devices -> DevicesPage(vm)
                Page.Template -> TemplatePage(vm)
                Page.Reminders -> RemindersPage(vm)
            }
        }
    }
}

@Composable
private fun IndexPage(vm: MainViewModel, open: (Page) -> Unit) {
    val email by vm.email.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val unsynced by vm.unsyncedCount.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val remindersOn by vm.remindersOn.collectAsStateWithLifecycle()
    val scheduledReminders by vm.scheduledReminders.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Sc.fill), contentAlignment = Alignment.Center) {
                Text(email?.firstOrNull()?.uppercase() ?: "?", style = Type.title.copy(color = Sc.onFill))
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(email ?: "", style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(BuildConfig.SCRINIUM_API_URL.substringAfter("://"), style = Type.meta)
            }
        }
        Divider()
        val failed = sync.report?.errors ?: 0
        PageRow(R.drawable.ms_sync, "Sync", buildList {
            add(
                when {
                    sync.syncing -> "Syncing"
                    sync.lastSyncAt != null -> "Synced ${relativeTime(sync.lastSyncAt!!)}"
                    else -> "Never synced"
                }
            )
            if (unsynced > 0) add("$unsynced waiting to upload")
            if (failed > 0) add("$failed failed")
        }.joinToString("  ·  ")) { open(Page.Sync) }
        PageRow(R.drawable.ms_devices, "Devices", if (devices.isEmpty()) "Phones signed in to your vault" else "${devices.size} signed in") { open(Page.Devices) }
        PageRow(R.drawable.ms_note_add, "New note template", template.lineSequence().firstOrNull { it.isNotBlank() }?.takeIf { "{{title}}" in template } ?: "# {{title}}  (default)") { open(Page.Template) }
        PageRow(R.drawable.ms_notifications, "Reminders", if (remindersOn) "On  ·  ${scheduledReminders.size} scheduled" else "Off") { open(Page.Reminders) }
        PageRow(R.drawable.ms_delete, "Trash", if (trash.isEmpty()) "Empty" else "${trash.size} item${if (trash.size == 1) "" else "s"}") { vm.openTrash() }
        Divider()
        PageRow(R.drawable.ms_info, "About", "Version ${BuildConfig.VERSION_NAME}", onClick = null)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { confirmSignOut = true }.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Sym(R.drawable.ms_logout, tint = Sc.red)
            Text("Sign out", style = Type.body.copy(color = Sc.red), modifier = Modifier.padding(start = 18.dp))
        }
    }
    if (confirmSignOut) {
        ConfirmDialog(
            "Sign out?",
            "Notes not yet synced stay on the phone and upload after you sign in again.",
            "Sign out",
            onConfirm = { vm.signOut() },
            onDismiss = { confirmSignOut = false }
        )
    }
}

@Composable
private fun PageRow(icon: Int, title: String, status: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Sym(icon, tint = Sc.text2)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(title, style = Type.body)
            Text(status, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Sym(R.drawable.ms_chevron_right, tint = Sc.text3, size = 18.dp)
    }
}

@Composable
private fun SyncPage(vm: MainViewModel) {
    val sync by vm.sync.collectAsStateWithLifecycle()
    val pending by vm.pendingNotes.collectAsStateWithLifecycle()
    val failures = sync.report?.failures.orEmpty()
    val healthy = !sync.syncing && failures.isEmpty() && pending.isEmpty() && sync.error == null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sym(
                    when {
                        sync.error != null || failures.isNotEmpty() -> R.drawable.ms_error
                        healthy -> R.drawable.ms_cloud_done_fill
                        else -> R.drawable.ms_cloud_upload
                    },
                    tint = if (sync.error != null || failures.isNotEmpty()) Sc.red else Sc.accent
                )
                Text(
                    when {
                        sync.syncing -> "Syncing"
                        sync.error != null -> "Last sync failed"
                        failures.isNotEmpty() -> "Some notes didn't sync"
                        pending.isNotEmpty() -> "Changes waiting to upload"
                        else -> "Up to date"
                    },
                    style = Type.title.copy(fontSize = 16.sp),
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Text(
                sync.lastSyncAt?.let { "last sync " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "never synced",
                style = Type.mono,
                modifier = Modifier.padding(top = 4.dp)
            )
            sync.error?.let { Text(it, style = Type.meta.copy(color = Sc.red), modifier = Modifier.padding(top = 4.dp)) }
        }
        FillButton(if (sync.syncing) "Syncing" else "Sync now", { vm.syncNow(force = true) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = !sync.syncing)
        if (pending.isNotEmpty()) {
            GroupHeader("Waiting to upload", "${pending.size}")
            pending.forEach { n ->
                Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Sym(if (n.isDeleted) R.drawable.ms_delete else R.drawable.ms_description, tint = Sc.text3, size = 17.dp)
                    Text(n.path.removeSuffix(".md"), style = Type.body.copy(color = if (n.isDeleted) Sc.text3 else Sc.text), textDecoration = if (n.isDeleted) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Text(relativeTime(n.localModifiedAt!!), style = Type.mono)
                }
            }
        }
        if (failures.isNotEmpty()) {
            GroupHeader("Failed", "${failures.size}", color = Sc.red)
            failures.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Sym(R.drawable.ms_error, tint = Sc.red, size = 17.dp)
                    Text(f, style = Type.meta.copy(color = Sc.text2), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        GroupHeader("Server")
        Text(BuildConfig.SCRINIUM_API_URL, style = Type.mono.copy(color = Sc.text2), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Text(
            "Sync runs only while the app is open: when it opens, every minute, and 5 seconds after you stop typing.",
            style = Type.meta,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun DevicesPage(vm: MainViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val current = vm.currentDeviceHash
    var revoking by remember { mutableStateOf<ApiTokenDto?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Phones signed in to your vault. Revoke one you no longer use; it is signed out on its next sync.",
            style = Type.meta.copy(color = Sc.text2),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp)
        )
        if (devices.isEmpty()) Text("No devices loaded. Check your connection.", style = Type.meta, modifier = Modifier.padding(20.dp))
        devices.forEachIndexed { i, d ->
            if (i > 0) Divider()
            val me = d.tokenHash == current
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Sym(R.drawable.ms_smartphone, tint = if (me) Sc.accent else Sc.text2)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(if (me) "This phone" else "Phone ${d.tokenHash.take(4)}", style = Type.body)
                    Text(
                        (d.lastUsedAt?.let { if (me) "active now" else "last used ${relativeTime(it)}" } ?: "never used") + "  ·  since " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.createdAt)),
                        style = Type.mono
                    )
                }
                if (!me) TextAction("Revoke", { revoking = d }, danger = true)
            }
        }
    }
    revoking?.let { d ->
        ConfirmDialog(
            "Revoke device?",
            "That phone will have to sign in again.",
            "Revoke",
            onConfirm = { vm.revokeDevice(d.tokenHash) },
            onDismiss = { revoking = null }
        )
    }
}

@Composable
private fun TemplatePage(vm: MainViewModel) {
    val saved by vm.template.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(saved) }
    fun set(v: String) {
        text = v
        vm.setTemplate(v)
    }
    val body = if ("{{title}}" in text) text.replace("{{title}}", "Untitled") else "# Untitled\n\n"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InputBox(
            text,
            { set(it) },
            "# {{title}}",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = false,
            minHeight = 150.dp,
            style = Type.body.copy(fontFamily = MonoFont, fontSize = 13.sp)
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextAction("Insert {{title}}", { set(text + "{{title}}") })
            TextAction("Reset", { set("") }, enabled = text.isNotEmpty())
        }
        Text(
            "Used for notes made on this phone when it contains {{title}}, which becomes the note's name. Otherwise a note starts with its name as a heading.",
            style = Type.meta,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        GroupHeader("Preview")
        Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) { MarkdownText(body) }
    }
}

@Composable
private fun RemindersPage(vm: MainViewModel) {
    val on by vm.remindersOn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheduled by vm.scheduledReminders.collectAsStateWithLifecycle()
    val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reminder notifications", style = Type.body)
                Text("A notification at each task's reminder time", style = Type.meta)
            }
            Switch(
                checked = on,
                onCheckedChange = { vm.setRemindersOn(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Sc.fill,
                    checkedThumbColor = Sc.onFill,
                    uncheckedTrackColor = Sc.press,
                    uncheckedThumbColor = Sc.text3,
                    uncheckedBorderColor = Sc.line3
                )
            )
        }
        if (on && !allowed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notifications are off for Scrinium in Android settings.", style = Type.meta.copy(color = Sc.orange), modifier = Modifier.weight(1f))
                TextAction("Open", {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                })
            }
        }
        if (on) {
            GroupHeader("Scheduled", "${scheduled.size}")
            if (scheduled.isEmpty()) Text("No upcoming reminders. Set one on a task.", style = Type.meta, modifier = Modifier.padding(16.dp))
            scheduled.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Sym(R.drawable.ms_alarm, tint = Sc.text3, size = 17.dp)
                    Text(r.title.ifBlank { "Task" }, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at)), style = Type.mono)
                }
            }
        }
    }
}

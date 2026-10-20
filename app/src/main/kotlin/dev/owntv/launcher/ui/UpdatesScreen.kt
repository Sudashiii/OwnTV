package dev.owntv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.update.ReleaseNotes
import dev.owntv.launcher.update.UpdateInfo
import dev.owntv.launcher.update.UpdateState
import dev.owntv.launcher.update.Updater

private val Muted = Color(0xFFA9AFBA)

/** Checks the GitHub releases for a newer OwnTV and installs it. */
@Composable
fun UpdatesScreen(container: AppContainer) {
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updater.checkIfStale() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.onResume() }

    SettingsScaffold("Updates", "Installed: OwnTV ${updater.installedVersionName}") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            // A new key per kind of state, so the button that replaces a focused one takes focus again.
            Column(Modifier.weight(1f)) {
                key(state::class) { UpdateStatus(state, updater) }
            }
            val notes = when (val s = state) {
                is UpdateState.UpToDate -> s.latest
                is UpdateState.Available -> s.update
                is UpdateState.NeedsPermission -> s.update
                is UpdateState.Downloading -> s.update
                is UpdateState.Installing -> s.update
                is UpdateState.Failed -> s.update
                else -> null
            }
            Column(Modifier.weight(1.2f)) {
                if (notes != null) ReleaseNotesView(notes)
            }
        }
    }
}

/** Shown in the Settings list under "Updates". */
fun updateSummary(state: UpdateState, updater: Updater): String = when (state) {
    UpdateState.Idle, UpdateState.Checking -> "OwnTV ${updater.installedVersionName}. Checking for a new version…"
    is UpdateState.UpToDate -> "OwnTV ${updater.installedVersionName} is up to date"
    is UpdateState.Available -> "OwnTV ${state.update.versionName} is available"
    is UpdateState.NeedsPermission -> "OwnTV ${state.update.versionName} is available"
    is UpdateState.Downloading -> "Downloading OwnTV ${state.update.versionName}…"
    is UpdateState.Installing -> "Installing OwnTV ${state.update.versionName}…"
    is UpdateState.Failed -> "OwnTV ${updater.installedVersionName}. " +
        if (state.update == null) "Could not check for updates" else "The update did not install"
}

@Composable
private fun UpdateStatus(state: UpdateState, updater: Updater) {
    when (state) {
        UpdateState.Idle, UpdateState.Checking -> {
            Title("Checking for updates…")
            Body("Looking for a new release on GitHub.")
        }
        is UpdateState.UpToDate -> {
            Title("OwnTV is up to date")
            Body("${state.latest.versionName} is the latest release.")
            Actions { OutlinedButton(onClick = updater::check, modifier = rememberInitialFocus()) { Text("Check again") } }
        }
        is UpdateState.Available -> {
            Title("OwnTV ${state.update.versionName} is available")
            Body(state.message ?: "Download it from GitHub and install it. Your layouts stay as they are.")
            Actions {
                Button(onClick = { updater.install(state.update) }, modifier = rememberInitialFocus()) { Text("Download and install") }
            }
        }
        is UpdateState.NeedsPermission -> {
            Title("Allow OwnTV to install updates")
            Body(
                "Android asks this once. In the next screen, turn on OwnTV under \"Install unknown apps\", " +
                    "then press Back. The update continues by itself.",
            )
            Actions {
                Button(onClick = { updater.openInstallPermission(state.update) }, modifier = rememberInitialFocus()) { Text("Open settings") }
            }
        }
        is UpdateState.Downloading -> {
            Title("Downloading OwnTV ${state.update.versionName}")
            Progress(state.fraction)
            Body(if (state.total > 0) "${megabytes(state.bytes)} of ${megabytes(state.total)}" else megabytes(state.bytes))
            Actions { OutlinedButton(onClick = updater::cancel, modifier = rememberInitialFocus()) { Text("Cancel") } }
        }
        is UpdateState.Installing -> {
            Title("Installing OwnTV ${state.update.versionName}")
            Body("Confirm the update on the screen Android shows. OwnTV restarts once it is installed.")
            Actions {
                OutlinedButton(onClick = { updater.install(state.update) }, modifier = rememberInitialFocus()) { Text("Install again") }
            }
        }
        is UpdateState.Failed -> {
            val update = state.update
            Title(if (update == null) "Could not check for updates" else "Could not install OwnTV ${update.versionName}")
            Body(state.message)
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
            Actions {
                Button(
                    onClick = { if (update == null) updater.check() else updater.install(update) },
                    modifier = rememberInitialFocus(),
                ) { Text("Try again") }
                // The release may have changed since the last check.
                if (update != null) OutlinedButton(onClick = updater::check) { Text("Check again") }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFDADDE3), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

@Composable
private fun Progress(fraction: Float?) {
    Box(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x33FFFFFF)),
    ) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
    }
}

/** Release notes, scrollable with the remote when they are long. */
@Composable
private fun ReleaseNotesView(update: UpdateInfo) {
    val sections = remember(update.notes) { ReleaseNotes.parse(update.notes) }
    if (sections.isEmpty()) return
    Column(Modifier.fillMaxWidth().focusable().verticalScroll(rememberScrollState())) {
        Text("What's new in ${update.versionName}", style = MaterialTheme.typography.titleLarge, color = Color.White)
        sections.forEach { section ->
            section.title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 16.dp))
            }
            section.items.forEach {
                Row(Modifier.padding(top = 6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.width(18.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFDADDE3))
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

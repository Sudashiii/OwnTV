package dev.owntv.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.update.ReleaseEntry
import dev.owntv.launcher.update.ReleaseNotes
import dev.owntv.launcher.update.ReleasesState
import dev.owntv.launcher.update.UpdateState
import dev.owntv.launcher.update.Updater

private val Muted = Color(0xFFA9AFBA)

/** Checks the GitHub releases for a newer OwnTV and installs it. */
@Composable
fun UpdatesScreen(container: AppContainer) {
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val releases by updater.releases.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updater.checkIfStale() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.onResume() }

    SettingsScaffold("Updates", "Installed: OwnTV ${updater.installedVersionName}") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            // A new key per kind of state, so the button that replaces a focused one takes focus again.
            Column(Modifier.weight(1f)) {
                key(state::class) { UpdateStatus(state, updater) }
            }
            Column(Modifier.weight(1.2f)) {
                VersionList(
                    releases = releases,
                    installedCode = updater.installedVersionCode,
                    onSelect = updater::install,
                )
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
                OutlinedButton(onClick = updater::check) { Text("Check again") }
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
            val retry = state.retry
            Title(
                when {
                    update != null -> "Could not install OwnTV ${update.versionName}"
                    retry != null -> "Could not load OwnTV ${retry.versionName}"
                    else -> "Could not check for updates"
                },
            )
            Body(state.message)
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
            Actions {
                Button(
                    onClick = {
                        when {
                            update != null -> updater.install(update)
                            retry != null -> updater.install(retry)
                            else -> updater.check()
                        }
                    },
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

/** Every release with its notes, newest first. Scroll with up and down; OK installs a newer version. */
@Composable
private fun VersionList(releases: ReleasesState, installedCode: Long, onSelect: (ReleaseEntry) -> Unit) {
    Text("Versions", style = MaterialTheme.typography.titleLarge, color = Color.White)
    when (releases) {
        ReleasesState.Idle, ReleasesState.Loading -> Body("Loading versions…")
        is ReleasesState.Failed -> Body("Could not load the version list. ${releases.message}")
        is ReleasesState.Loaded -> if (releases.releases.isEmpty()) {
            Body("No releases were found.")
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(releases.releases, key = { _, entry -> entry.versionCode }) { index, entry ->
                    VersionCard(entry, installedCode, latest = index == 0, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

@Composable
private fun VersionCard(entry: ReleaseEntry, installedCode: Long, latest: Boolean, onClick: () -> Unit) {
    val sections = remember(entry.notes) { ReleaseNotes.parse(entry.notes) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(12.dp)
    val tag = buildList {
        if (entry.versionCode == installedCode) add("Installed")
        if (latest) add("Latest")
    }.joinToString(" · ")
    val action = when {
        entry.versionCode == installedCode -> null
        entry.versionCode > installedCode -> "Press OK to update"
        else -> null
    }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x14FFFFFF),
            focusedContainerColor = Color(0x2EFFFFFF),
            contentColor = Color(0xFFDADDE3),
            focusedContentColor = Color(0xFFDADDE3),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)),
        // Long notes scroll inside the card first; at their end Down moves on to the next version.
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val down = event.key == Key.DirectionDown
                if (event.type != KeyEventType.KeyDown || (!down && event.key != Key.DirectionUp)) return@onPreviewKeyEvent false
                val canScroll = if (down) scroll.canScrollForward else scroll.canScrollBackward
                if (canScroll) scope.launch { scroll.animateScrollBy(if (down) 120f else -120f) }
                canScroll
            },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OwnTV ${entry.versionName}", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (tag.isNotEmpty()) Text(tag, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Column(Modifier.padding(top = 8.dp).heightIn(max = 240.dp).verticalScroll(scroll)) {
                if (sections.isEmpty()) {
                    Text("No notes.", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
                sections.forEach { section ->
                    section.title?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                    section.items.forEach {
                        Row(Modifier.padding(top = 6.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.width(18.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (scroll.canScrollForward) Text("More below", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
            if (action != null) Text(action, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

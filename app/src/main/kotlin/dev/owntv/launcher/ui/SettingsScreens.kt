package dev.owntv.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.actions.RemoteService
import dev.owntv.launcher.actions.StandbyService
import dev.owntv.launcher.data.layout.BuiltInKind
import dev.owntv.launcher.data.layout.BuiltInRef
import dev.owntv.launcher.data.layout.HistoryEntry
import dev.owntv.launcher.data.layout.LayoutImageLoader
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.update.UpdateState
import dev.owntv.layout.model.CURRENT_SCHEMA_VERSION
import dev.owntv.layout.validation.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator, state: LayoutState) {
    val scope = rememberCoroutineScope()
    val hasHistory by container.layouts.hasHistory.collectAsStateWithLifecycle()
    val problems = state.issues.count { it.severity != Severity.Info }
    val update by container.updater.state.collectAsStateWithLifecycle()
    val metricsMode by container.perf.mode.collectAsStateWithLifecycle()
    val builtInUpdate by container.layouts.builtInUpdate.collectAsStateWithLifecycle()
    val standbyOn by StandbyService.enabled.collectAsStateWithLifecycle()
    val remoteOn by RemoteService.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.updater.checkIfStale() }
    val available = (update as? UpdateState.Available)?.update?.versionName
    val context = LocalContext.current
    var cacheCleared by remember { mutableIntStateOf(0) }
    val cacheBytes by produceState<Long?>(null, cacheCleared) { value = withContext(Dispatchers.IO) { LayoutImageLoader.cacheSize(context) } }
    // Out here, not in the row: a lazy row that scrolls back into view would take focus again.
    val initialFocus = rememberInitialFocus()

    SettingsScaffold(
        "OwnTV settings",
        "Layout: ${state.document.name}" + if (available != null) " · OwnTV $available is available in Updates" else "",
    ) {
        // Lazy: only the rows on screen are built when Settings opens; each TV list item is costly to create.
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                SettingsItem("Edit layout", "Add, move and customize items with your remote", modifier = initialFocus) { nav.push(Screen.Editor()) }
            }
            builtInUpdate?.let { update ->
                item {
                    SettingsItem("Update \"${update.name}\"", update.description()) {
                        scope.launch {
                            if (container.layouts.applyBuiltInUpdate()) {
                                container.actions.toast("Updated to the new \"${update.name}\" ${update.ref.kind.label}")
                                nav.home()
                            }
                        }
                    }
                }
            }
            item {
                SettingsItem(
                    "Import layout",
                    "Paste JSON from your phone, load a URL or a file",
                ) { nav.push(Screen.Import) }
            }
            item {
                SettingsItem("Looks", "Google TV, Apple TV, Fire TV, Roku and more; use a whole look or only its style") { nav.push(Screen.Looks) }
            }
            item {
                SettingsItem("Templates", "Start from a built-in layout") { nav.push(Screen.Templates) }
            }
            item {
                SettingsItem(
                    "Current layout",
                    if (problems == 0) "Preview \"${state.document.name}\"" else "Preview \"${state.document.name}\" ($problems warning(s))",
                ) {
                    nav.showPreview(container.decode(container.layouts.currentJson()), "Current layout")
                }
            }
            item {
                SettingsItem("Revert to previous layout", "Undo the last change", enabled = hasHistory) {
                    scope.launch {
                        if (container.layouts.revert()) {
                            container.actions.toast("Restored the previous layout")
                            nav.home()
                        }
                    }
                }
            }
            item {
                SettingsItem("Layout history", "Restore one of the last 10 layouts", enabled = hasHistory) { nav.push(Screen.History) }
            }
            item {
                SettingsItem("All apps", "Every installed app") { nav.push(Screen.AppDrawer) }
            }
            item {
                SettingsItem("Hidden apps", "Apps left out of your rows and grids") { nav.push(Screen.HiddenApps) }
            }
            item {
                SettingsItem("Make OwnTV your Home screen", "What to do on Google TV") { nav.push(Screen.Help) }
            }
            item {
                SettingsItem(
                    "Standby",
                    if (standbyOn) {
                        "On: the standby action and the screensaver's standby switch the TV off"
                    } else {
                        "Off: turn on \"OwnTV standby\" in Accessibility so layouts can switch the TV off"
                    },
                ) { container.actions.openAccessibilitySettings() }
            }
            item {
                // Read each time Settings opens: usage access is granted outside the app.
                val usageAccess = remember { container.recent.hasUsageAccess() }
                SettingsItem("Recent apps", recentSummary(remoteOn, usageAccess)) {
                    when {
                        !remoteOn -> container.actions.openAccessibilitySettings()
                        !usageAccess && container.actions.openUsageAccessSettings() -> {}
                        else -> nav.push(Screen.Help)
                    }
                }
            }
            item {
                SettingsItem(
                    "Clear image cache",
                    when (val bytes = cacheBytes) {
                        null -> "Delete the images downloaded for your layouts"
                        0L -> "No downloaded layout images"
                        else -> "Delete ${Formatter.formatShortFileSize(context, bytes)} of downloaded layout images; they download again when shown"
                    },
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) { LayoutImageLoader.clearCache(context) }
                        cacheCleared++
                        container.actions.toast("Deleted the downloaded layout images")
                    }
                }
            }
            item {
                SettingsItem("Android settings", "Open the TV's system settings") { container.actions.openSystemSettings() }
            }
            item {
                SettingsItem("Updates", updateSummary(update, container.updater)) { nav.push(Screen.Updates) }
            }
            item {
                SettingsItem("Performance metrics", metricsSummary(metricsMode)) { nav.push(Screen.Performance) }
            }
            item {
                SettingsItem("About", "OwnTV ${container.updater.installedVersionName}, layout format v$CURRENT_SCHEMA_VERSION") {}
            }
        }
    }
}

private fun recentSummary(remoteOn: Boolean, usageAccess: Boolean): String {
    val sources = buildList {
        add("OwnTV launches")
        if (remoteOn) add("the open app")
        if (usageAccess) add("usage access")
    }
    val missing = if (remoteOn) "" else ". Turn on \"OwnTV remote\" in Accessibility to include apps opened any other way"
    return "Counting " + sources.joinToString(", ") + missing
}

@Composable
fun TemplatesScreen(container: AppContainer, nav: Navigator) {
    val templates by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            container.assets.templateFiles().map { file ->
                val name = container.decode(container.assets.template(file)).document?.name ?: file
                file to name
            }
        }
    }
    SettingsScaffold("Templates", "Pick a layout to preview. Nothing changes until you choose Apply.") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            templates.forEachIndexed { index, (file, name) ->
                SettingsItem(name, file, modifier = if (index == 0) rememberInitialFocus() else Modifier) {
                    nav.showPreview(container.decode(container.assets.template(file)), "Template: $name", BuiltInRef(BuiltInKind.Template, file))
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(container: AppContainer, nav: Navigator) {
    val entries by produceState(emptyList<HistoryEntry>()) { value = container.layouts.history() }
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    SettingsScaffold("Layout history", "Older layouts, newest first.") {
        if (entries.isEmpty()) {
            Text("No earlier layouts yet.", color = Color(0xFFA9AFBA))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            entries.forEachIndexed { index, entry ->
                SettingsItem(entry.name, formatter.format(Date(entry.savedAt)), modifier = if (index == 0) rememberInitialFocus() else Modifier) {
                    nav.showPreview(container.decode(entry.file.readText()), "History: ${entry.name}")
                }
            }
        }
    }
}

@Composable
fun HelpScreen(container: AppContainer) {
    val pkg = "dev.owntv.launcher"
    SettingsScaffold("Make OwnTV your Home screen") {
        Column(Modifier.fillMaxWidth().then(rememberInitialFocus()).focusable().verticalScroll(rememberScrollState())) {
            HelpText(
                "Google TV sends the Home button to its own launcher. To use OwnTV instead, connect a computer with adb " +
                    "(Settings > System > Developer options > USB or network debugging) and run:",
            )
            Code("adb shell cmd package set-home-activity $pkg/.MainActivity")
            HelpText("If Home still opens the Google TV launcher, disable it (you can undo this any time):")
            Code("adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx")
            HelpText("To undo:")
            Code("adb shell pm enable com.google.android.apps.tv.launcherx")
            HelpText("Layout files can also be copied straight to the TV and opened from Import > Files:")
            Code("adb push my-layout.json ${container.files.adbPath}/")
            HelpText(
                "Rows with a \"recent\" source always include apps opened from OwnTV. To include apps opened any other way " +
                    "(voice search, notifications), turn on \"OwnTV remote\" under Accessibility in Android settings (Settings > Recent apps). " +
                    "It sees which app is open and nothing else. Or grant usage access instead:",
            )
            Code("adb shell appops set $pkg GET_USAGE_STATS allow")
            HelpText("Long-press OK on any tile to open this launcher's settings from any layout.")
        }
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFDADDE3), modifier = Modifier.padding(top = 12.dp))
}

@Composable
fun Code(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        color = Color(0xFF9ECBFF),
        modifier = Modifier.padding(top = 6.dp, start = 12.dp),
    )
}

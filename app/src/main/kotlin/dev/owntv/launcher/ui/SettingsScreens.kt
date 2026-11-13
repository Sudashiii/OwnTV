package dev.owntv.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
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
    val update by container.updater.state.collectAsStateWithLifecycle()
    val builtInUpdate by container.layouts.builtInUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.updater.checkIfStale() }
    val badges = buildSet {
        if (builtInUpdate != null) add(SettingsGroup.Layout)
        if (update is UpdateState.Available) add(SettingsGroup.General)
    }
    SettingsSplitView(nav, "OwnTV", "Layout: ${state.document.name}", badges) { group ->
        when (group) {
            SettingsGroup.Layout -> LayoutSettings(container, nav, state)
            SettingsGroup.Apps -> AppsSettings(container, nav)
            SettingsGroup.Remote -> RemoteSettings(container, nav, state)
            SettingsGroup.General -> GeneralSettings(container, nav, update)
            SettingsGroup.Developer -> DeveloperSettings(container, nav)
        }
    }
}

@Composable
private fun LayoutSettings(container: AppContainer, nav: Navigator, state: LayoutState) {
    val scope = rememberCoroutineScope()
    val hasHistory by container.layouts.hasHistory.collectAsStateWithLifecycle()
    val builtInUpdate by container.layouts.builtInUpdate.collectAsStateWithLifecycle()
    val problems = state.issues.count { it.severity != Severity.Info }
    SettingsCard {
        row {
            SettingsRow("edit", "Edit layout", "Add, move and customize items with your remote", Icons.Filled.Edit, SettingsColors.Blue) {
                nav.push(Screen.Editor())
            }
        }
        builtInUpdate?.let { update ->
            row {
                SettingsRow("built-in-update", "Update \"${update.name}\"", update.description(), Icons.Filled.Refresh, SettingsColors.Green, RowEnd.New) {
                    scope.launch {
                        if (container.layouts.applyBuiltInUpdate()) {
                            container.actions.toast("Updated to the new \"${update.name}\" ${update.ref.kind.label}")
                            nav.home()
                        }
                    }
                }
            }
        }
        row {
            SettingsRow(
                "current",
                "Current layout",
                if (problems == 0) "Preview \"${state.document.name}\"" else "Preview \"${state.document.name}\" ($problems warning(s))",
                SettingsIcons.Tv,
                SettingsColors.Teal,
            ) {
                nav.showPreview(container.decode(container.layouts.currentJson()), "Current layout")
            }
        }
    }
    SettingsCard("Start from something new") {
        row {
            SettingsRow("looks", "Looks", "Google TV, Apple TV, Fire TV, Roku and more; use a whole look or only its style", SettingsIcons.Palette, SettingsColors.Pink) {
                nav.push(Screen.Looks)
            }
        }
        row {
            SettingsRow("templates", "Templates", "Start from a built-in layout", SettingsIcons.Dashboard, SettingsColors.Indigo) { nav.push(Screen.Templates) }
        }
        row {
            SettingsRow("import", "Import layout", "Paste JSON from your phone, load a URL or a file", SettingsIcons.Download, SettingsColors.Blue) {
                nav.push(Screen.Import)
            }
        }
    }
    SettingsCard("Earlier layouts") {
        row {
            SettingsRow(
                "revert",
                "Revert to previous layout",
                "Undo the last change",
                SettingsIcons.Undo,
                SettingsColors.Orange,
                RowEnd.None,
                enabled = hasHistory,
            ) {
                scope.launch {
                    if (container.layouts.revert()) {
                        container.actions.toast("Restored the previous layout")
                        nav.home()
                    }
                }
            }
        }
        row {
            SettingsRow("history", "Layout history", "Restore one of the last 10 layouts", SettingsIcons.History, SettingsColors.Graphite, enabled = hasHistory) {
                nav.push(Screen.History)
            }
        }
    }
}

@Composable
private fun AppsSettings(container: AppContainer, nav: Navigator) {
    val remoteOn by RemoteService.enabled.collectAsStateWithLifecycle()
    // Read each time the group shows: usage access is granted outside the app.
    val usageAccess = remember { container.recent.hasUsageAccess() }
    SettingsCard {
        row { SettingsRow("all-apps", "All apps", "Every installed app", SettingsIcons.Apps, SettingsColors.Green) { nav.push(Screen.AppDrawer) } }
        row {
            SettingsRow("hidden-apps", "Hidden apps", "Apps left out of your rows and grids", SettingsIcons.HiddenApps, SettingsColors.Graphite) {
                nav.push(Screen.HiddenApps)
            }
        }
    }
    SettingsCard {
        row {
            SettingsRow("recent", "Recent apps", recentSummary(remoteOn, usageAccess), SettingsIcons.History, SettingsColors.Blue) {
                when {
                    !remoteOn -> nav.push(Screen.RemoteButtons)
                    !usageAccess && container.actions.openUsageAccessSettings() -> {}
                    else -> nav.push(Screen.Help)
                }
            }
        }
    }
}

@Composable
private fun RemoteSettings(container: AppContainer, nav: Navigator, state: LayoutState) {
    val standbyOn by StandbyService.enabled.collectAsStateWithLifecycle()
    val remoteOn by RemoteService.enabled.collectAsStateWithLifecycle()
    SettingsCard {
        row {
            SettingsRow("remote-buttons", "Remote buttons", remoteButtonsSummary(remoteOn, state.document.keyBindings.size), SettingsIcons.Remote, SettingsColors.Purple) {
                nav.push(Screen.RemoteButtons)
            }
        }
    }
    SettingsCard {
        row {
            SettingsRow(
                "standby",
                "Standby",
                if (standbyOn) {
                    "The standby action and the screensaver's standby switch the TV off"
                } else {
                    "Turn on \"OwnTV standby\" in Accessibility so layouts can switch the TV off"
                },
                SettingsIcons.Power,
                SettingsColors.Indigo,
                RowEnd.Value(if (standbyOn) "On" else "Off"),
            ) { container.actions.openAccessibilitySettings() }
        }
    }
}

@Composable
private fun GeneralSettings(container: AppContainer, nav: Navigator, update: UpdateState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cacheCleared by remember { mutableIntStateOf(0) }
    val cacheBytes by produceState<Long?>(null, cacheCleared) { value = withContext(Dispatchers.IO) { LayoutImageLoader.cacheSize(context) } }
    SettingsCard {
        row {
            SettingsRow(
                "about",
                "About",
                "Layout format v$CURRENT_SCHEMA_VERSION",
                Icons.Filled.Info,
                SettingsColors.Gray,
                RowEnd.Value("OwnTV ${container.updater.installedVersionName}"),
            ) {}
        }
        row {
            SettingsRow(
                "updates",
                "Updates",
                updateSummary(update, container.updater),
                SettingsIcons.Download,
                SettingsColors.Gray,
                if (update is UpdateState.Available) RowEnd.New else RowEnd.Chevron,
            ) { nav.push(Screen.Updates) }
        }
    }
    SettingsCard {
        row {
            SettingsRow("home-screen", "Make OwnTV your Home screen", "What to do on Google TV", Icons.Filled.Home, SettingsColors.Blue) {
                nav.push(Screen.Help)
            }
        }
        row {
            SettingsRow("android-settings", "Android settings", "Open the TV's system settings", SettingsIcons.Android, SettingsColors.Green, RowEnd.None) {
                container.actions.openSystemSettings()
            }
        }
    }
    SettingsCard("Storage") {
        row {
            SettingsRow(
                "image-cache",
                "Clear image cache",
                when (val bytes = cacheBytes) {
                    null -> "Delete the images downloaded for your layouts"
                    0L -> "No downloaded layout images"
                    else -> "Downloaded layout images download again when shown"
                },
                SettingsIcons.Image,
                SettingsColors.Teal,
                RowEnd.Value(cacheBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) } ?: ""),
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) { LayoutImageLoader.clearCache(context) }
                    cacheCleared++
                    container.actions.toast("Deleted the downloaded layout images")
                }
            }
        }
    }
}

@Composable
private fun DeveloperSettings(container: AppContainer, nav: Navigator) {
    val metricsMode by container.perf.mode.collectAsStateWithLifecycle()
    val adbStatus by container.adb.status.collectAsStateWithLifecycle()
    SettingsCard {
        row { SettingsRow("adb", "ADB on this TV", selfAdbSummary(adbStatus), SettingsIcons.Code, SettingsColors.Graphite) { nav.push(Screen.SelfAdb) } }
        row {
            SettingsRow("performance", "Performance metrics", metricsSummary(metricsMode), SettingsIcons.Speed, SettingsColors.Red) {
                nav.push(Screen.Performance)
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            HelpText(
                "Google TV sends the Home button to its own launcher. To use OwnTV instead, run this with adb " +
                    "(Settings > System > Developer options > USB or network debugging). Press OK on a command to run it on this TV " +
                    "(see Settings > Developer > ADB on this TV), or type it on a computer:",
            )
            Code("adb shell cmd package set-home-activity $pkg/.MainActivity", rememberInitialFocus())
            HelpText("If Home still opens the Google TV launcher, disable it (you can undo this any time):")
            Code("adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx")
            HelpText("To undo:")
            Code("adb shell pm enable com.google.android.apps.tv.launcherx")
            HelpText("Layout files can also be copied straight to the TV and opened from Import > Files:")
            Code("adb push my-layout.json ${container.files.adbPath}/")
            HelpText(
                "Rows with a \"recent\" source always include apps opened from OwnTV. To include apps opened any other way " +
                    "(voice search, notifications), turn on \"OwnTV remote\" under Accessibility in Android settings (Settings > Apps > Recent apps). " +
                    "It sees which app is open and nothing else. Or grant usage access instead:",
            )
            Code("adb shell appops set $pkg GET_USAGE_STATS allow")
            HelpText("Long-press OK on any tile to open this launcher's settings from any layout.")
        }
    }
}

@Composable
fun HelpText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFDADDE3), modifier = Modifier.padding(top = 12.dp))
}


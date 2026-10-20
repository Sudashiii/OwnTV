package dev.owntv.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.data.layout.HistoryEntry
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.update.UpdateState
import dev.owntv.layout.model.CURRENT_SCHEMA_VERSION
import dev.owntv.layout.validation.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator, state: LayoutState) {
    val scope = rememberCoroutineScope()
    val hasHistory by produceState(false, state) { value = withContext(Dispatchers.IO) { container.layouts.hasHistory() } }
    val problems = state.issues.count { it.severity != Severity.Info }
    val update by container.updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.updater.checkIfStale() }
    val available = (update as? UpdateState.Available)?.update?.versionName

    SettingsScaffold(
        "OwnTV settings",
        "Layout: ${state.document.name}" + if (available != null) " · OwnTV $available is available in Updates" else "",
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SettingsItem("Edit layout", "Add, move and customize items with your remote", modifier = rememberInitialFocus()) { nav.push(Screen.Editor()) }
            SettingsItem(
                "Import layout",
                "Paste JSON from your phone, load a URL or a file",
            ) { nav.push(Screen.Import) }
            SettingsItem("Looks", "Google TV, Apple TV, Fire TV, Roku and more; use a whole look or only its style") { nav.push(Screen.Looks) }
            SettingsItem("Templates", "Start from a built-in layout") { nav.push(Screen.Templates) }
            SettingsItem(
                "Current layout",
                if (problems == 0) "Preview \"${state.document.name}\"" else "Preview \"${state.document.name}\" ($problems warning(s))",
            ) {
                nav.showPreview(container.decode(container.layouts.currentJson()), "Current layout")
            }
            SettingsItem("Revert to previous layout", "Undo the last change", enabled = hasHistory) {
                scope.launch {
                    if (container.layouts.revert()) {
                        container.actions.toast("Restored the previous layout")
                        nav.home()
                    }
                }
            }
            SettingsItem("Layout history", "Restore one of the last 10 layouts", enabled = hasHistory) { nav.push(Screen.History) }
            SettingsItem("All apps", "Every installed app") { nav.push(Screen.AppDrawer) }
            SettingsItem("Make OwnTV your Home screen", "What to do on Google TV") { nav.push(Screen.Help) }
            SettingsItem("Android settings", "Open the TV's system settings") { container.actions.openSystemSettings() }
            SettingsItem("Updates", updateSummary(update, container.updater)) { nav.push(Screen.Updates) }
            SettingsItem("About", "OwnTV ${container.updater.installedVersionName}, layout format v$CURRENT_SCHEMA_VERSION") {}
        }
    }
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
                    nav.showPreview(container.decode(container.assets.template(file)), "Template: $name")
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
            HelpText("Rows with a \"recent\" source always include apps opened from OwnTV. To include apps opened any other way (voice search, notifications), grant usage access once:")
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

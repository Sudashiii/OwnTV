package dev.owntv.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.adb.SelfAdb
import dev.owntv.launcher.cleanup.CleanupCatalog
import dev.owntv.launcher.cleanup.CleanupItem
import dev.owntv.launcher.cleanup.CleanupPlan
import dev.owntv.launcher.cleanup.CleanupPlanner
import dev.owntv.launcher.cleanup.CleanupRepository
import dev.owntv.launcher.cleanup.CleanupRepository.Scan
import dev.owntv.launcher.cleanup.ListMatch
import dev.owntv.launcher.cleanup.TurnedOff
import dev.owntv.launcher.ui.render.AppCatalog

/** The one-line state of Clean up this TV for the Settings > Apps row. */
fun cleanupSummary(turnedOff: Int): String =
    if (turnedOff == 0) "Turn off preinstalled apps and trackers, with tested lists from cleanyourtv" else "$turnedOff package(s) turned off · undo any time"

private enum class Mode { Browse, PickList, Confirm }

/** "No list" in the list picker, so a choice of none survives recomposition apart from "not chosen yet". */
private const val NO_LIST = ""

/**
 * Settings > Apps > Clean up this TV: cleanyourtv's debloat, run on the TV through ADB on this TV. Packages are
 * turned off with `pm disable-user` (never uninstalled) and can be turned back on here.
 */
@Composable
fun CleanupScreen(container: AppContainer, nav: Navigator, apps: AppCatalog) {
    val repo = container.cleanup
    val scan by repo.scan.collectAsStateWithLifecycle()
    val record by repo.record.collectAsStateWithLifecycle()
    val working by repo.working.collectAsStateWithLifecycle()
    val outcome by repo.outcome.collectAsStateWithLifecycle()
    val adbStatus by container.adb.status.collectAsStateWithLifecycle()
    val catalog by produceState<CleanupCatalog?>(null) { value = repo.catalog() }
    val match by produceState<ListMatch?>(null) { value = repo.matchLists() }
    LaunchedEffect(Unit) { if (repo.scan.value == Scan.None) repo.rescan() }

    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(Mode.Browse) }
    var showOther by rememberSaveable { mutableStateOf(false) }
    // The rows a change acts on disappear with it; the result takes focus instead of the sidebar.
    val outcomeFocus = remember { FocusRequester() }
    LaunchedEffect(outcome) {
        if (outcome != null && mode == Mode.Browse) {
            withFrameNanos { }
            runCatching { outcomeFocus.requestFocus() }
        }
    }
    if (mode != Mode.Browse) BackHandler { mode = Mode.Browse }

    val state = (scan as? Scan.Done)?.state
    val loadedCatalog = catalog
    val loadedMatch = match
    if (state == null || loadedCatalog == null || loadedMatch == null) {
        NotScanned(container, nav, scan, adbStatus)
        return
    }
    val list = when (val id = chosenId) {
        null -> loadedMatch.best
        NO_LIST -> null
        else -> loadedCatalog.lists.firstOrNull { it.id == id }
    }
    val plan = remember(loadedCatalog, state, list, record) { CleanupPlanner.build(loadedCatalog, state, list, record.packages.keys) }
    // What is selected starts from the TV's list and starts over when the list or the TV changes.
    var selected by remember(plan) { mutableStateOf(plan.suggested.filter { it.suggested }.map { it.packageName }.toSet()) }
    fun label(name: String) = apps[name]?.let(apps::labelOf)

    when (mode) {
        Mode.PickList -> ListPicker(loadedCatalog, loadedMatch, list) { picked ->
            chosenId = picked?.id ?: NO_LIST
            mode = Mode.Browse
        }
        Mode.Confirm -> Confirm(
            items = (plan.suggested + plan.common + plan.other).filter { it.packageName in selected },
            label = ::label,
            onConfirm = {
                repo.disable(selected.toList())
                mode = Mode.Browse
            },
            onCancel = { mode = Mode.Browse },
        )
        Mode.Browse -> SettingsScaffold(
            "Clean up this TV",
            "Turn off preinstalled apps, ads and trackers. Nothing is uninstalled: everything can be turned back on here.",
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsCard {
                    row {
                        SettingsRow(
                            "cleanup-list",
                            if (list == null) "No tested list" else "List: ${list.title}",
                            when {
                                list == null && loadedMatch.best == null -> "cleanyourtv has no list for this TV. Choose one from a similar TV, or pick packages below."
                                list == null -> "Choose a list to get suggestions"
                                else -> "${list.off.size} packages on that TV" + (if (list.os.isNotBlank()) " · ${list.os}" else "") + " · OK to choose another"
                            },
                            Icons.Filled.Build,
                            SettingsColors.Teal,
                            modifier = rememberInitialFocus(),
                        ) { mode = Mode.PickList }
                    }
                    row {
                        SettingsRow(
                            "cleanup-run",
                            if (working) "Working…" else "Turn off ${selected.size} selected package(s)",
                            if (selected.isEmpty()) "Select packages below" else "You see the list and what each one costs before anything changes",
                            Icons.Filled.Check,
                            SettingsColors.Red,
                            enabled = selected.isNotEmpty() && !working,
                        ) { mode = Mode.Confirm }
                    }
                    row {
                        SettingsRow("cleanup-rescan", "Scan again", "Read this TV's packages again", Icons.Filled.Refresh, SettingsColors.Graphite, RowEnd.None, enabled = !working) {
                            repo.rescan()
                        }
                    }
                }
                outcome?.let { result -> OutcomeCard(result, ::label, Modifier.focusRequester(outcomeFocus)) { repo.dismissOutcome() } }
                if (plan.unchecked.isNotEmpty()) {
                    Text(
                        "The TV did not say ${plan.unchecked.joinToString(", ")}. Packages no tested list knows stay locked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsColors.Muted,
                    )
                }
                ItemsCard("Suggested for this TV", plan.suggested, selected, ::label) { selected = selected.toggle(it) }
                ItemsCard("Turned off on other TVs", plan.common, selected, ::label) { selected = selected.toggle(it) }
                if (plan.other.isNotEmpty()) {
                    SettingsCard("Other packages") {
                        row {
                            SettingsRow(
                                "cleanup-other",
                                if (showOther) "Hide other packages" else "Show ${plan.other.size} other packages",
                                CleanupRulesText.OTHER,
                                Icons.Filled.Warning,
                                SettingsColors.Orange,
                                RowEnd.None,
                            ) { showOther = !showOther }
                        }
                        if (showOther) plan.other.forEach { item -> row { ItemRow(item, item.packageName in selected, label(item.packageName)) { selected = selected.toggle(it) } } }
                    }
                }
                TurnedOffCard(plan, working, ::label, onEnable = { repo.enable(listOf(it)) }, onRestoreAll = { repo.restoreAll() }, ownTvCount = record.packages.size)
                Text(
                    "Package lists and notes from cleanyourtv (github.com/${loadedCatalog.source.repo}, MIT licence), " +
                        "commit ${loadedCatalog.source.ref.take(7)}. Turn packages back on before you uninstall OwnTV: " +
                        "the record of what it turned off lives in OwnTV's files. The TV's Settings > Apps can turn them on too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsColors.Muted,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private object CleanupRulesText {
    const val OTHER = "No tested list turns these off. Only turn one off if you know what it does; core Android parts are never shown."
}

private fun Set<String>.toggle(name: String): Set<String> = if (name in this) this - name else this + name

@Composable
private fun NotScanned(container: AppContainer, nav: Navigator, scan: Scan, adbStatus: SelfAdb.Status) {
    SettingsScaffold("Clean up this TV", "Turn off preinstalled apps, ads and trackers, with ADB on this TV.") {
        SettingsCard {
            when (scan) {
                is Scan.Failed -> {
                    row {
                        SettingsRow(
                            "cleanup-retry",
                            "Try again",
                            (scan.error as? SelfAdb.AdbException)?.problem?.help() ?: "The TV did not list its packages. ${scan.error?.message.orEmpty()}".trim(),
                            Icons.Filled.Refresh,
                            SettingsColors.Blue,
                            RowEnd.None,
                            modifier = rememberInitialFocus(),
                        ) { container.cleanup.rescan() }
                    }
                    row { SettingsRow("cleanup-adb", "ADB on this TV", "How to let OwnTV run adb commands here", SettingsIcons.Code, SettingsColors.Graphite) { nav.push(Screen.SelfAdb) } }
                }
                else -> row {
                    SettingsRow(
                        "cleanup-scanning",
                        "Reading this TV's packages…",
                        if (adbStatus == SelfAdb.Status.WaitingForApproval) selfAdbSummary(adbStatus) + ": tick \"Always allow\" and choose Allow" else "Through ADB on this TV",
                        Icons.Filled.Refresh,
                        SettingsColors.Blue,
                        RowEnd.None,
                        modifier = rememberInitialFocus(),
                    ) { if (scan == Scan.None) container.cleanup.rescan() }
                }
            }
        }
    }
}

@Composable
private fun ItemsCard(caption: String, items: List<CleanupItem>, selected: Set<String>, label: (String) -> String?, onToggle: (String) -> Unit) {
    SettingsCard(caption) {
        items.forEach { item -> row { ItemRow(item, item.packageName in selected, label(item.packageName), onToggle) } }
    }
}

@Composable
private fun ItemRow(item: CleanupItem, selected: Boolean, label: String?, onToggle: (String) -> Unit) {
    val name = item.packageName
    val details = buildList {
        if (label != null) add(name)
        item.info?.note?.takeIf { it.isNotBlank() }?.let(::add)
        item.info?.let { info -> add("off on ${info.off} tested TV(s)" + if (info.kept > 0) ", kept on ${info.kept}" else "") }
        item.blocked?.let { add("Stays on: $it") } ?: item.warning?.let(::add)
    }
    SettingsRow(
        "cleanup-pkg-$name",
        label ?: name,
        details.joinToString(" · "),
        when {
            item.blocked != null -> Icons.Filled.Lock
            selected -> Icons.Filled.Check
            item.warning != null -> Icons.Filled.Warning
            else -> Icons.Filled.Close
        },
        when {
            item.blocked != null -> SettingsColors.Gray
            selected -> SettingsColors.Red
            item.warning != null -> SettingsColors.Orange
            else -> SettingsColors.Graphite
        },
        RowEnd.Value(if (selected) "Turn off" else "Keep"),
        enabled = item.blocked == null,
    ) { onToggle(name) }
}

@Composable
private fun TurnedOffCard(
    plan: CleanupPlan,
    working: Boolean,
    label: (String) -> String?,
    onEnable: (String) -> Unit,
    onRestoreAll: () -> Unit,
    ownTvCount: Int,
) {
    if (plan.turnedOff.isEmpty()) return
    SettingsCard("Turned off") {
        if (ownTvCount > 0) {
            row {
                SettingsRow(
                    "cleanup-restore",
                    "Turn everything back on",
                    "The $ownTvCount package(s) Clean up this TV turned off",
                    SettingsIcons.Undo,
                    SettingsColors.Green,
                    RowEnd.None,
                    enabled = !working,
                ) { onRestoreAll() }
            }
        }
        plan.turnedOff.forEach { off -> row { TurnedOffRow(off, working, label(off.packageName), onEnable) } }
    }
}

@Composable
private fun TurnedOffRow(off: TurnedOff, working: Boolean, label: String?, onEnable: (String) -> Unit) {
    val details = buildList {
        if (label != null) add(off.packageName)
        add(if (off.byOwnTv) "Turned off here" else "Turned off by the TV or another tool")
        off.info?.note?.takeIf { it.isNotBlank() }?.let(::add)
    }
    SettingsRow(
        "cleanup-off-${off.packageName}",
        label ?: off.packageName,
        details.joinToString(" · "),
        SettingsIcons.Undo,
        if (off.byOwnTv) SettingsColors.Green else SettingsColors.Graphite,
        RowEnd.Value("Turn on"),
        enabled = !working,
    ) { onEnable(off.packageName) }
}

@Composable
private fun OutcomeCard(outcome: CleanupRepository.Outcome, label: (String) -> String?, modifier: Modifier, onDismiss: () -> Unit) {
    val verb = if (outcome.turnedOff) "Turned off" else "Turned on"
    val failed = outcome.failed.entries.joinToString("; ") { (name, why) -> (label(name) ?: name) + (why?.let { ": $it" } ?: "") }
    SettingsCard {
        row {
            SettingsRow(
                "cleanup-outcome",
                "$verb ${outcome.changed} of ${outcome.requested.size} package(s)",
                when {
                    outcome.failed.isEmpty() -> "OK to hide this"
                    outcome.error is SelfAdb.AdbException -> (outcome.error as SelfAdb.AdbException).problem.help()
                    else -> "The TV did not change: $failed"
                },
                if (outcome.failed.isEmpty()) Icons.Filled.Check else Icons.Filled.Warning,
                if (outcome.failed.isEmpty()) SettingsColors.Green else SettingsColors.Orange,
                RowEnd.None,
                modifier = modifier,
            ) { onDismiss() }
        }
    }
}

@Composable
private fun ListPicker(catalog: CleanupCatalog, match: ListMatch, current: CleanupCatalog.TvList?, onPick: (CleanupCatalog.TvList?) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val lists = if (all) catalog.lists.sortedBy { it.title.lowercase() } else match.choices
    SettingsScaffold("Choose a tested list", "Lists from cleanyourtv, each made and tested on one TV model. Pick yours or the closest one.") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                match.best?.let { best ->
                    row { PickRow(best, "Made for this TV", best == current, onPick, rememberInitialFocus()) }
                }
                row {
                    SettingsRow(
                        "cleanup-pick-none",
                        "No list",
                        "Only pick packages by hand",
                        Icons.Filled.Close,
                        SettingsColors.Graphite,
                        RowEnd.Value(if (current == null) "Chosen" else ""),
                        modifier = if (match.best == null) rememberInitialFocus() else Modifier,
                    ) {
                        onPick(null)
                    }
                }
                row {
                    SettingsRow(
                        "cleanup-pick-all",
                        if (all) "Only this brand" else "Show all ${catalog.lists.size} lists",
                        null,
                        SettingsIcons.Tv,
                        SettingsColors.Teal,
                        RowEnd.None,
                    ) { all = !all }
                }
            }
            SettingsCard(if (all) "All TVs" else "Same brand") {
                lists.forEach { list -> row { PickRow(list, list.os.ifBlank { null }, list == current, onPick) } }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PickRow(list: CleanupCatalog.TvList, note: String?, chosen: Boolean, onPick: (CleanupCatalog.TvList?) -> Unit, modifier: Modifier = Modifier) {
    SettingsRow(
        "cleanup-pick-${list.id}",
        list.title,
        listOfNotNull(note, "${list.off.size} packages").joinToString(" · "),
        SettingsIcons.Tv,
        if (chosen) SettingsColors.Blue else SettingsColors.Graphite,
        RowEnd.Value(if (chosen) "Chosen" else ""),
        modifier = modifier,
    ) { onPick(list) }
}

@Composable
private fun Confirm(items: List<CleanupItem>, label: (String) -> String?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val names = items.map { it.packageName }
    SettingsScaffold(
        "Turn off ${items.size} package(s)?",
        "They stay installed and can be turned back on from this screen. Back cancels.",
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                row { SettingsRow("cleanup-confirm", "Turn off", null, Icons.Filled.Check, SettingsColors.Red, RowEnd.None, modifier = rememberInitialFocus(), onClick = onConfirm) }
                row { SettingsRow("cleanup-cancel", "Cancel", null, Icons.Filled.Close, SettingsColors.Graphite, RowEnd.None, onClick = onCancel) }
            }
            SettingsCard("Packages") {
                items.forEach { item ->
                    row {
                        SettingsRow(
                            "cleanup-confirm-${item.packageName}",
                            label(item.packageName) ?: item.packageName,
                            item.warning ?: item.info?.note?.takeIf { it.isNotBlank() } ?: item.packageName,
                            if (item.warning != null) Icons.Filled.Warning else Icons.Filled.Check,
                            if (item.warning != null) SettingsColors.Orange else SettingsColors.Red,
                            RowEnd.None,
                        ) {}
                    }
                }
            }
            Text("What runs on the TV:", style = MaterialTheme.typography.labelLarge, color = SettingsColors.Muted)
            CleanupPlanner.disableCommands(names).flatMap { it.split("; ") }.forEach { line ->
                Text(
                    "adb shell $line",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = SettingsColors.Muted,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

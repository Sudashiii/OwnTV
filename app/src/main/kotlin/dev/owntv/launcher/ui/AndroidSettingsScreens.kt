package dev.owntv.launcher.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.actions.AccessibilitySettings
import dev.owntv.launcher.androidsettings.AndroidState
import dev.owntv.launcher.androidsettings.ScreenTimeout
import dev.owntv.launcher.androidsettings.SettingsTable
import dev.owntv.launcher.androidsettings.SurroundMode
import dev.owntv.launcher.androidsettings.TimeZones
import dev.owntv.layout.model.OpenSystemSettingsAction
import dev.owntv.layout.model.SettingsScreen
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The rows of the Android tab: the TV's own settings, shown and changed inside OwnTV. Each change goes
 * through [dev.owntv.launcher.androidsettings.AndroidSettings], which writes directly once OwnTV was allowed
 * to (Apps & access) and otherwise through the TV's own adb. Anything not here is one press away in the
 * sidebar's "Open Android settings".
 */
@Composable
internal fun AndroidSettingsGroup(container: AppContainer, nav: Navigator, group: SettingsGroup) {
    val settings = container.androidSettings
    val state by settings.state.collectAsStateWithLifecycle()
    LaunchedEffect(group) { settings.refresh() }
    val scope = rememberCoroutineScope()
    val actions = container.actions
    val openAndroid: (SettingsScreen) -> Unit = { actions.execute(OpenSystemSettingsAction(it)) }
    // Runs a change. When it did not work (no debugging yet, the TV said no) OwnTV says why and opens the
    // matching Android screen, so nothing is a dead end.
    val change: Change = { screen, block ->
        scope.launch {
            block()?.let { problem ->
                actions.toast(problem)
                if (screen != null) openAndroid(screen)
            }
        }
    }
    when (group) {
        SettingsGroup.AndroidNetwork -> NetworkRows(state, change, openAndroid, settings::setWifi)
        SettingsGroup.AndroidDisplay -> DisplayRows(state, change, openAndroid) { table, key, value -> settings.put(table, key, value) }
        SettingsGroup.AndroidAccessories -> AccessoryRows(state, change, openAndroid, settings::setBluetooth, settings::grantBluetooth)
        SettingsGroup.AndroidAccess -> AccessRows(container, state, change)
        SettingsGroup.AndroidSystem -> SystemRows(container, nav, state, change, openAndroid) { table, key, value -> settings.put(table, key, value) }
        else -> {}
    }
}

private typealias Put = suspend (SettingsTable, String, String) -> String?
/** Runs a change; when it fails, the Android screen of the first argument opens (none for null). */
private typealias Change = (SettingsScreen?, suspend () -> String?) -> Unit

@Composable
private fun NetworkRows(state: AndroidState, change: Change, openAndroid: (SettingsScreen) -> Unit, setWifi: suspend (Boolean) -> String?) {
    SettingsCard {
        row {
            val summary = when {
                !state.wifiOn -> "Off"
                state.wifiConnection == null -> "On · not connected to a network"
                state.ip != null -> "Connected to ${state.wifiConnection} · ${state.ip}"
                else -> "Connected to ${state.wifiConnection}"
            }
            SettingsRow("wifi", "Wi-Fi", summary, SettingsIcons.Wifi, SettingsColors.Blue, RowEnd.Switch(state.wifiOn)) {
                change(SettingsScreen.Network) { setWifi(!state.wifiOn) }
            }
        }
    }
    SettingsCard("Networks") {
        row {
            SettingsRow(
                "wifi-networks",
                "Choose a network",
                "Pick or add a Wi-Fi network in Android's own screen",
                SettingsIcons.Wifi,
                SettingsColors.Blue,
                RowEnd.External,
            ) { openAndroid(SettingsScreen.Network) }
        }
    }
    SettingsCard("Connection") {
        row {
            SettingsRow(
                "connection",
                "Connected through",
                if (state.connection == null) "No connection" else "The connection programs on this TV use",
                SettingsIcons.Public,
                SettingsColors.Teal,
                RowEnd.Value(state.connection ?: "None"),
            ) {}
        }
        row {
            SettingsRow("ip", "IP address", "Where a computer on your network finds this TV", SettingsIcons.Wifi, SettingsColors.Graphite, RowEnd.Value(state.ip ?: "None")) {}
        }
    }
}

@Composable
private fun DisplayRows(state: AndroidState, change: Change, openAndroid: (SettingsScreen) -> Unit, put: Put) {
    SettingsCard("Picture") {
        row {
            SettingsRow(
                "resolution",
                "Resolution",
                "Now ${state.resolution ?: "unknown"}. Resolution, HDR and frame rate change in Android's own screen",
                SettingsIcons.Tv,
                SettingsColors.Orange,
                RowEnd.External,
            ) { openAndroid(SettingsScreen.Display) }
        }
    }
    SettingsCard("Screen") {
        row {
            fun step(delta: Int) = change(SettingsScreen.Display) { put(SettingsTable.System, "screen_off_timeout", ScreenTimeout.step(state.screenOffMillis, delta).toString()) }
            SettingsRow(
                "screen-off",
                "Turn the screen off after",
                "When nothing is playing and no button is pressed. Left and right change it",
                SettingsIcons.Schedule,
                SettingsColors.Graphite,
                RowEnd.Value(ScreenTimeout.label(state.screenOffMillis)),
                onStep = ::step,
            ) { step(1) }
        }
    }
    SettingsCard("Sound") {
        row {
            fun step(delta: Int) = change(SettingsScreen.Sound) { put(SettingsTable.Global, "encoded_surround_output", SurroundMode.step(state.surround, delta).value.toString()) }
            SettingsRow(
                "surround",
                "Surround sound",
                "Which formats go to the TV or soundbar. Left and right change it",
                SettingsIcons.Speaker,
                SettingsColors.Teal,
                RowEnd.Value(state.surround.label),
                onStep = ::step,
            ) { step(1) }
        }
        row {
            SettingsRow(
                "system-sounds",
                "System sounds",
                "Clicks when you move around",
                SettingsIcons.MusicNote,
                SettingsColors.Purple,
                RowEnd.Switch(state.systemSounds),
            ) { change(SettingsScreen.Sound) { put(SettingsTable.System, "sound_effects_enabled", if (state.systemSounds) "0" else "1") } }
        }
    }
}

@Composable
private fun AccessoryRows(
    state: AndroidState,
    change: Change,
    openAndroid: (SettingsScreen) -> Unit,
    setBluetooth: suspend (Boolean) -> String?,
    grantBluetooth: suspend () -> String?,
) {
    SettingsCard {
        row {
            val paired = state.pairedDevices
            SettingsRow(
                "bluetooth",
                "Bluetooth",
                when {
                    !state.bluetoothOn -> "Off"
                    paired == null -> "On"
                    else -> "On · ${paired.size} paired"
                },
                SettingsIcons.Bluetooth,
                SettingsColors.Purple,
                RowEnd.Switch(state.bluetoothOn),
            ) { change(SettingsScreen.Bluetooth) { setBluetooth(!state.bluetoothOn) } }
        }
    }
    SettingsCard {
        row {
            SettingsRow(
                "pair-device",
                "Pair a new device",
                "Pairing happens in Android's own screen",
                SettingsIcons.Bluetooth,
                SettingsColors.Blue,
                RowEnd.External,
            ) { openAndroid(SettingsScreen.Bluetooth) }
        }
    }
    val paired = state.pairedDevices
    if (paired == null) {
        SettingsCard("Paired devices") {
            row {
                SettingsRow(
                    "show-paired",
                    "Show paired devices",
                    "OwnTV asks the TV once, over its own debugging, to list them",
                    SettingsIcons.Bluetooth,
                    SettingsColors.Blue,
                    RowEnd.None,
                ) { change(SettingsScreen.Bluetooth) { grantBluetooth() } }
            }
        }
    } else if (paired.isNotEmpty()) {
        SettingsCard("Paired devices") {
            paired.forEachIndexed { index, name ->
                row { SettingsRow("paired-$index", name, "Paired", SettingsIcons.Bluetooth, SettingsColors.Graphite, RowEnd.None) {} }
            }
        }
    }
}

@Composable
private fun AccessRows(container: AppContainer, state: AndroidState, change: Change) {
    var tick by remember { mutableIntStateOf(0) }
    val accessibility = remember(tick, state) { container.accessibility.read() }
    val usageAccess = remember(tick, state) { container.recent.hasUsageAccess() }
    val settings = container.androidSettings
    // Turning a service on or off needs the secure settings; the first time OwnTV asks the TV for them.
    fun toggle(component: String, on: Boolean) = change(SettingsScreen.Accessibility) {
        val problem = if (container.accessibility.canChange()) null else settings.grantAccess()
        if (problem == null && !container.accessibility.setEnabled(component, on)) {
            "Could not change the service. Turn it on or off in Android's Accessibility settings."
        } else {
            problem
        }.also { tick++ }
    }
    SettingsCard("Accessibility services") {
        row {
            SettingsRow(
                "service-remote",
                "OwnTV remote",
                "Remote buttons, recent apps and night light in every app",
                SettingsIcons.Remote,
                SettingsColors.Purple,
                RowEnd.Switch(accessibility.isOn(AccessibilitySettings.REMOTE)),
            ) { toggle(AccessibilitySettings.REMOTE, !accessibility.isOn(AccessibilitySettings.REMOTE)) }
        }
        row {
            SettingsRow(
                "service-standby",
                "OwnTV standby",
                "Lets layouts switch the TV off",
                SettingsIcons.Power,
                SettingsColors.Indigo,
                RowEnd.Switch(accessibility.isOn(AccessibilitySettings.STANDBY)),
            ) { toggle(AccessibilitySettings.STANDBY, !accessibility.isOn(AccessibilitySettings.STANDBY)) }
        }
        accessibility.services.filterNot { it.ownTv }.forEach { service ->
            row {
                SettingsRow(
                    "service-${service.component}",
                    service.label,
                    if (service.speaks) "Reads the screen aloud" else "Another app's accessibility service",
                    Icons.Filled.Info,
                    SettingsColors.Graphite,
                    RowEnd.Switch(true),
                ) { toggle(service.component, false) }
            }
        }
    }
    SettingsCard("What OwnTV may do") {
        row {
            val allowed = state.canWriteSecure
            SettingsRow(
                "change-settings",
                "Change system settings",
                if (allowed) "OwnTV changes the TV's settings itself" else "OK lets OwnTV do it, using the TV's own debugging",
                SettingsIcons.Shield,
                SettingsColors.Green,
                RowEnd.Value(if (allowed) "Allowed" else "Not allowed"),
            ) { if (!allowed) change(null) { settings.grantAccess() } }
        }
        row {
            SettingsRow(
                "usage-access",
                "Usage access",
                "See which app is open, for recent apps",
                SettingsIcons.History,
                SettingsColors.Blue,
                RowEnd.Value(if (usageAccess) "Allowed" else "Not allowed"),
            ) {
                if (!usageAccess) {
                    change(null) { settings.grantUsageAccess().also { tick++ } }
                }
            }
        }
    }
}

@Composable
private fun SystemRows(container: AppContainer, nav: Navigator, state: AndroidState, change: Change, openAndroid: (SettingsScreen) -> Unit, put: Put) {
    val context = LocalContext.current
    SettingsCard("Date and time") {
        row {
            SettingsRow(
                "auto-time",
                "Set time automatically",
                "From the network, with the time zone",
                SettingsIcons.Schedule,
                SettingsColors.Orange,
                RowEnd.Switch(state.autoTime),
            ) {
                val value = if (state.autoTime) "0" else "1"
                change(SettingsScreen.DateTime) { put(SettingsTable.Global, "auto_time", value) ?: put(SettingsTable.Global, "auto_time_zone", value) }
            }
        }
        row {
            SettingsRow(
                "time-zone",
                "Time zone",
                if (state.autoTime) "Turn off \"Set time automatically\" to pick one" else "Pick a city",
                SettingsIcons.Public,
                SettingsColors.Teal,
                RowEnd.Value(TimeZones.city(state.timeZoneId)),
                enabled = !state.autoTime,
            ) { nav.push(Screen.TimeZone) }
        }
        row {
            SettingsRow(
                "24-hour",
                "24-hour clock",
                if (state.is24Hour) "Times read like 21:45" else "Times read like 9:45 PM",
                SettingsIcons.Schedule,
                SettingsColors.Graphite,
                RowEnd.Switch(state.is24Hour),
            ) { change(SettingsScreen.DateTime) { put(SettingsTable.System, "time_12_24", if (state.is24Hour) "12" else "24") } }
        }
    }
    SettingsCard("Language") {
        row {
            SettingsRow(
                "language",
                "Language",
                "Changes in Android's own screen",
                SettingsIcons.Public,
                SettingsColors.Pink,
                RowEnd.External,
            ) { container.actions.openLanguageSettings() }
        }
    }
    StorageCard(state, context)
    SettingsCard("This TV") {
        row { SettingsRow("model", "Model", null, SettingsIcons.Tv, SettingsColors.Gray, RowEnd.Value(state.model)) {} }
        row {
            val patch = state.securityPatch?.let { " · security patch $it" } ?: ""
            SettingsRow("android-version", "Android version", null, SettingsIcons.Android, SettingsColors.Green, RowEnd.Value("${state.androidVersion}$patch")) {}
        }
    }
    SettingsCard("Developer options") {
        row {
            SettingsRow(
                "developer-options",
                "Developer options",
                "Needed for USB and network debugging",
                SettingsIcons.Code,
                SettingsColors.Graphite,
                RowEnd.Switch(state.developerOptions),
            ) { change(SettingsScreen.Main) { put(SettingsTable.Global, "development_settings_enabled", if (state.developerOptions) "0" else "1") } }
        }
        row {
            SettingsRow(
                "usb-debugging",
                "USB debugging",
                "Lets a computer run adb, and OwnTV's own commands run on this TV",
                SettingsIcons.Terminal,
                SettingsColors.Orange,
                RowEnd.Switch(state.usbDebugging),
                enabled = state.developerOptions,
            ) { change(SettingsScreen.Main) { put(SettingsTable.Global, "adb_enabled", if (state.usbDebugging) "0" else "1") } }
        }
    }
}

/** "12.4 GB free of 32 GB" with a bar; nothing to press. */
@Composable
private fun StorageCard(state: AndroidState, context: android.content.Context) {
    if (state.storageTotal <= 0) return
    val used = (state.storageTotal - state.storageFree).coerceAtLeast(0)
    Column {
        Text(
            "STORAGE",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = SettingsColors.Caption,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Column(Modifier.fillMaxWidth().background(SettingsColors.Card, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Formatter.formatShortFileSize(context, state.storageFree)} free",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsColors.Text,
                    modifier = Modifier.weight(1f),
                )
                Text("of ${Formatter.formatShortFileSize(context, state.storageTotal)}", style = MaterialTheme.typography.bodyMedium, color = SettingsColors.Muted)
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(SettingsColors.Divider)) {
                Box(Modifier.weight(used.toFloat().coerceAtLeast(1f)).height(8.dp).background(SettingsColors.Focus))
                Box(Modifier.weight(state.storageFree.toFloat().coerceAtLeast(1f)).size(0.dp))
            }
        }
    }
}

/** Settings > Android > System > Time zone: the cities, with the current one under the cursor. */
@Composable
fun TimeZoneScreen(container: AppContainer, nav: Navigator) {
    val zones = remember { TimeZones.listable(TimeZone.getAvailableIDs().toList()) }
    val current = remember { TimeZone.getDefault().id }
    val currentIndex = zones.indexOf(current).coerceAtLeast(0)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    SettingsScaffold("Time zone", "Pick the city the TV is in.") {
        LazyColumn(Modifier.fillMaxWidth(), state = list) {
            itemsIndexed(zones, key = { _, id -> id }) { index, id ->
                SettingsItem(
                    TimeZones.city(id),
                    if (id == current) "${TimeZones.region(id)} · current" else TimeZones.region(id),
                    modifier = if (index == currentIndex) rememberInitialFocus() else Modifier,
                ) {
                    scope.launch {
                        val problem = container.androidSettings.setTimeZone(id)
                        if (problem == null) {
                            nav.back()
                        } else {
                            container.actions.toast(problem)
                            container.actions.execute(OpenSystemSettingsAction(SettingsScreen.DateTime))
                        }
                    }
                }
            }
        }
    }
}

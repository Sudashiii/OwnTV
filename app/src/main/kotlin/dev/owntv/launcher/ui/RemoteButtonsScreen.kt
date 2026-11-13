package dev.owntv.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.adb.AdbCommands
import dev.owntv.launcher.actions.KeyProbe
import dev.owntv.launcher.actions.PressedKey
import dev.owntv.launcher.actions.RemoteService
import dev.owntv.launcher.actions.StandbyService
import dev.owntv.launcher.actions.keyCode
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.ui.render.ShortcutIcon
import dev.owntv.layout.edit.LayoutOps
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.IntentAction
import dev.owntv.layout.model.KeyPress
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.NextWallpaperAction
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.OpenInputAction
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.OpenQuickSettingsAction
import dev.owntv.layout.model.OpenSystemSettingsAction
import dev.owntv.layout.model.OpenUrlAction
import dev.owntv.layout.model.RemoteKey
import dev.owntv.layout.model.StandbyAction
import dev.owntv.layout.model.TvInput
import kotlinx.coroutines.launch

private const val PACKAGE = "dev.owntv.launcher"

fun remoteButtonsSummary(on: Boolean, bindings: Int): String = when {
    !on -> "Off: turn on \"OwnTV remote\" in Accessibility to use remote buttons from a layout"
    bindings == 0 -> "On · choose what your remote's buttons do"
    else -> "On · the layout binds $bindings button press(es) · change them here"
}

private enum class RemoteTab(val title: String) { Buttons("Buttons"), Setup("Setup") }

/**
 * Settings > Remote & standby > Remote buttons, in two tabs: Buttons shows every button a layout can bind as a card with what a
 * press and a hold do, jumps to the card of any button pressed on the remote, and changes it with OK (saved to
 * the layout through [LayoutOps.setKeyBinding]). Setup turns on OwnTV's remote service.
 */
@Composable
fun RemoteButtonsScreen(container: AppContainer, state: LayoutState) {
    val on by RemoteService.enabled.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (on) RemoteTab.Buttons else RemoteTab.Setup) }
    // While this screen is open the remote service lets every key through, so pressed buttons land here.
    DisposableEffect(Unit) {
        KeyProbe.clear()
        KeyProbe.listening = true
        onDispose {
            KeyProbe.listening = false
            KeyProbe.clear()
        }
    }
    SettingsScaffold("Remote buttons", "Give your remote's buttons a job of their own, also while another app is open.") {
        TabChips(tab, onSelect = { tab = it }, initialFocus = rememberInitialFocus())
        Spacer(Modifier.height(16.dp))
        when (tab) {
            RemoteTab.Buttons -> ButtonsTab(container, state, on, openSetup = { tab = RemoteTab.Setup })
            RemoteTab.Setup -> SetupTab(container, on)
        }
    }
}

/** Two pill tabs; focusing one selects it, as on Google TV. */
@Composable
private fun TabChips(selected: RemoteTab, onSelect: (RemoteTab) -> Unit, initialFocus: Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteTab.entries.forEach { tab ->
            var focused by remember { mutableStateOf(false) }
            val isSelected = tab == selected
            val background by animateColorAsState(
                when {
                    focused -> Color.White
                    isSelected -> Color(0xFF2C303A)
                    else -> Color.Transparent
                },
                tween(150),
                label = "tab",
            )
            Text(
                tab.title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    focused -> Color(0xFF101115)
                    isSelected -> Color.White
                    else -> Color(0xFFA9AFBA)
                },
                modifier = (if (isSelected) initialFocus else Modifier)
                    .background(background, RoundedCornerShape(50))
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) onSelect(tab)
                    }
                    .clickable(interactionSource = null, indication = null) { onSelect(tab) }
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Buttons tab

private val ColorKeys = listOf(RemoteKey.Red, RemoteKey.Green, RemoteKey.Yellow, RemoteKey.Blue)
private val OtherKeys = RemoteKey.entries - ColorKeys.toSet()

/** The steps of choosing what a button does, one dialog each. */
private sealed interface Picker {
    data class Key(val key: RemoteKey) : Picker
    data class Choose(val key: RemoteKey, val press: KeyPress) : Picker
    data class Apps(val key: RemoteKey, val press: KeyPress) : Picker
    data class Pages(val key: RemoteKey, val press: KeyPress) : Picker
    data class Inputs(val key: RemoteKey, val press: KeyPress) : Picker
}

@Composable
private fun ButtonsTab(container: AppContainer, state: LayoutState, on: Boolean, openSetup: () -> Unit) {
    val pressed by KeyProbe.last.collectAsStateWithLifecycle()
    val apps by container.apps.apps.collectAsStateWithLifecycle()
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    val doc = state.document
    val scope = rememberCoroutineScope()
    val requesters = remember { RemoteKey.entries.associateWith { FocusRequester() } }
    var seen by remember { mutableStateOf(emptySet<RemoteKey>()) }
    var picker by remember { mutableStateOf<Picker?>(null) }

    // A button pressed on the remote: remember that it arrives and jump to its card.
    LaunchedEffect(pressed) {
        val key = pressed?.let { p -> RemoteKey.entries.firstOrNull { it.keyCode() == p.keyCode } } ?: return@LaunchedEffect
        seen = seen + key
        if (picker == null) runCatching { requesters.getValue(key).requestFocus() }
    }

    fun bound(key: RemoteKey, press: KeyPress): Action? = doc.keyBindings.firstOrNull { it.key == key && it.press == press }?.action

    fun save(key: RemoteKey, press: KeyPress, action: Action?) {
        scope.launch {
            val current = container.layouts.state.value.document
            LayoutOps.setKeyBinding(current, key, press, action)?.let { container.layouts.apply(it) }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (!on) {
            SettingsItem("The remote service is off", "Buttons only do their job once \"OwnTV remote\" is on · open Setup", onClick = openSetup)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Press a button on your remote to jump to it, then OK to choose what it does. " + lastPressed(pressed),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFA9AFBA),
        )
        SectionLabel("Color buttons")
        KeyRow(ColorKeys) { key, modifier ->
            KeyCard(key, bound(key, KeyPress.Short), bound(key, KeyPress.Long), key in seen, labels, modifier.focusRequester(requesters.getValue(key))) {
                picker = Picker.Key(key)
            }
        }
        SectionLabel("More buttons")
        OtherKeys.chunked(4).forEach { row ->
            KeyRow(row) { key, modifier ->
                KeyCard(key, bound(key, KeyPress.Short), bound(key, KeyPress.Long), key in seen, labels, modifier.focusRequester(requesters.getValue(key))) {
                    picker = Picker.Key(key)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        HelpText(
            "Remotes differ: most have only some of these buttons, and voice and brand buttons never reach apps. " +
                "D-pad, OK, Back, Home and Power cannot be changed. Other actions (web links, intents) can be set in the layout's \"keyBindings\".",
        )
    }

    picker?.let { current ->
        val dismiss = { if (picker == current) picker = null }
        when (current) {
            is Picker.Key -> {
                val short = bound(current.key, KeyPress.Short)
                val long = bound(current.key, KeyPress.Long)
                ContextMenu(
                    "${current.key.label()} button",
                    buildList {
                        add("Press: ${short?.describe(labels) ?: "nothing"}" to { picker = Picker.Choose(current.key, KeyPress.Short) })
                        add("Hold: ${long?.describe(labels) ?: "nothing"}" to { picker = Picker.Choose(current.key, KeyPress.Long) })
                        if (short != null || long != null) {
                            add("Clear both" to {
                                scope.launch {
                                    val before = container.layouts.state.value.document
                                    val noPress = LayoutOps.setKeyBinding(before, current.key, KeyPress.Short, null) ?: before
                                    val cleared = LayoutOps.setKeyBinding(noPress, current.key, KeyPress.Long, null) ?: noPress
                                    if (cleared !== before) container.layouts.apply(cleared)
                                }
                            })
                        }
                    },
                    onDismiss = dismiss,
                )
            }
            is Picker.Choose -> {
                val (key, press) = current
                val now = bound(key, press)
                fun option(label: String, action: Action?, selected: Boolean = now == action): Pair<String, () -> Unit> =
                    (if (selected) "✓ $label" else label) to { save(key, press, action) }
                ContextMenu(
                    "${key.label()} · ${if (press == KeyPress.Long) "held" else "pressed"}",
                    buildList {
                        add(option("Nothing", null))
                        add(option("Open quick settings", OpenQuickSettingsAction))
                        add((if (now is LaunchAppAction) "✓ ${now.describe(labels)}…" else "Open an app…") to { picker = Picker.Apps(key, press) })
                        add((if (now is GoToPageAction) "✓ ${now.describe(labels)}…" else "Go to a page…") to { picker = Picker.Pages(key, press) })
                        add(option("Show all apps", OpenAppDrawerAction))
                        add(option("OwnTV settings", OpenLauncherSettingsAction))
                        add(option("Android settings", OpenSystemSettingsAction(), now is OpenSystemSettingsAction))
                        add((if (now is OpenInputAction) "✓ ${now.describe(labels)}…" else "Switch TV input…") to { picker = Picker.Inputs(key, press) })
                        add(option("Standby", StandbyAction))
                        add(option("Next wallpaper", NextWallpaperAction))
                        if (now is OpenUrlAction || now is IntentAction) add("✓ Keep: ${now.describe(labels)}" to {})
                    },
                    onDismiss = dismiss,
                )
            }
            is Picker.Apps -> ContextMenu(
                "Open an app",
                apps.filter { it.packageName != PACKAGE }.sortedBy { it.label.lowercase() }.map { app ->
                    app.label to { save(current.key, current.press, LaunchAppAction(app.packageName)) }
                },
                onDismiss = dismiss,
            )
            is Picker.Pages -> ContextMenu(
                "Go to a page",
                doc.pages.map { page ->
                    (page.title ?: page.id) to { save(current.key, current.press, GoToPageAction(page.id)) }
                },
                onDismiss = dismiss,
            )
            is Picker.Inputs -> ContextMenu(
                "Switch TV input",
                TvInput.entries.map { input ->
                    OpenInputAction(input).describe() to { save(current.key, current.press, OpenInputAction(input)) }
                },
                onDismiss = dismiss,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
        color = Color(0xFF8A909B),
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
    )
}

/** Up to four cards side by side, each a quarter of the width. */
@Composable
private fun KeyRow(keys: List<RemoteKey>, card: @Composable (RemoteKey, Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.forEach { card(it, Modifier.weight(1f)) }
        repeat(4 - keys.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** One button: its symbol and name, what a press and a hold do, and whether it arrived from this remote. */
@Composable
private fun KeyCard(
    key: RemoteKey,
    short: Action?,
    long: Action?,
    seen: Boolean,
    labels: Map<String, String>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150), label = "scale")
    val used = short != null || long != null
    val content = if (focused) Color(0xFF101115) else Color.White
    val muted = if (focused) Color(0xFF3A3F4A) else Color(0xFFA9AFBA)
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(if (focused) Color.White else Color(0xFF1C1F26), RoundedCornerShape(14.dp))
            .then(if (used && !focused) Modifier.border(1.dp, key.tint().copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeySymbol(key, content)
            Spacer(Modifier.width(10.dp))
            Text(
                key.label(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (seen) {
                Text("✓", style = MaterialTheme.typography.labelMedium, color = if (focused) Color(0xFF2E7D32) else Color(0xFF81C784))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!used) {
            Text("Not used", style = MaterialTheme.typography.bodySmall, color = muted)
            Text(" ", style = MaterialTheme.typography.bodySmall)
        } else {
            BindingLine("Press", short, labels, content, muted)
            BindingLine("Hold", long, labels, content, muted)
        }
    }
}

@Composable
private fun BindingLine(what: String, action: Action?, labels: Map<String, String>, content: Color, muted: Color) {
    Row {
        Text("$what  ", style = MaterialTheme.typography.bodySmall, color = muted)
        Text(
            action?.describe(labels) ?: "–",
            style = MaterialTheme.typography.bodySmall,
            color = if (action == null) muted else content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A colored dot for the color buttons, an icon or short mark for the others. */
@Composable
private fun KeySymbol(key: RemoteKey, content: Color) {
    // A fixed box, so every card's title row has the same height.
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (key in ColorKeys) {
            Box(Modifier.size(18.dp).background(key.tint(), CircleShape).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
        } else {
            when (val symbol = key.symbol()) {
                is KeyMark.Icon -> ShortcutIcon(symbol.name, 20.dp, tint = content)
                is KeyMark.Text -> Text(symbol.text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = content, maxLines = 1, softWrap = false)
            }
        }
    }
}

private sealed interface KeyMark {
    data class Icon(val name: String) : KeyMark
    data class Text(val text: String) : KeyMark
}

private fun RemoteKey.symbol(): KeyMark = when (this) {
    RemoteKey.Menu -> KeyMark.Icon("menu")
    RemoteKey.Guide -> KeyMark.Icon("live_tv")
    RemoteKey.Info -> KeyMark.Icon("info_outline")
    RemoteKey.Settings -> KeyMark.Icon("settings")
    RemoteKey.Captions -> KeyMark.Text("CC")
    RemoteKey.Bookmark -> KeyMark.Icon("star")
    RemoteKey.Tv -> KeyMark.Icon("tv")
    RemoteKey.TvInput -> KeyMark.Icon("input")
    RemoteKey.Dvr -> KeyMark.Text("REC")
    RemoteKey.ChannelUp -> KeyMark.Text("CH+")
    RemoteKey.ChannelDown -> KeyMark.Text("CH−")
    RemoteKey.LastChannel -> KeyMark.Text("↺")
    RemoteKey.Red, RemoteKey.Green, RemoteKey.Yellow, RemoteKey.Blue -> KeyMark.Text("")
}

private fun RemoteKey.tint(): Color = when (this) {
    RemoteKey.Red -> Color(0xFFE53935)
    RemoteKey.Green -> Color(0xFF43A047)
    RemoteKey.Yellow -> Color(0xFFFDD835)
    RemoteKey.Blue -> Color(0xFF1E88E5)
    else -> Color(0xFF7AA2F7)
}

private fun RemoteKey.label(): String = when (this) {
    RemoteKey.Menu -> "Menu"
    RemoteKey.Guide -> "Guide"
    RemoteKey.Info -> "Info"
    RemoteKey.Settings -> "Settings"
    RemoteKey.Captions -> "Captions"
    RemoteKey.Bookmark -> "Bookmark"
    RemoteKey.Tv -> "TV"
    RemoteKey.TvInput -> "Input"
    RemoteKey.Dvr -> "DVR"
    RemoteKey.ChannelUp -> "Channel +"
    RemoteKey.ChannelDown -> "Channel −"
    RemoteKey.LastChannel -> "Last channel"
    RemoteKey.Red -> "Red"
    RemoteKey.Green -> "Green"
    RemoteKey.Yellow -> "Yellow"
    RemoteKey.Blue -> "Blue"
}

private fun lastPressed(pressed: PressedKey?): String = when {
    pressed == null -> ""
    pressed.remappable -> "Last: ${pressed.name}."
    else -> "Last: ${pressed.name}, which cannot be changed."
}

// ---------------------------------------------------------------- Setup tab

@Composable
private fun SetupTab(container: AppContainer, on: Boolean) {
    val standbyOn by StandbyService.enabled.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            SettingsItem(
                "OwnTV remote",
                if (on) "On · OwnTV sees the buttons you press and acts only on the ones the layout binds" else "Off · open Accessibility and turn on \"OwnTV remote\"",
            ) { container.actions.openAccessibilitySettings() }
        }
        item {
            SettingsItem(
                "OwnTV standby",
                if (standbyOn) "On · standby, the sleep timer and the screensaver can switch the TV off" else "Off · needed for buttons and timers that switch the TV off",
            ) { container.actions.openAccessibilitySettings() }
        }
        if (!on) {
            item {
                HelpText(
                    "Android 13 and newer blocks accessibility services for apps that were not installed from a store, so the switch can be greyed out. " +
                        "Then open OwnTV's app info, choose the menu (the three dots) > Allow restricted settings, and go back to Accessibility.",
                )
            }
            item {
                SettingsItem("Open OwnTV app info", "Menu (⋮) > Allow restricted settings") { container.actions.openAppInfo(PACKAGE) }
            }
            item {
                HelpText("If your TV has no such menu, adb can allow it once (OK runs it on this TV, see Settings > Developer > ADB on this TV):")
                Code(AdbCommands.AllowRestrictedSettings.line)
            }
            item {
                HelpText(
                    "If your TV has no Accessibility screen at all, adb can turn on OwnTV's remote and standby services directly " +
                        "(this replaces other accessibility services you turned on):",
                )
                Code(AdbCommands.EnableRemoteServices.line)
            }
        }
        item {
            HelpText(
                "The remote service only acts on buttons the layout binds; every other button works as before. " +
                    "Nothing it sees is stored or sent, apart from when an app was last used.",
            )
        }
    }
}

/** A short, plain description of what [this] does, for lists of bindings. */
fun Action.describe(labels: Map<String, String> = emptyMap()): String = when (this) {
    is LaunchAppAction -> "Open ${labels[packageName] ?: packageName}"
    is OpenUrlAction -> "Open $url"
    is IntentAction -> "Start " + (action ?: component ?: packageName ?: "an intent")
    is GoToPageAction -> "Go to the page \"$page\""
    is OpenInputAction -> "Switch the TV to ${if (input == TvInput.Av) "AV" else "HDMI " + (input.ordinal + 1)}"
    OpenAppDrawerAction -> "Show all apps"
    is OpenSystemSettingsAction -> "Open the Android settings" + if (screen == dev.owntv.layout.model.SettingsScreen.Main) "" else " (${screen.name.lowercase()})"
    NextWallpaperAction -> "Next wallpaper"
    StandbyAction -> "Put the TV into standby"
    OpenLauncherSettingsAction -> "Open OwnTV's settings"
    OpenQuickSettingsAction -> "Open quick settings"
}

package dev.owntv.launcher.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.actions.KeyProbe
import dev.owntv.launcher.actions.RemoteService
import dev.owntv.launcher.actions.keyName
import dev.owntv.launcher.actions.runsInOwnTv
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.IntentAction
import dev.owntv.layout.model.KeyPress
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.NextWallpaperAction
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.OpenInputAction
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.OpenSystemSettingsAction
import dev.owntv.layout.model.OpenUrlAction
import dev.owntv.layout.model.StandbyAction

private const val PACKAGE = "dev.owntv.launcher"

fun remoteButtonsSummary(on: Boolean, bindings: Int): String = when {
    !on -> "Off: turn on \"OwnTV remote\" in Accessibility to use remote buttons from a layout"
    bindings == 0 -> "On, the layout binds no buttons · test your remote's buttons here"
    else -> "On · the layout binds $bindings button press(es) · test your remote's buttons here"
}

/** Settings > Remote buttons: the remote service, a test for which buttons arrive, and the layout's bindings. */
@Composable
fun RemoteButtonsScreen(container: AppContainer, state: LayoutState) {
    val on by RemoteService.enabled.collectAsStateWithLifecycle()
    val pressed by KeyProbe.last.collectAsStateWithLifecycle()
    val apps by container.apps.apps.collectAsStateWithLifecycle()
    val bindings = state.document.keyBindings
    DisposableEffect(Unit) {
        KeyProbe.clear()
        KeyProbe.listening = true
        onDispose {
            KeyProbe.listening = false
            KeyProbe.clear()
        }
    }
    val initialFocus = rememberInitialFocus()

    SettingsScaffold(
        "Remote buttons",
        "A layout can give remote buttons another job, also while another app is open. This needs OwnTV's remote service.",
    ) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                SettingsItem(
                    "OwnTV remote",
                    if (on) "On · OwnTV sees the buttons you press and acts only on the ones the layout binds" else "Off · tap to open Accessibility and turn on \"OwnTV remote\"",
                    modifier = initialFocus,
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
                    SettingsItem("Open Accessibility settings", "Turn on \"OwnTV remote\"") { container.actions.openAccessibilitySettings() }
                }
                item {
                    HelpText("If your TV has no such menu, a computer with adb can allow it once:")
                    Code("adb shell appops set $PACKAGE ACCESS_RESTRICTED_SETTINGS allow")
                }
            }
            item {
                SettingsItem("Press a button on your remote", pressedSummary(pressed, on)) {}
            }
            item {
                HelpText(
                    if (bindings.isEmpty()) "This layout binds no buttons. Add \"keyBindings\" to the layout (see the layout format) or ask the AI prompt for them."
                    else "Buttons this layout binds:",
                )
            }
            bindings.forEach { binding ->
                item {
                    SettingsItem(
                        "${binding.key.label()} · ${if (binding.press == KeyPress.Long) "held" else "pressed"}",
                        binding.action.describe(apps.associate { it.packageName to it.label }) + if (binding.action.runsInOwnTv()) " (opens OwnTV)" else "",
                    ) {}
                }
            }
            item {
                HelpText(
                    "Some buttons never reach apps, such as the voice and brand buttons on many remotes, and the D-pad, OK, Back, Home and Power cannot be changed. " +
                        "Press a button above to see whether it arrives.",
                )
            }
        }
    }
}

private fun pressedSummary(pressed: dev.owntv.launcher.actions.PressedKey?, on: Boolean): String = when {
    pressed == null -> if (on) "Last button: none yet" else "Last button: none yet (turn on the remote service to see buttons while other apps are open too)"
    else -> {
        val times = if (pressed.count > 1) " ×${pressed.count}" else ""
        val use = if (pressed.remappable) "a layout can use it as \"${keyName(pressed.keyCode)}\"" else "a layout cannot use it"
        "${pressed.name} (key code ${pressed.keyCode})$times · $use"
    }
}

private fun dev.owntv.layout.model.RemoteKey.label(): String =
    name.replace(Regex("([a-z])([A-Z])"), "$1 $2")

/** A short, plain description of what [this] does, for lists of bindings. */
fun Action.describe(labels: Map<String, String> = emptyMap()): String = when (this) {
    is LaunchAppAction -> "Open ${labels[packageName] ?: packageName}"
    is OpenUrlAction -> "Open $url"
    is IntentAction -> "Start " + (action ?: component ?: packageName ?: "an intent")
    is GoToPageAction -> "Go to the page \"$page\""
    is OpenInputAction -> "Switch the TV to ${input.name.uppercase()}"
    OpenAppDrawerAction -> "Show all apps"
    is OpenSystemSettingsAction -> "Open the Android settings (${screen.name.lowercase()})"
    NextWallpaperAction -> "Next wallpaper"
    StandbyAction -> "Put the TV into standby"
    OpenLauncherSettingsAction -> "Open OwnTV's settings"
}

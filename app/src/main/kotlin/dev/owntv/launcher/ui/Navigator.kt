package dev.owntv.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.owntv.launcher.data.layout.BuiltInRef
import dev.owntv.layout.codec.DecodeResult

sealed interface Screen {
    data object Home : Screen
    data object AppDrawer : Screen
    data object Settings : Screen
    data class Editor(val selectId: String? = null) : Screen
    data object Import : Screen
    data object Templates : Screen
    data object Looks : Screen
    data class LookPreview(val fileName: String) : Screen
    data object History : Screen
    data object Help : Screen
    data object Updates : Screen
    data object Performance : Screen
    data object RemoteButtons : Screen
    data object SelfAdb : Screen
    data object AdbCommands : Screen
    data class ListEditor(val name: String, val title: String?) : Screen
    data object HiddenApps : Screen
    /** [builtIn] is set for a look or template, so applying it is remembered (see `LayoutRepository.apply`). */
    data class Preview(val result: DecodeResult, val source: String, val builtIn: BuiltInRef? = null) : Screen
}

/** Screens that open in the Settings pane next to the sidebar; the others (editor, previews, app drawer) take the whole screen. */
val Screen.opensInSettingsPane: Boolean
    get() = when (this) {
        Screen.Import, Screen.Templates, Screen.Looks, Screen.History, Screen.Help, Screen.Updates, Screen.Performance,
        Screen.RemoteButtons, Screen.SelfAdb, Screen.AdbCommands, Screen.HiddenApps,
        -> true
        else -> false
    }

/** A small back stack. Home is always at the bottom. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    /** Page shown on Home; null means the first page. */
    var pageId by mutableStateOf<String?>(null)

    /** Bumped whenever Home should move focus back to the page's initial focus. */
    var focusTrigger by mutableIntStateOf(0)
        private set

    /** Settings group shown in the sidebar; kept while Settings is closed, like a Mac's System Settings. */
    var settingsGroup by mutableStateOf(SettingsGroup.General)

    /** Row of [settingsGroup] that opened the screen on top of Settings, so Back puts focus on it again. */
    var settingsRow by mutableStateOf<String?>(null)

    fun push(screen: Screen) {
        // Opened afresh (not returning to it), Settings starts in the sidebar.
        if (screen == Screen.Settings) settingsRow = null
        stack.add(screen)
    }

    /** Closes the screens open in the Settings pane, back to the group's rows. */
    fun closeSettingsDetail() {
        if (settingsDetail() == null) return
        while (current != Screen.Settings) stack.removeAt(stack.lastIndex)
    }

    /**
     * The screen shown in the Settings pane, beside the sidebar: the current screen, when Settings is below it with
     * only pane screens in between. Null when Settings shows its rows, or the current screen needs the whole screen.
     */
    fun settingsDetail(): Screen? {
        val settings = stack.lastIndexOf(Screen.Settings)
        if (settings < 0 || settings == stack.lastIndex) return null
        return current.takeIf { stack.subList(settings + 1, stack.size).all { it.opensInSettingsPane } }
    }

    fun showPreview(result: DecodeResult, source: String, builtIn: BuiltInRef? = null) {
        val screen = Screen.Preview(result, source, builtIn)
        if (current is Screen.Preview) stack[stack.lastIndex] = screen else push(screen)
    }

    /** Handles Back. Returns false when there is nowhere to go (Home, first page). */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            if (current == Screen.Home) focusTrigger++
            return true
        }
        if (pageId != null) {
            goToPage(null)
            return true
        }
        return false
    }

    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        pageId = null
        focusTrigger++
    }

    fun goToPage(id: String?) {
        pageId = id
        focusTrigger++
    }
}

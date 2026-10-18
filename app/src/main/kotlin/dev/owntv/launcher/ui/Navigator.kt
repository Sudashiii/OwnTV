package dev.owntv.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.owntv.layout.codec.DecodeResult

sealed interface Screen {
    data object Home : Screen
    data object AppDrawer : Screen
    data object Settings : Screen
    data object Import : Screen
    data object Templates : Screen
    data object Looks : Screen
    data class LookPreview(val fileName: String) : Screen
    data object History : Screen
    data object Help : Screen
    data class Preview(val result: DecodeResult, val source: String) : Screen
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

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun showPreview(result: DecodeResult, source: String) {
        if (current is Screen.Preview) stack[stack.lastIndex] = Screen.Preview(result, source) else push(Screen.Preview(result, source))
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

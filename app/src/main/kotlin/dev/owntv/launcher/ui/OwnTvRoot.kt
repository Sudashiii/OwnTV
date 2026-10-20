package dev.owntv.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.ui.editor.EditorScreen
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.LocalInteractiveSurfaces

@Composable
fun OwnTvRoot(container: AppContainer, nav: Navigator) {
    val layoutState by container.layouts.state.collectAsStateWithLifecycle()
    val apps by container.apps.apps.collectAsStateWithLifecycle()
    val appsLoaded by container.apps.loaded.collectAsStateWithLifecycle()
    val recent by container.recent.recent.collectAsStateWithLifecycle()
    val catalog = remember(apps, appsLoaded, recent) { AppCatalog(apps, appsLoaded, recent) }

    // The phone paste page runs while Import (or a preview opened from it) is on the stack.
    val importOpen = nav.stack.any { it is Screen.Import }
    DisposableEffect(importOpen) {
        if (importOpen) container.paste.start()
        onDispose { if (importOpen) container.paste.stop() }
    }
    LaunchedEffect(Unit) {
        container.paste.received.collect { result -> nav.showPreview(result, "Phone") }
    }

    BackHandler { nav.back() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalInteractiveSurfaces provides TvSurfaces) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                when (val screen = nav.current) {
                    Screen.Home -> HomeScreen(container, nav, layoutState, catalog)
                    Screen.AppDrawer -> AppDrawerScreen(container, nav, layoutState, catalog)
                    Screen.Settings -> SettingsScreen(container, nav, layoutState)
                    is Screen.Editor -> EditorScreen(container, nav, layoutState, catalog, screen.selectId)
                    Screen.Import -> ImportScreen(container, nav)
                    Screen.Templates -> TemplatesScreen(container, nav)
                    Screen.Looks -> LooksScreen(container, nav)
                    is Screen.LookPreview -> LookPreviewScreen(container, nav, screen.fileName, layoutState, catalog)
                    Screen.History -> HistoryScreen(container, nav)
                    Screen.Help -> HelpScreen(container)
                    Screen.Updates -> UpdatesScreen(container)
                    is Screen.Preview -> PreviewScreen(container, nav, screen.result, screen.source, catalog)
                }
            }
        }
    }
}

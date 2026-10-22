package dev.owntv.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.metrics.MetricsMode
import dev.owntv.launcher.ui.editor.EditorScreen
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.LocalAppArranger
import dev.owntv.launcher.ui.render.LocalBackgroundPaused
import dev.owntv.launcher.ui.render.LocalInteractiveSurfaces
import dev.owntv.launcher.ui.render.LocalNetworkState
import dev.owntv.launcher.ui.render.LocalRenderMetrics
import dev.owntv.launcher.ui.render.LocalVideoPlayers
import dev.owntv.launcher.ui.render.LocalWallpaperFolders
import dev.owntv.launcher.ui.render.LocalWatchNext

@Composable
fun OwnTvRoot(container: AppContainer, nav: Navigator) {
    val layoutState by container.layouts.state.collectAsStateWithLifecycle()
    val apps by container.apps.apps.collectAsStateWithLifecycle()
    val appsLoaded by container.apps.loaded.collectAsStateWithLifecycle()
    val recent by container.recent.recent.collectAsStateWithLifecycle()
    val lists by container.appLists.lists.collectAsStateWithLifecycle()
    val watchNext by container.watchNext.state.collectAsStateWithLifecycle()
    val catalog = remember(apps, appsLoaded, recent, lists) { AppCatalog(apps, appsLoaded, recent, lists = lists) }
    val arranger = remember(container) { LauncherArranger(container.appLists) }
    val metricsMode by container.perf.mode.collectAsStateWithLifecycle()
    val screenLabel = when (val screen = nav.current) {
        Screen.Home -> "Home" + (nav.pageId ?: layoutState.document.pages.firstOrNull()?.id)?.let { " ($it)" }.orEmpty()
        // R8 keeps the class names but not the nesting, so "Screen$Settings" is cut to "Settings".
        else -> screen::class.simpleName?.substringAfterLast('$') ?: "Screen"
    }
    SideEffect { container.perf.screen = screenLabel }

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
        CompositionLocalProvider(
            LocalInteractiveSurfaces provides TvSurfaces,
            LocalAppArranger provides arranger,
            LocalWatchNext provides watchNext,
            LocalNetworkState provides rememberNetworkState(),
            LocalRenderMetrics provides container.perf.takeIf { metricsMode != MetricsMode.Off },
            LocalVideoPlayers provides ExoVideoPlayers,
            LocalWallpaperFolders provides container.wallpaperFolders,
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                // Home stays composed under the other screens, so going back does not build the whole layout
                // again (that took most of a second). While covered it is measured but not placed: it does not
                // draw and focus search skips it.
                Box(Modifier.fillMaxSize().placedOnlyIf(nav.current == Screen.Home)) {
                    CompositionLocalProvider(LocalBackgroundPaused provides (nav.current != Screen.Home)) {
                        HomeScreen(container, nav, layoutState, catalog, arranger)
                    }
                }
                when (val screen = nav.current) {
                    Screen.Home -> {}
                    Screen.AppDrawer -> AppDrawerScreen(container, nav, layoutState, catalog, arranger)
                    Screen.Settings -> SettingsScreen(container, nav, layoutState)
                    is Screen.Editor -> EditorScreen(container, nav, layoutState, catalog, screen.selectId)
                    Screen.Import -> ImportScreen(container, nav)
                    Screen.Templates -> TemplatesScreen(container, nav)
                    Screen.Looks -> LooksScreen(container, nav)
                    is Screen.LookPreview -> LookPreviewScreen(container, nav, screen.fileName, layoutState, catalog)
                    Screen.History -> HistoryScreen(container, nav)
                    Screen.Help -> HelpScreen(container)
                    Screen.Updates -> UpdatesScreen(container)
                    Screen.Performance -> PerformanceScreen(container)
                    is Screen.ListEditor -> ListEditorScreen(container, screen.name, screen.title, catalog)
                    Screen.HiddenApps -> HiddenAppsScreen(container, catalog)
                    is Screen.Preview -> PreviewScreen(container, nav, screen.result, screen.source, screen.builtIn, catalog)
                }
                if (metricsMode == MetricsMode.Live) {
                    PerformanceOverlay(container.perf, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                }
            }
        }
    }
}

/** Measures the content but places it only when [placed]; unplaced content keeps its state and does not draw. */
private fun Modifier.placedOnlyIf(placed: Boolean) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (placed) placeable.place(0, 0) }
}

package dev.owntv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.data.apps.AppInfo
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.ui.render.ActionHandler
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.ContextTarget
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalActionHandler
import dev.owntv.launcher.ui.render.LocalAppImages
import dev.owntv.launcher.ui.render.LocalApps
import dev.owntv.launcher.ui.render.rendersFocusable
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.Padding
import dev.owntv.layout.model.Page
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.TileShape
import dev.owntv.layout.model.TileSize
import dev.owntv.layout.model.TileStyle

/** Handles widget clicks for the live launcher. */
@Composable
private fun rememberActionHandler(container: AppContainer, nav: Navigator, onLongPress: (ContextTarget) -> Unit): ActionHandler =
    remember(container, nav) {
        object : ActionHandler {
            override fun perform(action: Action) {
                when (action) {
                    is GoToPageAction -> nav.goToPage(action.page)
                    OpenAppDrawerAction -> nav.push(Screen.AppDrawer)
                    OpenLauncherSettingsAction -> nav.push(Screen.Settings)
                    else -> container.actions.execute(action)
                }
            }

            override fun openApp(app: AppInfo) {
                container.actions.launchApp(app.packageName)
            }

            override fun openMissing(packageName: String) {
                container.actions.openStorePage(packageName)
            }

            override fun longPress(target: ContextTarget) = onLongPress(target)
        }
    }

@Composable
fun HomeScreen(container: AppContainer, nav: Navigator, state: LayoutState, catalog: AppCatalog) {
    var menuTarget by remember { mutableStateOf<ContextTarget?>(null) }
    val handler = rememberActionHandler(container, nav) { menuTarget = it }

    if (!state.loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    CompositionLocalProvider(
        LocalActionHandler provides handler,
        LocalApps provides catalog,
        LocalAppImages provides container.images,
    ) {
        Box(Modifier.fillMaxSize()) {
            LayoutView(
                doc = state.document,
                pageId = nav.pageId,
                onSelectPage = { nav.pageId = it },
                focusTrigger = nav.focusTrigger,
            )

            // A layout must never lock the user out: if nothing on the page can take focus, offer settings.
            val page = state.document.pages.firstOrNull { it.id == nav.pageId } ?: state.document.pages.first()
            val hasFocusable = remember(page, catalog) { page.root.rendersFocusable(catalog) }
            if (catalog.loaded && !hasFocusable) {
                FallbackButton(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.align(Alignment.BottomCenter))
            }

            state.problem?.let { ProblemBanner(it, Modifier.align(Alignment.BottomStart)) }
        }
    }

    menuTarget?.let { target ->
        ContextMenu(
            target = target,
            onDismiss = { menuTarget = null },
            onOpen = { target.app?.let { handler.openApp(it) } },
            onAppInfo = { target.packageName?.let(container.actions::openAppInfo) },
            onLauncherSettings = { nav.push(Screen.Settings) },
        )
    }
}

@Composable
private fun FallbackButton(onClick: () -> Unit, modifier: Modifier) {
    val requester = rememberInitialFocus()
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nothing on this page can be selected.", color = Color.White)
        Button(onClick = onClick, modifier = Modifier.padding(top = 12.dp).then(requester)) {
            Text("Open launcher settings")
        }
    }
}

@Composable
private fun ProblemBanner(text: String, modifier: Modifier) {
    Box(
        modifier
            .padding(24.dp)
            .widthIn(max = 520.dp)
            .background(Color(0xE6331A1A), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFD7D7))
    }
}

/** The built-in "all apps" screen, rendered with the active layout's theme. */
@Composable
fun AppDrawerScreen(container: AppContainer, nav: Navigator, state: LayoutState, catalog: AppCatalog) {
    val drawer = remember(state.document.theme) {
        LayoutDocument(
            name = "All apps",
            theme = state.document.theme,
            background = state.document.background,
            pages = listOf(
                Page(
                    id = "drawer",
                    root = ColumnNode(
                        id = "drawer_root",
                        padding = Padding.symmetric(48, 28),
                        spacing = 16,
                        scroll = true,
                        children = listOf(
                            TextNode(id = "drawer_title", text = "All apps", style = TextStyleName.Headline),
                            AppGridNode(
                                id = "drawer_grid",
                                source = AllAppsSource(includeNonTvApps = true),
                                columns = 7,
                                tile = TileStyle(style = TileShape.Icon, size = TileSize.Medium),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
    var menuTarget by remember { mutableStateOf<ContextTarget?>(null) }
    val handler = rememberActionHandler(container, nav) { menuTarget = it }
    CompositionLocalProvider(
        LocalActionHandler provides handler,
        LocalApps provides catalog,
        LocalAppImages provides container.images,
    ) {
        LayoutView(drawer, pageId = null, onSelectPage = {}, focusTrigger = 0)
    }
    menuTarget?.let { target ->
        ContextMenu(
            target = target,
            onDismiss = { menuTarget = null },
            onOpen = { target.app?.let { handler.openApp(it) } },
            onAppInfo = { target.packageName?.let(container.actions::openAppInfo) },
            onLauncherSettings = { nav.push(Screen.Settings) },
        )
    }
}

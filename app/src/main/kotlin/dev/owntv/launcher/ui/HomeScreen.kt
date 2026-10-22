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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.actions.StandbyService
import dev.owntv.launcher.ui.render.AppInfo
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.ui.render.ActionHandler
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.ContextTarget
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalActionHandler
import dev.owntv.launcher.ui.render.LocalAppImages
import dev.owntv.launcher.ui.render.LocalApps
import dev.owntv.launcher.ui.render.rememberResolved
import dev.owntv.launcher.ui.render.rendersFocusable
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.AllAppsSource
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.ColumnNode
import dev.owntv.layout.model.GoToPageAction
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.ListRef
import dev.owntv.layout.model.OpenAppDrawerAction
import dev.owntv.layout.model.OpenLauncherSettingsAction
import dev.owntv.layout.model.NodeCommon
import dev.owntv.layout.model.Padding
import dev.owntv.layout.model.StandbyAction
import dev.owntv.layout.model.Page
import dev.owntv.layout.model.TextNode
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.TileShape
import dev.owntv.layout.model.TileSize
import dev.owntv.layout.model.TileStyle
import dev.owntv.layout.model.WatchNextEntry
import dev.owntv.layout.model.screensaverPage
import kotlinx.coroutines.launch

/** Handles widget clicks for the live launcher. */
@Composable
private fun rememberActionHandler(
    container: AppContainer,
    nav: Navigator,
    goToPage: (String) -> Unit = nav::goToPage,
    standby: () -> Unit = {},
    onLongPress: (ContextTarget) -> Unit,
): ActionHandler {
    val currentGoToPage by rememberUpdatedState(goToPage)
    val currentStandby by rememberUpdatedState(standby)
    return remember(container, nav) {
        object : ActionHandler {
            override fun perform(action: Action) {
                when (action) {
                    is GoToPageAction -> currentGoToPage(action.page)
                    StandbyAction -> currentStandby()
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

            override fun openWatchNext(entry: WatchNextEntry) = container.watchNext.open(entry)

            override fun allowWatchNext() = container.watchNext.requestAccess()

            override fun editList(ref: ListRef, title: String?) {
                if (ref is ListRef.Named) nav.push(Screen.ListEditor(ref.name, title))
            }
        }
    }
}

@Composable
fun HomeScreen(container: AppContainer, nav: Navigator, state: LayoutState, catalog: AppCatalog, arranger: LauncherArranger, idle: IdleTracker) {
    var menuTarget by remember { mutableStateOf<ContextTarget?>(null) }
    // The device sleeps through OwnTV's standby service; without it the screen goes black until a key is pressed.
    fun standby(asked: Boolean) {
        if (StandbyService.sleep()) return
        idle.blackout()
        if (asked) container.actions.toast("To switch the TV off, turn on \"OwnTV standby\" in Settings > Accessibility")
    }
    // A go_to_page action to the screensaver page starts the screensaver.
    val handler = rememberActionHandler(
        container,
        nav,
        goToPage = { id -> if (id == state.document.screensaverPage?.id) idle.start() else nav.goToPage(id) },
        standby = { standby(asked = true) },
    ) { menuTarget = it }
    val scope = rememberCoroutineScope()
    val builtInUpdate by container.layouts.builtInUpdate.collectAsStateWithLifecycle()
    // Home stays composed under other screens, so only ask while it is the one the user sees.
    val updatePrompt = builtInUpdate?.takeIf { !it.dismissed && nav.current == Screen.Home && menuTarget == null }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()

    if (!state.loaded) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (catalog.loaded) LaunchedEffect(Unit) { container.perf.homeReady() }

    CompositionLocalProvider(
        LocalActionHandler provides handler,
        LocalApps provides catalog,
        LocalAppImages provides container.images,
    ) {
        val screensaverAllowed = nav.current == Screen.Home && menuTarget == null && updatePrompt == null &&
            catalog.loaded && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        ScreensaverHost(state.document, idle, screensaverAllowed, onStandby = { standby(asked = false) }) {
            Box(Modifier.fillMaxSize()) {
                LayoutView(
                    doc = state.document,
                    pageId = nav.pageId,
                    onSelectPage = { nav.pageId = it },
                    focusTrigger = nav.focusTrigger,
                )

                // A layout must never lock the user out: if nothing on the page can take focus, offer settings.
                val resolved = rememberResolved(state.document)
                val page = resolved.pages.firstOrNull { it.id == nav.pageId } ?: resolved.pages.first()
                val pageCatalog = catalog.withOverrides(resolved.apps)
                val watchNext by container.watchNext.state.collectAsStateWithLifecycle()
                val hasFocusable = remember(page, pageCatalog, watchNext) { page.root.rendersFocusable(pageCatalog, watchNext) }
                if (catalog.loaded && !hasFocusable) {
                    FallbackButton(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.align(Alignment.BottomCenter))
                }

                state.problem?.let { ProblemBanner(it, Modifier.align(Alignment.BottomStart)) }
            }
        }
    }

    menuTarget?.let { target ->
        TileMenu(
            target = target,
            container = container,
            nav = nav,
            doc = state.document,
            arranger = arranger,
            onDismiss = { menuTarget = null },
            onEditLayout = { nav.push(Screen.Editor(target.nodeId)) },
        )
    }

    updatePrompt?.let { update ->
        BuiltInUpdatePrompt(
            update,
            onUpdate = {
                scope.launch {
                    if (container.layouts.applyBuiltInUpdate()) {
                        container.actions.toast("Updated to the new \"${update.name}\" ${update.ref.kind.label}")
                        nav.home()
                    }
                }
            },
            onLater = { scope.launch { container.layouts.dismissBuiltInUpdate() } },
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
fun AppDrawerScreen(container: AppContainer, nav: Navigator, state: LayoutState, catalog: AppCatalog, arranger: LauncherArranger) {
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
                        spacing = 16,
                        common = NodeCommon(padding = Padding.symmetric(48, 28)),
                        scroll = true,
                        children = listOf(
                            TextNode(id = "drawer_title", text = "All apps", textStyle = TextStyleName.Headline),
                            AppGridNode(
                                id = "drawer_grid",
                                source = AllAppsSource(includeNonTvApps = true),
                                columns = 7,
                                tile = TileStyle(shape = TileShape.Icon, size = TileSize.Medium),
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
        LocalApps provides catalog.withShowHidden(true),
        LocalAppImages provides container.images,
    ) {
        LayoutView(drawer, pageId = null, onSelectPage = {}, focusTrigger = 0)
    }
    menuTarget?.let { target ->
        TileMenu(
            target = target,
            container = container,
            nav = nav,
            doc = state.document,
            arranger = arranger,
            onDismiss = { menuTarget = null },
            onEditLayout = null,
        )
    }
}

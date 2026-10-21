package dev.owntv.launcher.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Page
import dev.owntv.layout.model.StyleResolver
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.model.referencedIds
import kotlinx.coroutines.delay

/** The layout with styles and type defaults applied, ready to render. */
@Composable
fun rememberResolved(doc: LayoutDocument): LayoutDocument =
    remember(doc) { runCatching { StyleResolver.resolve(doc) }.getOrDefault(doc) }

/**
 * Renders a whole layout: background, optional page tabs and the current page.
 * [focusTrigger] changes whenever the page's initial focus should be applied again.
 */
@Composable
fun LayoutView(
    doc: LayoutDocument,
    pageId: String?,
    onSelectPage: (String) -> Unit,
    focusTrigger: Int,
    modifier: Modifier = Modifier,
) {
    val resolved = rememberResolved(doc)
    val fonts = LocalFontFamilies.current
    val theme = remember(resolved.theme, fonts) { OwnTheme(resolved.theme, fonts) }
    val page = resolved.pages.firstOrNull { it.id == pageId } ?: resolved.pages.first()
    val catalog = LocalApps.current.withOverrides(resolved.apps)
    val haze = rememberHazeState()
    CompositionLocalProvider(LocalOwnTheme provides theme, LocalApps provides catalog, LocalHazeState provides haze) {
        Box(modifier.fillMaxSize()) {
            val background = page.background ?: resolved.background
            val backgroundModifier = Modifier.fillMaxSize().glassSource(0f)
            if (background != null) BackgroundFill(background, backgroundModifier) else Box(backgroundModifier.background(theme.background))
            Column(Modifier.fillMaxSize()) {
                if (resolved.pageTabs && resolved.pages.size > 1) {
                    PageTabs(resolved.pages, page.id, onSelectPage)
                }
                PageView(page, focusTrigger, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PageView(page: Page, focusTrigger: Int, modifier: Modifier) {
    val preview = LocalPreviewMode.current
    val appsLoaded = LocalApps.current.loaded
    val registry = remember(page) { FocusRegistry(page.referencedIds()) }
    val tracker = remember(page.id) { FocusTracker() }
    val pageRequester = remember(page.id) { FocusRequester() }
    Box(modifier.focusRequester(pageRequester).focusGroup()) {
        CompositionLocalProvider(
            LocalFocusRegistry provides registry,
            LocalFocusTracker provides tracker,
            LocalPage provides page,
            LocalPageTitle provides page.title,
            LocalAncestors provides emptyList(),
        ) {
            RenderNode(page.root, fillByDefault = true)
        }
    }
    if (!preview) {
        // Keyed on the trigger, not the page: switching pages with the tabs must keep focus on the tabs.
        LaunchedEffect(focusTrigger, appsLoaded) {
            withFrameNanos { }
            if (!registry.focus(page.initialFocus)) runCatching { pageRequester.requestFocus() }
        }
    }
}

@Composable
private fun PageTabs(pages: List<Page>, selectedId: String, onSelect: (String) -> Unit) {
    val theme = LocalOwnTheme.current
    val surfaces = LocalInteractiveSurfaces.current
    val preview = LocalPreviewMode.current || surfaces == null
    // A tab switches the page only after holding focus briefly. Focus can land on a tab for a
    // moment when a go_to_page action removes the focused tile; that must not switch back.
    var focusedTab by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedTab) {
        val id = focusedTab ?: return@LaunchedEffect
        delay(TAB_SELECT_DELAY_MS)
        onSelect(id)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        pages.forEach { page ->
            val selected = page.id == selectedId
            val label = page.title ?: page.id
            val textColor = if (selected) theme.text else theme.textMuted
            if (preview) {
                Box(Modifier.background(if (selected) theme.surface else Color.Transparent, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(label, style = theme.textStyle(TextStyleName.Body, bold = selected), color = textColor)
                }
            } else {
                surfaces!!.PageTab(
                    onClick = { onSelect(page.id) },
                    selected = selected,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) focusedTab = page.id else if (focusedTab == page.id) focusedTab = null
                    },
                ) { contentColor ->
                    Text(
                        label,
                        style = theme.textStyle(TextStyleName.Body, bold = selected),
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private const val TAB_SELECT_DELAY_MS = 250L

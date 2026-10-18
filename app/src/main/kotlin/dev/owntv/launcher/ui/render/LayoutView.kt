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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.owntv.layout.model.Background
import dev.owntv.layout.model.ColorBackground
import dev.owntv.layout.model.GradientBackground
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.Page
import dev.owntv.layout.model.TextStyleName
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
    val theme = remember(doc.theme) { OwnTheme(doc.theme) }
    val page = doc.pages.firstOrNull { it.id == pageId } ?: doc.pages.first()
    CompositionLocalProvider(LocalOwnTheme provides theme) {
        Box(modifier.fillMaxSize()) {
            BackgroundLayer(page.background ?: doc.background, theme)
            Column(Modifier.fillMaxSize()) {
                if (doc.pageTabs && doc.pages.size > 1) {
                    PageTabs(doc.pages, page.id, onSelectPage)
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
    val targets = remember(page.id, page.initialFocus) { FocusTargets(page.initialFocus) }
    val pageRequester = remember(page.id) { FocusRequester() }
    Box(
        modifier
            .focusRequester(pageRequester)
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalFocusTargets provides targets) {
            RenderNode(page.root, fillByDefault = true)
        }
    }
    if (!preview) {
        // Keyed on the trigger, not the page: switching pages with the tabs must keep focus on the tabs.
        LaunchedEffect(focusTrigger, appsLoaded) {
            withFrameNanos { }
            if (!targets.tryFocus()) runCatching { pageRequester.requestFocus() }
        }
    }
}

@Composable
private fun PageTabs(pages: List<Page>, selectedId: String, onSelect: (String) -> Unit) {
    val theme = LocalOwnTheme.current
    val preview = LocalPreviewMode.current
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
                Surface(
                    onClick = { onSelect(page.id) },
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) focusedTab = page.id else if (focusedTab == page.id) focusedTab = null
                    },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) theme.surface else Color.Transparent,
                        contentColor = textColor,
                        focusedContainerColor = theme.text,
                        focusedContentColor = theme.background,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                ) {
                    Text(
                        label,
                        style = theme.textStyle(TextStyleName.Body, bold = selected),
                        color = LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private const val TAB_SELECT_DELAY_MS = 250L

@Composable
fun BackgroundLayer(background: Background?, theme: OwnTheme) {
    val fill = Modifier.fillMaxSize()
    when (background) {
        null -> Box(fill.background(theme.background))
        is ColorBackground -> Box(fill.background(theme.color(background.color) ?: theme.background))
        is GradientBackground -> {
            val colors = background.colors.mapNotNull { theme.color(it) }.ifEmpty { listOf(theme.background, theme.background) }
            val radians = Math.toRadians(background.angle.toDouble())
            Box(
                fill.drawBehind {
                    // CSS-style angle: 0 = bottom to top, 90 = left to right.
                    val dx = sin(radians).toFloat()
                    val dy = -cos(radians).toFloat()
                    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawRect(Brush.linearGradient(colors, start = c - Offset(dx * half, dy * half), end = c + Offset(dx * half, dy * half)))
                },
            )
        }
        is ImageBackground -> Box(fill.background(theme.background)) {
            AsyncImage(
                model = imageModel(background.src),
                contentDescription = null,
                contentScale = background.fit.toContentScale(),
                modifier = fill,
            )
            Box(fill.background(Color.Black.copy(alpha = background.dim)))
        }
    }
}

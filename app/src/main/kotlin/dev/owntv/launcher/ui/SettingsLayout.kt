package dev.owntv.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.R

/** The groups in the Settings sidebar. Each one fills the pane on the right with its rows. */
enum class SettingsGroup(val title: String, val description: String, val icon: ImageVector, val tint: Color) {
    General(
        "General",
        "Updates, storage, making OwnTV your Home screen and the TV's own settings.",
        Icons.Filled.Settings,
        SettingsColors.Gray,
    ),
    Layout(
        "Layout",
        "Edit your home screen, switch to another look or template, import layouts and go back to earlier ones.",
        SettingsIcons.Dashboard,
        SettingsColors.Blue,
    ),
    Apps("Apps", "Every installed app, the apps you hid and how recent apps are counted.", SettingsIcons.Apps, SettingsColors.Green),
    Remote(
        "Remote & standby",
        "Give your remote's buttons a job of their own and let layouts switch the TV off.",
        SettingsIcons.Remote,
        SettingsColors.Purple,
    ),
    Developer("Developer", "Run adb commands on this TV, see every command OwnTV uses and measure how smoothly OwnTV runs.", SettingsIcons.Code, SettingsColors.Orange),
}

/** Sidebar blocks, separated by a gap like the sections of a Mac's System Settings. */
private val SidebarBlocks = listOf(
    listOf(SettingsGroup.General),
    listOf(SettingsGroup.Layout, SettingsGroup.Apps, SettingsGroup.Remote),
    listOf(SettingsGroup.Developer),
)

object SettingsColors {
    val Blue = Color(0xFF3A82F7)
    val Green = Color(0xFF34B65A)
    val Purple = Color(0xFF8E5CF0)
    val Indigo = Color(0xFF5E5CE6)
    val Pink = Color(0xFFE5508A)
    val Teal = Color(0xFF2AA5C9)
    val Orange = Color(0xFFF0892A)
    val Red = Color(0xFFEB4D3D)
    val Gray = Color(0xFF8A8D96)
    val Graphite = Color(0xFF4A4D55)

    val Sidebar = Color(0xFF1A1C21)
    val Card = Color(0xFF1C1E24)
    val Divider = Color(0xFF2C2F37)
    val SidebarSelected = Color(0xFF30333B)
    val Focus = Color(0xFF2F74E8)
    val Text = Color(0xFFF2F3F5)
    val Muted = Color(0xFFA9AFBA)
}

/** What a row needs from the Settings screen: which row gets focus back, and telling it which row opened something. */
private class SettingsRowHost(restoreKey: String?, val onOpen: (String) -> Unit) {
    /** Cleared after the first try, so showing the group again later does not pull focus to that row. */
    var restoreKey by mutableStateOf(restoreKey)
}

private val LocalSettingsRowHost = staticCompositionLocalOf<SettingsRowHost?> { null }

/** True inside the right pane of Settings: [SettingsScaffold] then draws a sub-page header instead of a full screen. */
val LocalInSettingsPane = staticCompositionLocalOf { false }

/**
 * Two panes like a Mac's System Settings: groups on the left, the selected group's rows on the right.
 * Focusing a group shows it; Right or OK moves into its rows and Left goes back to the group.
 *
 * A screen opened from a row ([detail], identified by [detailKey]) takes the place of the rows, with the sidebar
 * still showing; Back closes it again. Moving to another group in the sidebar closes it too.
 */
@Composable
fun SettingsSplitView(
    nav: Navigator,
    title: String,
    subtitle: String,
    badges: Set<SettingsGroup>,
    detailKey: Any?,
    detail: @Composable () -> Unit,
    content: @Composable ColumnScope.(SettingsGroup) -> Unit,
) {
    val group = nav.settingsGroup
    val showingRows = detailKey == null
    // After Back from a screen opened here, focus goes to the row that opened it; otherwise (opened afresh) to the group.
    val firstRestore = remember { nav.settingsRow }
    val focusSidebarFirst = remember { firstRestore == null && showingRows }
    val sidebarFocus = remember { SettingsGroup.entries.associateWith { FocusRequester() } }
    val paneFocus = remember { FocusRequester() }
    // New each time the rows come back, holding the row to focus again.
    val host = remember(showingRows) { SettingsRowHost(nav.settingsRow.takeIf { showingRows }) { nav.settingsRow = it } }
    var hasFocus by remember { mutableStateOf(false) }
    if (host.restoreKey != null) {
        // The row may be gone (a disabled history, an update that was applied); then the sidebar takes focus.
        LaunchedEffect(host) {
            repeat(3) { withFrameNanos { } }
            host.restoreKey = null
            if (!hasFocus) runCatching { sidebarFocus.getValue(group).requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .background(ScreenBackground)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Column(
            Modifier
                .width(248.dp)
                .fillMaxHeight()
                .background(SettingsColors.Sidebar, RoundedCornerShape(18.dp))
                .padding(12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                        runCatching { paneFocus.requestFocus() }
                        true
                    } else {
                        false
                    }
                }
                .focusProperties { onExit = { if (requestedFocusDirection.isVertical()) cancelFocusChange() } }
                // Coming back from an open screen with Left lands on the selected group, not the nearest one.
                .focusRestorer(sidebarFocus.getValue(group))
                .focusGroup(),
        ) {
            SidebarHeader(title, subtitle)
            SidebarBlocks.forEachIndexed { index, block ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                block.forEach { entry ->
                    SidebarItem(
                        entry,
                        selected = entry == group,
                        badge = entry in badges,
                        modifier = Modifier
                            .focusRequester(sidebarFocus.getValue(entry))
                            .then(if (focusSidebarFirst && entry == group) rememberInitialFocus() else Modifier),
                        onFocus = {
                            if (entry != nav.settingsGroup) {
                                nav.closeSettingsDetail()
                                nav.settingsGroup = entry
                            }
                        },
                        onClick = { runCatching { paneFocus.requestFocus() } },
                    )
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        if (!showingRows) {
            // The open screen: Left and Right move as the screen needs; Left from its left edge reaches the sidebar.
            key(detailKey) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(paneFocus)
                        .focusProperties { onExit = { if (requestedFocusDirection.isVertical()) cancelFocusChange() } }
                        .focusRestorer()
                        .focusGroup()
                        .padding(horizontal = 8.dp),
                ) {
                    CompositionLocalProvider(LocalInSettingsPane provides true) { detail() }
                }
            }
            return@Row
        }
        CompositionLocalProvider(LocalSettingsRowHost provides host) {
            key(group) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                runCatching { sidebarFocus.getValue(group).requestFocus() }
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(paneFocus)
                        .focusProperties { onExit = { if (requestedFocusDirection.isVertical()) cancelFocusChange() } }
                        .focusGroup()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GroupHeader(group)
                    content(group)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * The header of a screen opened in the Settings pane, like a Mac's sub-page: a back chevron (Back on the remote)
 * and the title, then [subtitle].
 */
@Composable
fun SettingsPaneHeader(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).background(SettingsColors.Card, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = SettingsColors.Muted, modifier = Modifier.size(24.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = SettingsColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SettingsColors.Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun FocusDirection.isVertical() = this == FocusDirection.Up || this == FocusDirection.Down

@Composable
private fun SidebarHeader(title: String, subtitle: String) {
    Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(44.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SettingsColors.Text, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SettingsColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SidebarItem(
    group: SettingsGroup,
    selected: Boolean,
    badge: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill = when {
        focused -> SettingsColors.Focus
        selected -> SettingsColors.SidebarSelected
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(fill, RoundedCornerShape(10.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(group.icon, group.tint, 26.dp)
        Text(
            group.title,
            style = MaterialTheme.typography.bodyLarge,
            color = SettingsColors.Text,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        if (badge) Box(Modifier.size(9.dp).background(SettingsColors.Red, CircleShape))
    }
}

@Composable
private fun GroupHeader(group: SettingsGroup) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SettingsColors.Card, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(group.icon, group.tint, 48.dp)
        Text(group.title, style = MaterialTheme.typography.headlineSmall, color = SettingsColors.Text, modifier = Modifier.padding(top = 8.dp))
        Text(
            group.description,
            style = MaterialTheme.typography.bodyMedium,
            color = SettingsColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A white symbol on a colored rounded square, like the icons of a Mac's System Settings. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp) {
    Box(
        Modifier.size(size).background(tint, RoundedCornerShape(size * 0.24f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/** Collects the rows of a [SettingsCard]; dividers go between them. */
class SettingsCardScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** A rounded card of rows, with an optional caption above it. */
@Composable
fun SettingsCard(caption: String? = null, build: SettingsCardScope.() -> Unit) {
    val rows = SettingsCardScope().apply(build).rows
    if (rows.isEmpty()) return
    Column {
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelLarge, color = SettingsColors.Muted, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        Column(Modifier.fillMaxWidth().background(SettingsColors.Card, RoundedCornerShape(14.dp)).padding(4.dp)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    // Inset to the text, past the icon, as on a Mac.
                    Box(Modifier.padding(start = 56.dp, end = 12.dp).fillMaxWidth().height(1.dp).background(SettingsColors.Divider))
                }
                row()
            }
        }
    }
}

/** Trailing content of a [SettingsRow]. */
sealed interface RowEnd {
    /** Opens another screen. */
    data object Chevron : RowEnd

    /** Opens another screen and shows a red "New" pill. */
    data object New : RowEnd

    /** A value instead of a chevron; the row does something in place or nothing. */
    data class Value(val text: String) : RowEnd

    data object None : RowEnd
}

/**
 * A row of a [SettingsCard]. [key] identifies it: after Back from the screen it opened, it gets focus again.
 * Disabled rows still take focus (so the list does not jump) and show an outline.
 */
@Composable
fun SettingsRow(
    key: String,
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Filled.Edit,
    tint: Color = SettingsColors.Gray,
    end: RowEnd = RowEnd.Chevron,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val host = LocalSettingsRowHost.current
    var focused by remember { mutableStateOf(false) }
    val content = if (enabled) SettingsColors.Text else SettingsColors.Text.copy(alpha = 0.45f)
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .then(if (host?.restoreKey == key) rememberInitialFocus() else Modifier)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                when {
                    focused && enabled -> Modifier.background(SettingsColors.Focus, shape)
                    focused -> Modifier.drawBehind {
                        drawRoundRect(SettingsColors.Muted, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                    else -> Modifier
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null) {
                if (enabled) {
                    host?.onOpen?.invoke(key)
                    onClick()
                }
            }
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, if (enabled) tint else tint.copy(alpha = 0.45f), 30.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = content)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused && enabled) content.copy(alpha = 0.85f) else SettingsColors.Muted.copy(alpha = content.alpha),
                )
            }
        }
        when (end) {
            RowEnd.Chevron -> Chevron(content)
            RowEnd.New -> {
                Text(
                    "New",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.background(SettingsColors.Red, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Chevron(content)
            }
            is RowEnd.Value -> Text(end.text, style = MaterialTheme.typography.bodyMedium, color = if (focused) content else SettingsColors.Muted)
            RowEnd.None -> {}
        }
    }
}

@Composable
private fun Chevron(color: Color) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = color.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 4.dp).size(22.dp),
    )
}

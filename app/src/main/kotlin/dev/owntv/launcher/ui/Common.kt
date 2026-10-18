package dev.owntv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.owntv.launcher.ui.render.ContextTarget

val ScreenBackground = Color(0xFF101115)

/** A modifier that takes focus when the composable first appears. */
@Composable
fun rememberInitialFocus(): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return Modifier.focusRequester(requester)
}

@Composable
fun SettingsScaffold(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .padding(horizontal = 56.dp, vertical = 32.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA9AFBA), modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

@Composable
fun ContextMenu(
    target: ContextTarget,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onAppInfo: () -> Unit,
    onLauncherSettings: () -> Unit,
    onEditLayout: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 320.dp, max = 420.dp)
                .background(Color(0xFF1C1E24), RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(target.title, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(8.dp))
            val items = buildList<Pair<String, () -> Unit>> {
                if (target.app != null) add("Open" to onOpen)
                if (target.packageName != null) add("App info" to onAppInfo)
                if (onEditLayout != null) add("Edit layout" to onEditLayout)
                add("Launcher settings" to onLauncherSettings)
                add("Cancel" to {})
            }
            items.forEachIndexed { index, (label, action) ->
                ListItem(
                    selected = false,
                    onClick = { onDismiss(); action() },
                    modifier = if (index == 0) rememberInitialFocus() else Modifier,
                    headlineContent = { Text(label) },
                )
            }
        }
    }
}

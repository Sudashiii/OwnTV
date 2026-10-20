package dev.owntv.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

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

/**
 * A settings row that looks and behaves like tv-material's `ListItem` (sizes, colors and focus scale are its
 * defaults) at a fraction of the cost: a `ListItem` took about 20 ms to build on the emulator, and Settings
 * builds a screenful of them when it opens. Disabled rows still take focus, as they did, and show an outline.
 */
@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && enabled) 1.05f else 1f, tween(150), label = "scale")
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = if (focused) 0.64f else 0.48f)
        focused -> colors.inverseOnSurface
        else -> colors.onSurface
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) 48.dp else 64.dp)
            .then(
                when {
                    focused && enabled -> Modifier.background(colors.inverseSurface, shape)
                    // Centred on the edge like tv-material's focused-disabled border, half of it outside the row.
                    focused -> Modifier.drawBehind {
                        drawRoundRect(colors.border, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                    else -> Modifier
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = null, indication = null) { if (enabled) onClick() }
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = content)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = content.alpha * 0.8f))
    }
}

@Composable
fun ContextMenu(
    title: String,
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    // The menu opens while OK is still held from the long press. Swallow that press (its repeats and
    // its release) so letting go does not click the first item; the next fresh press goes through.
    var freshPress by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (event.nativeKeyEvent.repeatCount == 0) freshPress = true
                            !freshPress
                        }
                        KeyEventType.KeyUp -> {
                            val stale = !freshPress
                            freshPress = false
                            stale
                        }
                        else -> false
                    }
                }
                .widthIn(min = 320.dp, max = 420.dp)
                .background(Color(0xFF1C1E24), RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(8.dp))
            items.forEachIndexed { index, (label, action) ->
                ListItem(
                    selected = false,
                    onClick = { action(); onDismiss() },
                    modifier = if (index == 0) rememberInitialFocus() else Modifier,
                    headlineContent = { Text(label) },
                )
            }
        }
    }
}

package dev.owntv.launcher.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.ui.Navigator
import dev.owntv.launcher.ui.rememberInitialFocus
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalAppImages
import dev.owntv.launcher.ui.render.LocalApps
import dev.owntv.launcher.ui.render.LocalEditorSelection
import dev.owntv.launcher.ui.render.LocalPreviewMode
import dev.owntv.layout.validation.Severity
import kotlinx.coroutines.launch

internal val PanelBackground = Color(0xFF17181C)
internal val EditorBackground = Color(0xFF0F1013)
internal val Muted = Color(0xFFA9AFBA)
internal val ErrorColor = Color(0xFFFFB4A9)

private const val PREVIEW_SCALE = 0.6f

/** The on-TV layout editor: a live preview on the left, a remote-friendly panel on the right. */
@Composable
fun EditorScreen(container: AppContainer, nav: Navigator, layoutState: LayoutState, catalog: AppCatalog, selectId: String?) {
    val state = remember { EditorState(layoutState.document, selectId) }
    val scope = rememberCoroutineScope()
    var confirmExit by remember { mutableStateOf(false) }
    val undoFocus = remember { FocusRequester() }
    val redoFocus = remember { FocusRequester() }

    var saving by remember { mutableStateOf(false) }
    fun save() {
        if (saving) return
        val errors = state.issues.filter { it.severity == Severity.Error }
        if (errors.isNotEmpty()) {
            state.message = "Fix ${errors.size} error(s) before saving. First: ${errors.first()}"
            return
        }
        scope.launch {
            saving = true
            try {
                container.layouts.apply(state.doc)
                container.actions.toast("Layout saved")
                nav.home()
            } catch (e: java.io.IOException) {
                state.message = "Could not save the layout: ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    BackHandler {
        when {
            state.dialog != null -> state.dialog = null
            state.pop() -> Unit
            state.dirty -> confirmExit = true
            else -> nav.back()
        }
    }

    Row(Modifier.fillMaxSize().background(EditorBackground)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 32.dp, top = 22.dp, end = 24.dp, bottom = 16.dp)) {
            Text("Edit layout", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            StatusLine(state)
            BoxWithConstraints(Modifier.padding(top = 12.dp)) {
                val scale = minOf(PREVIEW_SCALE, maxWidth.value / 960f)
                Box(
                    Modifier.size(960.dp * scale, 540.dp * scale).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val error = state.issues.firstOrNull { it.severity == Severity.Error }
                    if (error != null) {
                        Text("Preview paused until this error is fixed:\n$error", color = ErrorColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
                    } else {
                        CompositionLocalProvider(
                            LocalPreviewMode provides true,
                            LocalApps provides catalog,
                            LocalAppImages provides container.images,
                            LocalEditorSelection provides state.selectedId,
                        ) {
                            Box(Modifier.requiredSize(960.dp, 540.dp).graphicsLayer(scaleX = scale, scaleY = scale)) {
                                LayoutView(state.doc, pageId = state.page.id, onSelectPage = {}, focusTrigger = 0)
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::save, enabled = state.dirty && !saving,
                    modifier = Modifier.focusProperties { canFocus = state.dirty && !saving }) { Text(if (saving) "Saving…" else "Save") }
                OutlinedButton(onClick = {
                    state.undo()
                    if (!state.canUndo && state.canRedo) scope.launch {
                        withFrameNanos { }
                        redoFocus.requestFocus()
                    }
                }, enabled = state.canUndo,
                    modifier = Modifier.focusRequester(undoFocus).focusProperties { canFocus = state.canUndo }) { Text("Undo") }
                OutlinedButton(onClick = {
                    state.redo()
                    if (!state.canRedo && state.canUndo) scope.launch {
                        withFrameNanos { }
                        undoFocus.requestFocus()
                    }
                }, enabled = state.canRedo,
                    modifier = Modifier.focusRequester(redoFocus).focusProperties { canFocus = state.canRedo }) { Text("Redo") }
                OutlinedButton(onClick = { if (state.dirty) confirmExit = true else nav.back() }) { Text(if (state.dirty) "Discard" else "Close") }
            }
            (state.message ?: state.issues.firstOrNull { it.severity != Severity.Info }?.toString())?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ErrorColor, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Column(Modifier.width(360.dp).fillMaxHeight().background(PanelBackground).padding(horizontal = 12.dp, vertical = 16.dp)) {
            key(state.panels.last()) {
                when (val panel = state.panels.last()) {
                    Panel.Outline -> OutlinePanel(state)
                    is Panel.NodeMenu -> NodeMenuPanel(state, panel)
                    is Panel.Palette -> PalettePanel(state, panel, catalog)
                    is Panel.Form -> FormPanel(state, panel)
                    is Panel.ListForm -> ListFormPanel(state, panel)
                }
            }
        }
    }

    state.dialog?.let { edit -> FieldDialog(state, edit, catalog) }

    if (confirmExit) {
        ConfirmExitDialog(
            onSave = { confirmExit = false; save() },
            onDiscard = { confirmExit = false; nav.back() },
            onCancel = { confirmExit = false },
        )
    }
}

@Composable
private fun StatusLine(state: EditorState) {
    val errors = state.issues.count { it.severity == Severity.Error }
    val warnings = state.issues.count { it.severity == Severity.Warning }
    val parts = buildList {
        add(state.doc.name)
        add(if (state.dirty) "unsaved changes" else "no changes")
        if (errors > 0) add("$errors error(s)")
        if (warnings > 0) add("$warnings warning(s)")
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (errors > 0) ErrorColor else Muted)
}

@Composable
private fun ConfirmExitDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.widthIn(min = 320.dp, max = 420.dp).background(Color(0xFF1C1E24), RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("Save your changes?", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(8.dp))
            ListItem(selected = false, onClick = onSave, modifier = rememberInitialFocus(), headlineContent = { Text("Save") })
            ListItem(selected = false, onClick = onDiscard, headlineContent = { Text("Discard changes") })
            ListItem(selected = false, onClick = onCancel, headlineContent = { Text("Keep editing") })
        }
    }
}

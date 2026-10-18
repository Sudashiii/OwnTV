package dev.owntv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.data.layout.LayoutState
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalAppImages
import dev.owntv.launcher.ui.render.LocalApps
import dev.owntv.launcher.ui.render.LocalPreviewMode
import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.LayoutThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Muted = Color(0xFFA9AFBA)

private data class LookEntry(val fileName: String, val doc: LayoutDocument)

/** The built-in looks. Each one can replace the whole layout or only lend its style. */
@Composable
fun LooksScreen(container: AppContainer, nav: Navigator) {
    val looks by produceState(emptyList<LookEntry>()) {
        value = withContext(Dispatchers.IO) {
            container.assets.lookFiles().mapNotNull { file ->
                LayoutCodec.decode(container.assets.look(file)).document?.let { LookEntry(file, it) }
            }
        }
    }
    SettingsScaffold("Looks", "Recreations of popular TV home screens. Use a whole look, or keep your layout and take only its style.") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            looks.forEachIndexed { index, look ->
                SettingsItem(
                    look.doc.name,
                    look.doc.description,
                    modifier = if (index == 0) rememberInitialFocus() else Modifier,
                ) { nav.push(Screen.LookPreview(look.fileName)) }
            }
        }
    }
}

@Composable
fun LookPreviewScreen(container: AppContainer, nav: Navigator, fileName: String, state: LayoutState, catalog: AppCatalog) {
    val scope = rememberCoroutineScope()
    val look by produceState<LayoutDocument?>(null, fileName) {
        value = withContext(Dispatchers.IO) { LayoutCodec.decode(container.assets.look(fileName)).document }
    }
    val doc = look ?: return
    val styled = remember(doc, state.document) { LayoutThemes.applyLook(state.document, doc) }

    SettingsScaffold(doc.name, doc.description) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            PreviewCard("The whole look", doc, catalog, container)
            PreviewCard("Your layout in this style", styled, catalog, container)
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        container.layouts.apply(doc)
                        container.actions.toast("Applied the ${doc.name} look")
                        nav.home()
                    }
                },
                modifier = rememberInitialFocus(),
            ) { Text("Use the whole look") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        container.layouts.apply(styled)
                        container.actions.toast("Applied the ${doc.name} style to your layout")
                        nav.home()
                    }
                },
            ) { Text("Use only its style") }
            OutlinedButton(onClick = { nav.back() }) { Text("Cancel") }
        }
        Text(
            "Revert in launcher settings brings back your previous layout either way.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** A layout's first page at 45% size. Nothing inside can take focus. */
@Composable
private fun PreviewCard(title: String, doc: LayoutDocument, catalog: AppCatalog, container: AppContainer) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
        Box(Modifier.size(432.dp, 243.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(
                LocalPreviewMode provides true,
                LocalApps provides catalog,
                LocalAppImages provides container.images,
            ) {
                Box(Modifier.requiredSize(960.dp, 540.dp).graphicsLayer(scaleX = 0.45f, scaleY = 0.45f)) {
                    LayoutView(doc, pageId = null, onSelectPage = {}, focusTrigger = 0)
                }
            }
        }
    }
}

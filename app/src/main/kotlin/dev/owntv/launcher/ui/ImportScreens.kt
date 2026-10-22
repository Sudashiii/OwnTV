package dev.owntv.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.data.layout.BuiltInRef
import dev.owntv.launcher.importexport.QrCode
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalAppImages
import dev.owntv.launcher.ui.render.LocalApps
import dev.owntv.launcher.ui.render.LocalPreviewMode
import dev.owntv.layout.apps.AppListOps
import dev.owntv.layout.codec.DecodeResult
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.validation.Severity
import dev.owntv.layout.validation.ValidationIssue
import kotlinx.coroutines.launch
import java.io.File

private val Muted = Color(0xFFA9AFBA)

@Composable
fun ImportScreen(container: AppContainer, nav: Navigator) {
    val paste by container.paste.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val files by produceState(emptyList<File>()) { value = container.files.list() }

    SettingsScaffold("Import layout", "Nothing changes until you check the preview and choose Apply.") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // Left: paste from phone.
            Column(Modifier.weight(1f)) {
                Text("Paste from your phone", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "1. Scan the code with a phone on the same Wi-Fi.\n" +
                        "2. Paste the layout JSON (for example from an AI chat) and tap Send.\n" +
                        "3. Check the preview here. The page can also copy an AI prompt with your apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                val url = paste.url
                when {
                    url != null -> {
                        val qr = remember(url) { QrCode.bitmap(url) }
                        Image(qr, contentDescription = "QR code for $url", modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)))
                        Text(url, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                        if (!paste.hasLanAddress) {
                            Text("No network address found; connect the TV to Wi-Fi or Ethernet.", color = Color(0xFFFFB4A9))
                        }
                    }
                    paste.message != null -> Text(paste.message!!, color = Color(0xFFFFB4A9))
                    else -> Text("Starting…", color = Muted)
                }
            }

            // Right: URL and files.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("From a URL", style = MaterialTheme.typography.titleLarge, color = Color.White)
                UrlField(
                    enabled = busy == null,
                    onSubmit = { url ->
                        busy = "Loading $url…"
                        scope.launch {
                            container.urlImporter.fetch(url)
                                .onSuccess { nav.showPreview(container.decode(it), url) }
                                .onFailure { container.actions.toast("Could not load the URL: ${it.message}") }
                            busy = null
                        }
                    },
                )
                busy?.let { Text(it, color = Muted, modifier = Modifier.padding(top = 6.dp)) }

                Spacer(Modifier.height(24.dp))
                Text("From a file", style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (files.isEmpty()) {
                    Text("No .json files yet. Copy one with:", color = Muted, modifier = Modifier.padding(top = 6.dp))
                    Code("adb push my-layout.json ${container.files.adbPath}/")
                }
                files.forEach { file ->
                    SettingsItem(file.name, "Modified ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))}") {
                        scope.launch { nav.showPreview(container.decode(container.files.read(file)), file.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlField(enabled: Boolean, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("https://") }
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit(text) }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Color.White else Color(0xFF3A3D45), RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1E24), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Button(onClick = { onSubmit(text) }, enabled = enabled, modifier = Modifier.padding(start = 12.dp).then(rememberInitialFocus())) {
            Text("Load")
        }
    }
}

@Composable
fun PreviewScreen(container: AppContainer, nav: Navigator, result: DecodeResult, source: String, builtIn: BuiltInRef?, catalog: AppCatalog) {
    val scope = rememberCoroutineScope()
    val doc = result.document
    val errors = result.issues.count { it.severity == Severity.Error }
    val warnings = result.issues.count { it.severity == Severity.Warning }

    SettingsScaffold(doc?.name ?: "Layout has errors", "Source: $source") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column {
                if (doc != null) {
                    // An older layout's own package lists, shown as the TV will keep them.
                    val previewCatalog = remember(catalog, result) {
                        result.extractedLists?.let { catalog.withLists(AppListOps.seed(catalog.lists, it)) } ?: catalog
                    }
                    ScaledPreview(doc, previewCatalog, container)
                } else {
                    Box(
                        Modifier.size(480.dp, 270.dp).background(Color(0xFF1C1E24), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Fix the errors to see a preview.", color = Muted)
                    }
                }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (doc != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    container.layouts.apply(doc, result.extractedLists, builtIn)
                                    container.actions.toast("Applied \"${doc.name}\"")
                                    nav.home()
                                }
                            },
                            modifier = rememberInitialFocus(),
                        ) { Text("Apply") }
                        OutlinedButton(onClick = { nav.back() }) { Text("Cancel") }
                    } else {
                        OutlinedButton(onClick = { nav.back() }, modifier = rememberInitialFocus()) { Text("Back") }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        result.issues.isEmpty() -> "No problems found."
                        else -> "$errors error(s), $warnings warning(s)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (errors > 0) Color(0xFFFFB4A9) else Color.White,
                )
                LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(result.issues) { IssueRow(it) }
                }
            }
        }
    }
}

@Composable
private fun IssueRow(issue: ValidationIssue) {
    val color = when (issue.severity) {
        Severity.Error -> Color(0xFFFFB4A9)
        Severity.Warning -> Color(0xFFFFE08A)
        Severity.Info -> Muted
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1C1E24), RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text("${issue.severity.name.uppercase()}  ${issue.path}", style = MaterialTheme.typography.labelMedium, color = color)
        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/** The layout's first page at half size. Nothing inside can take focus. */
@Composable
private fun ScaledPreview(doc: LayoutDocument, catalog: AppCatalog, container: AppContainer) {
    Box(Modifier.size(480.dp, 270.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(
            LocalPreviewMode provides true,
            LocalApps provides catalog,
            LocalAppImages provides container.images,
        ) {
            Box(Modifier.requiredSize(960.dp, 540.dp).graphicsLayer(scaleX = 0.5f, scaleY = 0.5f)) {
                LayoutView(doc, pageId = null, onSelectPage = {}, focusTrigger = 0)
            }
        }
    }
}

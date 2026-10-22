package dev.owntv.launcher.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.owntv.launcher.ui.rememberInitialFocus
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.layout.edit.FieldKind
import dev.owntv.layout.edit.FieldSpec
import dev.owntv.layout.edit.Fields
import dev.owntv.layout.edit.PathStep
import dev.owntv.layout.edit.at
import dev.owntv.layout.model.Colors
import dev.owntv.layout.model.NodeRegistry
import dev.owntv.layout.model.walk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

@Composable
internal fun FormPanel(state: EditorState, panel: Panel.Form) {
    val obj = state.rootJson(panel.target)?.at(panel.path) as? JsonObject ?: return
    val subtype = (obj["type"] as? JsonPrimitive)?.contentOrNull
    val fields = panel.sealedBase?.let { base ->
        Fields.subtypes(base)[subtype]?.let { Fields.forDescriptor(it) }.orEmpty()
    } ?: Fields.forDescriptors(panel.descriptors, panel.skip)
    val nodeRoot = panel.target is FormTarget.NodeTarget && panel.path.isEmpty() &&
        NodeRegistry.commonDescriptor !in panel.descriptors

    PanelHeader(panel.title)
    LazyColumn(Modifier.fillMaxWidth()) {
        if (panel.sealedBase != null) {
            item {
                PanelRow("Type", subtype, modifier = rememberInitialFocus()) {
                    val types = Fields.subtypes(panel.sealedBase).keys.filter { it != "color" }
                    state.dialog = FieldEdit(
                        panel.target, panel.path,
                        FieldSpec("type", FieldKind.Enum, panel.sealedBase, null, false, false, enumValues = types),
                        sealedBase = panel.sealedBase,
                    )
                }
            }
        }
        itemsIndexed(fields, key = { _, spec -> spec.name }) { index, spec ->
            val path = panel.path + PathStep.Key(spec.name)
            val value = obj[spec.name]
            PanelRow(
                spec.name.label(), value.describe(spec),
                modifier = if (index == 0 && panel.sealedBase == null) rememberInitialFocus() else Modifier,
            ) { openField(state, panel.target, path, spec, value) }
        }
        if (nodeRoot) {
            item {
                PanelRow("Size, spacing and look", "Padding, background, focus links and visibility") {
                    state.push(Panel.Form(panel.target, emptyList(), "Size, spacing and look", listOf(NodeRegistry.commonDescriptor)))
                }
            }
        }
        if (panel.path.lastOrNull() is PathStep.Index) {
            item {
                PanelRow("Remove this item") {
                    if (state.setValue(panel.target, panel.path, null)) state.pop()
                }
            }
        } else if (panel.path.isNotEmpty()) {
            item {
                PanelRow("Reset to default") {
                    if (state.setValue(panel.target, panel.path, null)) state.pop()
                }
            }
        }
    }
}

private fun openField(state: EditorState, target: FormTarget, path: List<PathStep>, spec: FieldSpec, value: JsonElement?) {
    val missing = value == null || value == JsonNull
    when (spec.kind) {
        FieldKind.Bool -> state.setValue(target, path, JsonPrimitive(!((value as? JsonPrimitive)?.booleanOrNull ?: false)))
        FieldKind.Object, FieldKind.Sealed -> {
            val sealed = spec.kind == FieldKind.Sealed
            val initial = if (sealed) Fields.newSealed(spec.descriptor) else Fields.newObject(spec.descriptor)
            if (!missing || state.setValue(target, path, initial)) {
                state.push(Panel.Form(target, path, spec.name.label(),
                    descriptors = if (sealed) emptyList() else listOf(spec.descriptor),
                    sealedBase = spec.descriptor.takeIf { sealed }))
            }
        }
        FieldKind.StringList, FieldKind.ObjectList -> {
            if (!missing || state.setValue(target, path, JsonArray(emptyList()))) {
                state.push(Panel.ListForm(target, path, spec.name.label(), spec))
            }
        }
        FieldKind.Unsupported -> state.message = "Edit ${spec.name} in the layout JSON before importing it."
        else -> state.dialog = FieldEdit(target, path, spec)
    }
}

@Composable
internal fun ListFormPanel(state: EditorState, panel: Panel.ListForm) {
    val values = state.rootJson(panel.target)?.at(panel.path) as? JsonArray ?: return
    val descriptor = panel.spec.descriptor.getElementDescriptor(0)
    val itemSpec = panel.spec.copy(kind = panel.spec.itemKind ?: FieldKind.Text, descriptor = descriptor, optional = false, nullable = false)
    fun editItem(index: Int) {
        val path = panel.path + PathStep.Index(index)
        if (panel.spec.kind == FieldKind.ObjectList) {
            state.push(Panel.Form(panel.target, path, "${panel.title} · ${index + 1}", listOf(descriptor)))
        } else {
            state.dialog = FieldEdit(panel.target, path, itemSpec, listItem = true)
        }
    }
    PanelHeader(panel.title, "${values.size} item(s). Select an item to edit it.")
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            PanelRow("Add…", modifier = rememberInitialFocus()) {
                if (panel.spec.kind == FieldKind.ObjectList) {
                    if (state.setValue(panel.target, panel.path + PathStep.Index(values.size), Fields.newObject(descriptor))) editItem(values.size)
                } else editItem(values.size)
            }
        }
        itemsIndexed(values) { index, value ->
            PanelRow("${index + 1}. ${value.describe(itemSpec)}") { editItem(index) }
        }
    }
}

@Composable
internal fun FieldDialog(state: EditorState, edit: FieldEdit, catalog: AppCatalog) {
    val current = state.rootJson(edit.target)?.at(edit.path)?.takeUnless { it == JsonNull }
    fun save(value: JsonElement?) {
        if (state.setValue(edit.target, edit.path, value)) state.dialog = null
    }
    Dialog(onDismissRequest = { state.dialog = null }) {
        key(edit) {
            Column(Modifier.width(420.dp).heightIn(max = 460.dp)
                .background(PanelBackground, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(edit.spec.name.label(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                edit.spec.doc?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                when (edit.spec.kind) {
                    FieldKind.Enum, FieldKind.StyleRef, FieldKind.NodeRef, FieldKind.PageRef -> {
                        val choices = when (edit.spec.kind) {
                            FieldKind.StyleRef -> state.doc.styles.keys.toList()
                            FieldKind.NodeRef -> buildList { state.page.root.walk("") { node, _, _ -> add(node.id) } }
                            FieldKind.PageRef -> state.doc.pages.map { it.id }
                            else -> edit.spec.enumValues
                        }
                        if (choices.isEmpty()) Text("No choices available.", color = Muted)
                        choices.forEachIndexed { index, choice ->
                            PanelRow(choice, selected = (current as? JsonPrimitive)?.contentOrNull == choice,
                                modifier = if (index == 0) rememberInitialFocus() else Modifier) {
                                save(edit.sealedBase?.let { Fields.newSealed(it, choice) } ?: JsonPrimitive(choice))
                            }
                        }
                    }
                    FieldKind.Package -> {
                        catalog.apps.sortedBy { catalog.labelOf(it).lowercase() }.forEachIndexed { index, app ->
                            PanelRow(catalog.labelOf(app), app.packageName,
                                modifier = if (index == 0) rememberInitialFocus() else Modifier) { save(JsonPrimitive(app.packageName)) }
                        }
                        TextInput(current.text(), "Package name", focusDone = catalog.apps.isEmpty(), onDone = { save(JsonPrimitive(it)) })
                    }
                    FieldKind.Color -> {
                        val colors = state.doc.theme.colors
                        val themeColor = edit.target == FormTarget.DocumentTarget && edit.path.take(2) == listOf(PathStep.Key("theme"), PathStep.Key("colors"))
                        if (!themeColor) {
                            (listOf("background", "surface", "primary", "text", "textMuted", "focus") + colors.custom.keys).distinct().forEachIndexed { index, token ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(24.dp).background(colors.resolve("@$token")?.let { Color(it) } ?: Color.Transparent))
                                    PanelRow("@$token", modifier = (if (index == 0) rememberInitialFocus() else Modifier).weight(1f)) { save(JsonPrimitive("@$token")) }
                                }
                            }
                        }
                        TextInput(current.text(), "#RRGGBB or #AARRGGBB", focusDone = themeColor, valid = Colors::isHex, onDone = { save(JsonPrimitive(it)) })
                    }
                    FieldKind.Int, FieldKind.Decimal -> {
                        val range = edit.spec.range ?: (0.0..4000.0)
                        var number by remember { mutableStateOf(((current as? JsonPrimitive)?.doubleOrNull ?: range.start).coerceIn(range)) }
                        val integer = edit.spec.kind == FieldKind.Int
                        NumberStepper("Value", number, range, if (integer) 1.0 else 0.1) { number = it }
                        TextInput(number.numberText(integer), "Number", numeric = true,
                            valid = { it.toDoubleOrNull()?.let { n -> n.isFinite() && n in range && (!integer || n % 1.0 == 0.0) } == true },
                            onDone = { save(if (integer) JsonPrimitive(it.toDouble().toInt()) else JsonPrimitive(it.toDouble())) })
                    }
                    FieldKind.Size -> {
                        PanelRow("Fill", modifier = rememberInitialFocus()) { save(JsonPrimitive("fill")) }
                        PanelRow("Wrap") { save(JsonPrimitive("wrap")) }
                        var size by remember { mutableStateOf((current as? JsonPrimitive)?.doubleOrNull ?: 160.0) }
                        NumberStepper("Fixed dp", size, 1.0..4000.0, 8.0) { size = it }
                        Button(onClick = { save(JsonPrimitive(size.toInt())) }) { Text("Use fixed size") }
                    }
                    FieldKind.Padding -> {
                        val values = (current as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
                        val all = (current as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        var horizontal by remember { mutableStateOf(values?.firstOrNull() ?: all) }
                        var vertical by remember { mutableStateOf(values?.getOrNull(1) ?: all) }
                        NumberStepper("Horizontal", horizontal, 0.0..400.0, 4.0) { horizontal = it }
                        NumberStepper("Vertical", vertical, 0.0..400.0, 4.0) { vertical = it }
                        Button(onClick = { save(JsonArray(listOf(JsonPrimitive(horizontal.toInt()), JsonPrimitive(vertical.toInt())))) }, modifier = rememberInitialFocus()) { Text("Done") }
                    }
                    FieldKind.Background -> {
                        PanelRow("Solid color…", modifier = rememberInitialFocus()) { state.dialog = edit.copy(spec = edit.spec.copy(kind = FieldKind.Color)) }
                        listOf("gradient", "image", "video", "slideshow").forEach { type ->
                            PanelRow("${type.label()} ›") {
                                val existing = current as? JsonObject
                                if ((existing?.get("type") as? JsonPrimitive)?.contentOrNull == type ||
                                    state.setValue(edit.target, edit.path, Fields.newSealed(edit.spec.descriptor, type))) {
                                    state.dialog = null
                                    state.push(Panel.Form(edit.target, edit.path, edit.spec.name.label(), sealedBase = edit.spec.descriptor))
                                }
                            }
                        }
                        PanelRow("None") { save(null) }
                    }
                    else -> TextInput(current.text(), "Text", onDone = { save(JsonPrimitive(it)) })
                }
                if (edit.listItem && current != null || edit.spec.optional || edit.spec.nullable) {
                    OutlinedButton(onClick = { save(null) }) {
                        Text(if (edit.listItem) "Remove item" else if (edit.spec.nullable) "Clear" else "Reset to default")
                    }
                }
                state.message?.let { Text(it, color = ErrorColor, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { state.dialog = null }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun TextInput(initial: String, hint: String, numeric: Boolean = false, focusDone: Boolean = true, valid: (String) -> Boolean = { true }, onDone: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val acceptable = valid(text)
    BasicTextField(
        value = text, onValueChange = { text = it }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (acceptable) onDone(text) }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Color.White else Muted, RoundedCornerShape(8.dp)).padding(12.dp),
    )
    Text(hint, color = if (acceptable) Muted else ErrorColor, style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onDone(text) }, enabled = acceptable,
        modifier = (if (focusDone && acceptable) rememberInitialFocus() else Modifier).focusProperties { canFocus = acceptable }) { Text("Done") }
}

@Composable
private fun NumberStepper(label: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, onChange: (Double) -> Unit) {
    Text("$label: ${value.numberText(step >= 1)}", color = Color.White)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.start,
            modifier = Modifier.focusProperties { canFocus = value > range.start }) { Text("−") }
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.endInclusive,
            modifier = Modifier.focusProperties { canFocus = value < range.endInclusive }) { Text("+") }
    }
}

private fun Double.numberText(integer: Boolean) = if (integer) toInt().toString() else java.math.BigDecimal.valueOf(this).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
private fun String.label() = replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun JsonElement?.text() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement?.describe(spec: FieldSpec): String = when (this) {
    null, JsonNull -> "Not set"
    is JsonArray -> "${size} item(s)"
    is JsonObject -> (get("type") as? JsonPrimitive)?.contentOrNull ?: "Edit…"
    is JsonPrimitive -> if (spec.kind == FieldKind.Bool) if (booleanOrNull == true) "On" else "Off" else content
}

package dev.owntv.launcher.data.layout

import android.content.Context
import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.AppLists
import dev.owntv.layout.model.BuiltInLayouts
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.walkNodes
import dev.owntv.layout.validation.LayoutValidator
import dev.owntv.layout.validation.ValidationIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class LayoutState(
    val document: LayoutDocument,
    val issues: List<ValidationIssue> = emptyList(),
    /** Set when the saved layout could not be read and the fallback is shown instead. */
    val problem: String? = null,
    val loaded: Boolean = true,
)

data class HistoryEntry(val file: File, val name: String, val savedAt: Long)

/**
 * Stores the active layout in app storage and keeps the last [MAX_HISTORY] versions for revert.
 * Package lists found in older layouts go to [keepLists], since the TV stores them apart now.
 * [onLoaded] gets how long reading the stored layout at start-up took and its node count.
 */
class LayoutRepository(
    context: Context,
    scope: CoroutineScope,
    private val assets: BundledAssets,
    private val keepLists: (AppLists) -> Unit,
    private val onLoaded: (decodeMs: Double, nodes: Int) -> Unit = { _, _ -> },
) {
    private val dir = File(context.filesDir, "layouts")
    private val historyDir = File(dir, "history")
    private val currentFile = File(dir, "current.json")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(LayoutState(BuiltInLayouts.fallback(), loaded = false))
    val state: StateFlow<LayoutState> = _state.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        historyDir.mkdirs()
        val text = runCatching {
            if (currentFile.exists()) currentFile.readText() else assets.template(BundledAssets.DEFAULT_TEMPLATE)
        }.getOrNull()
        val start = System.nanoTime()
        val result = text?.let { LayoutCodec.decode(it) }
        val decodeMs = (System.nanoTime() - start) / 1e6
        result?.document?.let { doc ->
            var nodes = 0
            doc.walkNodes { _, _, _, _ -> nodes++ }
            onLoaded(decodeMs, nodes)
        }
        result?.extractedLists?.let(keepLists)
        _state.value = if (result?.document != null) {
            LayoutState(result.document!!, result.issues)
        } else {
            LayoutState(
                document = BuiltInLayouts.fallback(),
                issues = result?.issues.orEmpty(),
                problem = "The saved layout could not be read, so a simple fallback is shown. Open launcher settings to import a layout or revert.",
            )
        }
    }

    fun currentJson(): String = LayoutCodec.encode(_state.value.document)

    /**
     * Makes [doc] the active layout. The previous layout goes into history. [extracted] holds the
     * package lists [doc] had before it was upgraded (see `DecodeResult.extractedLists`).
     */
    suspend fun apply(doc: LayoutDocument, extracted: AppLists? = null) = mutex.withLock {
        extracted?.let(keepLists)
        withContext(Dispatchers.IO) {
            historyDir.mkdirs()
            val previous = _state.value
            if (previous.loaded && previous.problem == null) {
                File(historyDir, "${System.currentTimeMillis()}.json").writeText(LayoutCodec.encode(previous.document))
                prune()
            }
            writeAtomically(currentFile, LayoutCodec.encode(doc))
            _state.value = LayoutState(doc, LayoutValidator.validate(doc))
        }
    }

    suspend fun history(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        historyFiles().map { file ->
            val name = LayoutCodec.decode(file.readText()).document?.name ?: "Unreadable layout"
            HistoryEntry(file, name, file.nameWithoutExtension.toLongOrNull() ?: file.lastModified())
        }
    }

    fun hasHistory(): Boolean = historyFiles().isNotEmpty()

    /** Restores the newest history entry. Returns false when there is none. */
    suspend fun revert(): Boolean {
        val latest = withContext(Dispatchers.IO) { historyFiles().firstOrNull() } ?: return false
        return restore(latest)
    }

    suspend fun restore(file: File): Boolean {
        val result = withContext(Dispatchers.IO) { LayoutCodec.decode(file.readText()) }
        apply(result.document ?: return false, result.extractedLists)
        withContext(Dispatchers.IO) { file.delete() }
        return true
    }

    private fun historyFiles(): List<File> =
        historyDir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.name }.orEmpty()

    private fun prune() {
        historyFiles().drop(MAX_HISTORY).forEach { it.delete() }
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_HISTORY = 10
    }
}

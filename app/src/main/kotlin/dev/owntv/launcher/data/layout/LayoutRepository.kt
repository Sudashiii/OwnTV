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
 *
 * A layout applied from a built-in look or template is remembered (`built-in.json`, see [BuiltInTracking]). If a
 * new OwnTV version changes that look or template while the user has not touched the layout, [builtInUpdate]
 * holds the new version and [applyBuiltInUpdate] swaps it in; the old layout goes to history like any other.
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
    private val originFile = File(dir, "built-in.json")
    private val mutex = Mutex()
    private var origin: BuiltInOrigin? = null

    private val _state = MutableStateFlow(LayoutState(BuiltInLayouts.fallback(), loaded = false))
    val state: StateFlow<LayoutState> = _state.asStateFlow()

    private val _hasHistory = MutableStateFlow(false)

    /** Whether there is a layout to revert to; kept up to date so Settings does not have to look on the disk. */
    val hasHistory: StateFlow<Boolean> = _hasHistory.asStateFlow()

    private val _builtInUpdate = MutableStateFlow<BuiltInUpdate?>(null)

    /** A newer version of the built-in look or template the stored layout came from, while that layout is unmodified. */
    val builtInUpdate: StateFlow<BuiltInUpdate?> = _builtInUpdate.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        historyDir.mkdirs()
        _hasHistory.value = historyFiles().isNotEmpty()
        // Without a stored file the first-run template is read from the APK every time, so it is always the newest.
        val stored = runCatching { currentFile.takeIf { it.exists() }?.readText() }.getOrNull()
        val text = stored ?: runCatching { assets.template(BundledAssets.DEFAULT_TEMPLATE) }.getOrNull()
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
        val doc = result?.document
        if (stored != null && doc != null) mutex.withLock { refreshBuiltInUpdate(stored, doc) }
    }

    /** Looks up whether the built-in layout behind [fileText] changed. Runs on the IO dispatcher, after the layout is shown. */
    private fun refreshBuiltInUpdate(fileText: String, doc: LayoutDocument) {
        val fileHash = BuiltInTracking.hash(fileText)
        if (originFile.exists()) {
            origin = runCatching { BuiltInOriginFile.decode(originFile.readText()) }.getOrNull()
        } else {
            // Stored before this was tracked: link it to the built-in layout it is identical to, once.
            origin = BuiltInTracking.adopt(doc, bundledCandidates(), fileHash)
            writeOrigin()
        }
        val current = origin
        _builtInUpdate.value = BuiltInTracking.update(current, fileHash, doc, current?.let { bundledDocument(it.ref) })
    }

    private fun bundledDocument(ref: BuiltInRef): LayoutDocument? = runCatching {
        val text = when (ref.kind) {
            BuiltInKind.Look -> assets.look(ref.file)
            BuiltInKind.Template -> assets.template(ref.file)
        }
        LayoutCodec.decode(text).document
    }.getOrNull()

    private fun bundledCandidates(): List<Pair<BuiltInRef, LayoutDocument>> {
        val refs = assets.lookFiles().map { BuiltInRef(BuiltInKind.Look, it) } +
            assets.templateFiles().map { BuiltInRef(BuiltInKind.Template, it) }
        return refs.mapNotNull { ref -> bundledDocument(ref)?.let { ref to it } }
    }

    private fun writeOrigin() {
        runCatching { writeAtomically(originFile, BuiltInOriginFile.encode(origin)) }
    }

    fun currentJson(): String = LayoutCodec.encode(_state.value.document)

    /**
     * Makes [doc] the active layout. The previous layout goes into history. [extracted] holds the
     * package lists [doc] had before it was upgraded (see `DecodeResult.extractedLists`). Pass [builtIn] when
     * [doc] is a built-in look or template as shipped, so a later OwnTV version can offer its newer version;
     * anything else (edits, imports, "only its style") ends that link.
     */
    suspend fun apply(doc: LayoutDocument, extracted: AppLists? = null, builtIn: BuiltInRef? = null) = mutex.withLock {
        extracted?.let(keepLists)
        withContext(Dispatchers.IO) {
            historyDir.mkdirs()
            val previous = _state.value
            if (previous.loaded && previous.problem == null) {
                File(historyDir, "${System.currentTimeMillis()}.json").writeText(LayoutCodec.encode(previous.document))
                prune()
                _hasHistory.value = true
            }
            val text = LayoutCodec.encode(doc)
            writeAtomically(currentFile, text)
            origin = builtIn?.let { BuiltInOrigin(it, BuiltInTracking.hash(text)) }
            writeOrigin()
            _builtInUpdate.value = null
            _state.value = LayoutState(doc, LayoutValidator.validate(doc))
        }
    }

    /** Replaces the layout with the newer built-in version. Returns false when there is none to apply. */
    suspend fun applyBuiltInUpdate(): Boolean {
        val update = _builtInUpdate.value ?: return false
        apply(update.document, builtIn = update.ref)
        return true
    }

    /** "Not now": stop prompting for this version of the built-in layout. [builtInUpdate] stays set for Settings. */
    suspend fun dismissBuiltInUpdate() = mutex.withLock {
        val update = _builtInUpdate.value ?: return@withLock
        withContext(Dispatchers.IO) {
            origin = origin?.copy(dismissedHash = update.hash)
            writeOrigin()
        }
        _builtInUpdate.value = update.copy(dismissed = true)
    }

    suspend fun history(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        historyFiles().map { file ->
            val name = LayoutCodec.decode(file.readText()).document?.name ?: "Unreadable layout"
            HistoryEntry(file, name, file.nameWithoutExtension.toLongOrNull() ?: file.lastModified())
        }
    }

    /** Restores the newest history entry. Returns false when there is none. */
    suspend fun revert(): Boolean {
        val latest = withContext(Dispatchers.IO) { historyFiles().firstOrNull() } ?: return false
        return restore(latest)
    }

    suspend fun restore(file: File): Boolean {
        val result = withContext(Dispatchers.IO) { LayoutCodec.decode(file.readText()) }
        apply(result.document ?: return false, result.extractedLists)
        withContext(Dispatchers.IO) {
            file.delete()
            _hasHistory.value = historyFiles().isNotEmpty()
        }
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

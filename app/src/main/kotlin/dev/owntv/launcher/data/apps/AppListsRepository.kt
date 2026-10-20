package dev.owntv.launcher.data.apps

import android.content.Context
import android.util.Log
import dev.owntv.launcher.data.layout.BundledAssets
import dev.owntv.layout.apps.AppListOps
import dev.owntv.layout.model.AppLists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The TV's app lists, custom app order and hidden apps, stored apart from the layout so they
 * survive layout and look changes. The first run starts from the bundled lists.
 */
class AppListsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val assets: BundledAssets,
) {
    private val file = File(context.filesDir, "app-lists.json")
    private val writeLock = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _lists = MutableStateFlow(AppLists())
    val lists: StateFlow<AppLists> = _lists.asStateFlow()

    /**
     * Lists that came from the bundled defaults and nobody has changed yet. When OwnTV is updated
     * from a version that kept the lists in the layout, the layout's own lists replace these.
     */
    private var untouchedDefaults = emptySet<String>()

    private val loadJob = scope.launch {
        val (lists, defaults) = withContext(Dispatchers.IO) {
            val stored = file.exists()
            val text = runCatching { if (stored) file.readText() else assets.defaultAppLists() }
                .onFailure { Log.w(TAG, "Could not read the app lists", it) }
                .getOrNull()
            val lists = text?.let { runCatching { json.decodeFromString(AppLists.serializer(), it) }.getOrNull() } ?: AppLists()
            lists to if (stored) emptySet() else lists.lists.keys
        }
        _lists.value = lists
        untouchedDefaults = defaults
    }

    /** Applies [change] and saves the result. Changes made before loading finished wait for it. */
    fun update(change: (AppLists) -> AppLists) = change(byUser = true, change)

    private fun change(byUser: Boolean, change: (AppLists) -> AppLists) {
        scope.launch(Dispatchers.Main.immediate) {
            loadJob.join()
            if (byUser) untouchedDefaults = emptySet()
            val updated = change(_lists.value)
            if (updated != _lists.value) {
                _lists.value = updated
                save(updated)
            }
        }
    }

    /** Shows [lists] without saving them, for move mode. [save] or another [update] stores them. */
    fun showUnsaved(lists: AppLists) {
        _lists.value = lists
    }

    fun saveCurrent() {
        untouchedDefaults = emptySet()
        save(_lists.value)
    }

    /**
     * Keeps the lists and hidden apps an older layout held itself. Lists the user has are not
     * overwritten; untouched bundled defaults are.
     */
    fun seed(from: AppLists) = change(byUser = false) { current ->
        val replaced = untouchedDefaults.intersect(from.lists.keys)
        untouchedDefaults = untouchedDefaults - replaced
        AppListOps.seed(current.copy(lists = current.lists - replaced), from)
    }

    private fun save(lists: AppLists) {
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(AppLists.serializer(), lists))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    private companion object {
        const val TAG = "AppLists"
    }
}

package dev.owntv.launcher.cleanup

import android.content.Context
import android.os.Build
import android.util.Log
import dev.owntv.launcher.adb.SelfAdb
import dev.owntv.launcher.data.layout.BundledAssets
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

/**
 * Settings > Apps > Clean up this TV: reads the TV's packages through [SelfAdb], turns packages off with
 * `pm disable-user` (never uninstalls) and back on with `pm enable`, and keeps a record of what it turned
 * off so it can undo it. The knowledge of which packages are safe comes from cleanyourtv ([CleanupCatalog]).
 */
class CleanupRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val assets: BundledAssets,
    private val adb: SelfAdb,
) {
    sealed interface Scan {
        data object None : Scan
        data object Running : Scan
        data class Done(val state: DeviceState) : Scan
        data class Failed(val error: Throwable?) : Scan
    }

    /** How the last change went: which packages did not change, and why when the TV said. */
    data class Outcome(val turnedOff: Boolean, val requested: List<String>, val failed: Map<String, String?>, val error: Throwable? = null) {
        val changed: Int get() = requested.size - failed.size
    }

    private val file = File(context.filesDir, "cleanup/record.json")
    private val lock = Mutex()

    private val _scan = MutableStateFlow<Scan>(Scan.None)
    val scan: StateFlow<Scan> = _scan.asStateFlow()

    private val _record = MutableStateFlow(CleanupRecord())
    val record: StateFlow<CleanupRecord> = _record.asStateFlow()

    private val _working = MutableStateFlow(false)

    /** True while a change runs. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /** Loaded on first use: about 650 KB of JSON nobody needs until the screen opens. */
    private var catalogCache: CleanupCatalog? = null

    init {
        scope.launch {
            _record.value = withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) CleanupRecord.decode(file.readText()) else CleanupRecord() }.getOrDefault(CleanupRecord())
            }
        }
    }

    suspend fun catalog(): CleanupCatalog = catalogCache ?: withContext(Dispatchers.IO) {
        CleanupCatalog.decode(assets.cleanupCatalog())
    }.also { catalogCache = it }

    /** The tested list for this TV, or the lists of its brand to choose from. */
    suspend fun matchLists(): ListMatch = CleanupPlanner.matchLists(catalog(), Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT)

    fun rescan() {
        if (_scan.value == Scan.Running) return
        _scan.value = Scan.Running
        scope.launch { _scan.value = readTv() }
    }

    private suspend fun readTv(): Scan = adb.run(CleanupScan.SCRIPT).fold(
        onSuccess = { result -> CleanupScan.parse(result.output)?.let { Scan.Done(it) } ?: Scan.Failed(null) },
        onFailure = { Scan.Failed(it) },
    )

    /** Turns [packages] off. The record is saved before the first command runs, then trimmed to what changed. */
    fun disable(packages: List<String>) = change(packages, turnOff = true)

    /** Turns [packages] back on and drops them from the record. */
    fun enable(packages: List<String>) = change(packages, turnOff = false)

    /** Turns on everything Clean up this TV turned off. */
    fun restoreAll() = enable(_record.value.packages.keys.sorted())

    fun dismissOutcome() {
        _outcome.value = null
    }

    private fun change(packages: List<String>, turnOff: Boolean) {
        val names = packages.filter(CleanupRules::isValidPackage).filterNot { turnOff && CleanupRules.isProtected(it) }.distinct()
        if (names.isEmpty() || _working.value) return
        _working.value = true
        scope.launch {
            lock.withLock {
                try {
                    if (turnOff) save(_record.value.added(names, System.currentTimeMillis()))
                    val output = StringBuilder()
                    var error: Throwable? = null
                    val commands = if (turnOff) CleanupPlanner.disableCommands(names) else CleanupPlanner.enableCommands(names)
                    for (command in commands) {
                        adb.run(command).fold(onSuccess = { output.appendLine(it.output) }, onFailure = { error = it })
                        if (error != null) break
                    }
                    val after = readTv()
                    val state = (after as? Scan.Done)?.state
                    _scan.value = after
                    val failed = when {
                        state == null -> names.associateWith { null }
                        turnOff -> CleanupPlanner.stillEnabled(names, state.enabled).associateWith { CleanupPlanner.errorFor(it, output.toString()) }
                        else -> names.filter { it !in state.enabled }.associateWith { CleanupPlanner.errorFor(it, output.toString()) }
                    }
                    // Keep in the record what is off now: packages the TV refused stay out, ones turned back on go.
                    val record = if (turnOff) _record.value.removed(failed.keys) else _record.value.removed(names - failed.keys)
                    save(record)
                    _outcome.value = Outcome(turnOff, names, failed, error ?: (after as? Scan.Failed)?.error)
                } catch (e: Exception) {
                    Log.w(TAG, "Clean up failed", e)
                    _outcome.value = Outcome(turnOff, names, names.associateWith { null }, e)
                } finally {
                    _working.value = false
                }
            }
        }
    }

    private suspend fun save(record: CleanupRecord) {
        _record.value = record
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(record.encode())
            if (!temp.renameTo(file)) {
                file.writeText(record.encode())
                temp.delete()
            }
        }
    }

    private companion object {
        const val TAG = "CleanupRepository"
    }
}

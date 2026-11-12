package dev.owntv.launcher.data.apps

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recently used apps, most recent first. Launches made from OwnTV are always recorded. Apps opened any
 * other way count when the remote service is on (it reports the app in front) or the user grants usage
 * access (`adb shell appops set dev.owntv.launcher GET_USAGE_STATS allow`).
 */
class RecentAppsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()
    private var pendingRefresh: Job? = null

    init {
        refresh()
    }

    fun recordLaunch(packageName: String) {
        prefs.edit().putLong(packageName, System.currentTimeMillis()).apply()
        refresh()
    }

    /** An app came to the front without OwnTV launching it (reported by the remote service). */
    fun recordUse(packageName: String) {
        prefs.edit().putLong(packageName, System.currentTimeMillis()).apply()
        refreshSoon()
    }

    fun refresh() {
        scope.launch {
            _recent.value = withContext(Dispatchers.IO) { load() }
        }
    }

    /** Apps can switch quickly (a launch that opens a splash, then the app), so the list updates once things settle. */
    private fun refreshSoon() {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            _recent.value = withContext(Dispatchers.IO) { load() }
        }
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun load(): List<String> {
        val times = mutableMapOf<String, Long>()
        prefs.all.forEach { (pkg, value) -> (value as? Long)?.let { times[pkg] = it } }
        if (hasUsageAccess()) {
            val usage = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            runCatching { usage?.queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now) }.getOrNull()?.forEach { (pkg, stats) ->
                if (stats.lastTimeUsed > 0 && stats.totalTimeInForeground > 0) {
                    times[pkg] = maxOf(times[pkg] ?: 0L, stats.lastTimeUsed)
                }
            }
        }
        times.remove(context.packageName)
        return times.entries.sortedByDescending { it.value }.map { it.key }
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 500L
        private const val USAGE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}

package dev.owntv.launcher.data.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.core.content.ContextCompat
import dev.owntv.launcher.ui.render.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's launcher activity. */
val AppInfo.component: ComponentName get() = ComponentName(packageName, activityName)

/** The launchable apps on this TV, kept up to date when apps are installed, updated or removed. */
class InstalledAppsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        scope.launch {
            _apps.value = withContext(Dispatchers.IO) { load() }
            _loaded.value = true
        }
    }

    /** Installed package names, or null while the first load is still running. */
    fun installedPackagesOrNull(): Set<String>? =
        if (_loaded.value) _apps.value.mapTo(mutableSetOf()) { it.packageName } else null

    private fun load(): List<AppInfo> {
        val pm = context.packageManager
        val result = linkedMapOf<String, AppInfo>()
        fun add(entries: List<ResolveInfo>, tv: Boolean) {
            for (entry in entries) {
                val activity = entry.activityInfo ?: continue
                val pkg = activity.packageName
                // OwnTV's launchable entry is its settings shortcut, so include our own package.
                if (pkg in result) continue
                val app = activity.applicationInfo
                val packageInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                @Suppress("DEPRECATION")
                val isGame = app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                result[pkg] = AppInfo(
                    packageName = pkg,
                    label = entry.loadLabel(pm).toString(),
                    activityName = activity.name,
                    isGame = isGame,
                    isTvApp = tv,
                    firstInstallTime = packageInfo?.firstInstallTime ?: 0L,
                    lastUpdateTime = packageInfo?.lastUpdateTime ?: 0L,
                )
            }
        }
        add(pm.query(Intent.CATEGORY_LEANBACK_LAUNCHER), tv = true)
        add(pm.query(Intent.CATEGORY_LAUNCHER), tv = false)
        return result.values.toList()
    }

    private fun PackageManager.query(category: String): List<ResolveInfo> =
        queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
}

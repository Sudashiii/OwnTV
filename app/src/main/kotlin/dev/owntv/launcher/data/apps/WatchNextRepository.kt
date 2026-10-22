package dev.owntv.launcher.data.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import dev.owntv.launcher.ui.render.WatchNextAccess
import dev.owntv.launcher.ui.render.WatchNextCatalog
import dev.owntv.layout.model.WatchNextEntry
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
 * What the TV's apps offer to continue watching (Android TV's Watch Next), for `watch_next_row`.
 *
 * Apps publish these entries to the system TV provider. Reading other apps' entries needs
 * `READ_TV_LISTINGS`, a runtime permission: [requestAccess] asks for it and the provider then hands
 * out every entry its app left searchable (the default). Entries are read only; only the app that
 * published one can change or remove it.
 */
class WatchNextRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(WatchNextCatalog(access = access()))
    val state: StateFlow<WatchNextCatalog> = _state.asStateFlow()

    /** Shows the system's permission dialog; set by the activity. Calls [onPermissionResult] when answered. */
    var permissionLauncher: (() -> Unit)? = null

    private var asked = false
    private var refreshJob: Job? = null

    init {
        // A burst of changes (an app rewriting all its entries) is read once.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh(delayMillis = 400)
        }
        context.contentResolver.registerContentObserver(WatchNextPrograms.CONTENT_URI, true, observer)
        refresh()
    }

    fun refresh(delayMillis: Long = 0) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    /** Asks for the permission; when the system will not ask again, opens OwnTV's app settings instead. */
    fun requestAccess() {
        val launcher = permissionLauncher ?: return
        if (asked) {
            toast("Allow \"TV listings\" for OwnTV in the app settings, then come back.")
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        asked = true
        launcher()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) asked = false
        refresh()
    }

    /** Resumes [entry] in its app, or just opens the app when the entry has no usable link. */
    fun open(entry: WatchNextEntry) {
        val resume = entry.intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        val fallback = context.packageManager.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(entry.packageName)
        if (resume != null && start(resume)) return
        if (fallback == null || !start(fallback)) toast("Could not open ${entry.title}")
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun access() =
        if (context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) WatchNextAccess.Granted else WatchNextAccess.NeedsPermission

    private fun load(): WatchNextCatalog {
        val access = access()
        if (access != WatchNextAccess.Granted) return WatchNextCatalog(access)
        val entries = try {
            read()
        } catch (e: RuntimeException) {
            // The provider can refuse (no TV provider on this device, permission revoked meanwhile).
            emptyList()
        }
        return WatchNextCatalog(access, entries)
    }

    private fun read(): List<WatchNextEntry> {
        val projection = arrayOf(
            WatchNextPrograms._ID,
            WatchNextPrograms.COLUMN_PACKAGE_NAME,
            WatchNextPrograms.COLUMN_TITLE,
            WatchNextPrograms.COLUMN_POSTER_ART_URI,
            WatchNextPrograms.COLUMN_DURATION_MILLIS,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            WatchNextPrograms.COLUMN_INTENT_URI,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        val entries = mutableListOf<WatchNextEntry>()
        // The provider refuses a selection from callers that are not system apps, so nothing is filtered here.
        context.contentResolver.query(WatchNextPrograms.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2)?.trim().orEmpty()
                val pkg = c.getString(1) ?: continue
                if (title.isEmpty() || pkg == context.packageName) continue
                entries += WatchNextEntry(
                    id = "$pkg/${c.getLong(0)}",
                    packageName = pkg,
                    title = title,
                    posterUri = c.getString(3)?.takeIf { it.isNotBlank() },
                    durationMillis = c.getLong(4),
                    positionMillis = c.getLong(5),
                    intentUri = c.getString(6)?.takeIf { it.isNotBlank() },
                    lastEngagementMillis = c.getLong(7),
                )
            }
        }
        return entries
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    }
}

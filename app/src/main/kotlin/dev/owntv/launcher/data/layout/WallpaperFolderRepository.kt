package dev.owntv.launcher.data.layout

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.owntv.launcher.ui.render.WallpaperFolders
import dev.owntv.layout.wallpaper.FolderFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures and videos in a folder of the TV's shared storage (USB drives included), for slideshow
 * backgrounds with a `folder`. Reading them needs the photos and videos permission: the first slideshow
 * that names a folder asks for it, once per launch of the app.
 */
class WallpaperFolderRepository(private val context: Context) : WallpaperFolders {
    override var generation by mutableIntStateOf(0)
        private set

    /** Shows the system's permission dialog for [PERMISSIONS]; set by the activity. Calls [onPermissionResult] when answered. */
    var permissionLauncher: (() -> Unit)? = null

    private var asked = false
    private var hadAccess = hasAccess()

    override suspend fun list(path: String): List<FolderFile> {
        if (!hasAccess()) {
            withContext(Dispatchers.Main) {
                val launcher = permissionLauncher
                if (!asked && launcher != null) {
                    asked = true
                    launcher()
                }
            }
            return emptyList()
        }
        return withContext(Dispatchers.IO) { runCatching { query(path) }.getOrDefault(emptyList()) }
    }

    fun onPermissionResult() = refresh()

    /** Lets slideshows read their folders again when access changed, e.g. in the app settings. */
    fun refresh() {
        val access = hasAccess()
        if (access != hadAccess) {
            hadAccess = access
            generation++
        }
    }

    private fun hasAccess() = PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun query(path: String): List<FolderFile> {
        val folder = relativePath(path) ?: return emptyList()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.VOLUME_NAME,
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)" +
            " AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            folder.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
        )
        val order = "${MediaStore.Files.FileColumns.RELATIVE_PATH}, ${MediaStore.Files.FileColumns.DISPLAY_NAME}"
        val files = mutableListOf<FolderFile>()
        context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args, order)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val type = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val volume = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.VOLUME_NAME)
            while (cursor.moveToNext()) {
                val video = cursor.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val volumeName = cursor.getString(volume) ?: MediaStore.VOLUME_EXTERNAL
                val base = if (video) MediaStore.Video.Media.getContentUri(volumeName) else MediaStore.Images.Media.getContentUri(volumeName)
                files += FolderFile(ContentUris.withAppendedId(base, cursor.getLong(id)).toString(), video)
            }
        }
        return files
    }

    companion object {
        val PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        private val storageRoots = listOf("/sdcard/", "/storage/emulated/0/", "storage/emulated/0/", "sdcard/")

        /** MediaStore's relative path for a folder as layouts write it ("Pictures/OwnTV", "/sdcard/Pictures/OwnTV/"), or null for none. */
        fun relativePath(path: String): String? {
            var p = path.trim().replace('\\', '/')
            storageRoots.firstOrNull { p.startsWith(it) }?.let { p = p.removePrefix(it) }
            p = p.trim('/')
            return if (p.isEmpty()) null else "$p/"
        }
    }
}

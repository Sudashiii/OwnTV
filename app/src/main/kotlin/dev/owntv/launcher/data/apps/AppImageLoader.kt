package dev.owntv.launcher.data.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.owntv.launcher.ui.render.AppImages
import dev.owntv.launcher.ui.render.AppImages.Kind
import dev.owntv.launcher.ui.render.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Loads app banners and icons as bitmaps, with a memory cache sized for low-RAM TV devices. */
class AppImageLoader(context: Context) : AppImages {
    private val pm: PackageManager = context.packageManager

    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Keys of apps that have no banner, so we do not ask the package manager again. */
    private val noBanner = ConcurrentHashMap.newKeySet<String>()

    override fun cached(app: AppInfo, kind: Kind): ImageBitmap? = cache.get(key(app, kind))

    fun hasNoBanner(app: AppInfo) = key(app, Kind.Banner) in noBanner

    override suspend fun load(app: AppInfo, kind: Kind): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = key(app, kind)
        cache.get(key)?.let { return@withContext it }
        if (kind == Kind.Banner && key in noBanner) return@withContext null
        val drawable = runCatching { drawableFor(app, kind) }.getOrNull()
        if (drawable == null) {
            if (kind == Kind.Banner) noBanner += key
            return@withContext null
        }
        val (width, height) = when (kind) {
            Kind.Banner -> 320 to 180
            Kind.Icon -> 192 to 192
        }
        drawable.toBitmap(width, height).asImageBitmap().also { cache.put(key, it) }
    }

    private fun drawableFor(app: AppInfo, kind: Kind): Drawable? = when (kind) {
        Kind.Banner -> pm.getActivityBanner(app.component) ?: pm.getApplicationBanner(app.packageName)
        Kind.Icon -> pm.getActivityIcon(app.component)
    }

    private fun key(app: AppInfo, kind: Kind) = "${app.component.flattenToShortString()}@${app.lastUpdateTime}/$kind"
}

package dev.owntv.launcher.data.layout

import android.content.Context
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * Loads the images a layout points at with `https://` links (backgrounds, heroes, tiles, image nodes).
 *
 * Every image is stored on the TV the first time it is shown and then served from there: the cache lives
 * in `cache/layout-images`, ignores the server's no-store/no-cache headers and keeps images for [MAX_AGE_DAYS]
 * days, so the home screen does not wait for the network and still shows its artwork offline. To refresh an
 * image the layout changes its URL (the URL is the cache key).
 */
object LayoutImageLoader {
    private const val MAX_AGE_DAYS = 30L
    private const val DISK_CACHE_BYTES = 256L * 1024 * 1024

    fun create(context: Context, events: EventListener.Factory = EventListener.Factory.NONE): ImageLoader {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .removeHeader("Pragma")
                    .removeHeader("Expires")
                    .removeHeader("Vary")
                    .header("Cache-Control", "public, max-age=${TimeUnit.DAYS.toSeconds(MAX_AGE_DAYS)}")
                    .build()
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("layout-images").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .eventListenerFactory(events)
            .build()
    }

    /** Bytes the downloaded images take up on the TV. Reads the disk, so call it off the main thread. */
    fun cacheSize(context: Context): Long = SingletonImageLoader.get(context).diskCache?.size ?: 0L

    /** Deletes every downloaded image; layouts download them again the next time they show them. Call it off the main thread. */
    fun clearCache(context: Context) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}

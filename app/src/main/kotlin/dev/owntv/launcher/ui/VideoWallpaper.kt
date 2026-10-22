package dev.owntv.launcher.ui

import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.owntv.launcher.ui.render.VideoPlayers
import dev.owntv.layout.model.ImageFit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video backgrounds with Media3: silent, looping, paused while the launcher is in the background or
 * covered. Downloaded videos are kept on the TV (like layout images), so a loop is not downloaded
 * again on every pass and plays offline later.
 */
object ExoVideoPlayers : VideoPlayers {
    @OptIn(UnstableApi::class)
    @Composable
    override fun Video(src: String, fit: ImageFit, colorMatrix: FloatArray?, playing: Boolean, modifier: Modifier) {
        val context = LocalContext.current
        val player = remember(src) {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.dataSource(context)))
                .build()
                .apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    setMediaItem(MediaItem.fromUri(src))
                    prepare()
                }
        }
        var videoSize by remember(player) { mutableStateOf(IntSize.Zero) }
        var shown by remember(player) { mutableStateOf(false) }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) videoSize = IntSize((size.width * size.pixelWidthHeightRatio).roundToInt(), size.height)
                }

                override fun onRenderedFirstFrame() {
                    shown = true
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val play = playing && lifecycle.isAtLeast(Lifecycle.State.STARTED)
        LaunchedEffect(player, play) { player.playWhenReady = play }

        Box(modifier.clipToBounds()) {
            AndroidView(
                factory = { TextureView(it).also(player::setVideoTextureView) },
                update = { view ->
                    view.alpha = if (shown) 1f else 0f
                    view.setLayerPaint(colorMatrix?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(it) } })
                },
                modifier = Modifier.fitVideo(videoSize, fit),
            )
        }
    }
}

/** Sizes the video to the area like an image with [fit], centered; the caller clips what reaches past it. */
private fun Modifier.fitVideo(video: IntSize, fit: ImageFit): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val (w, h) = if (video == IntSize.Zero || fit == ImageFit.Fill) {
        width to height
    } else {
        val sx = width.toFloat() / video.width
        val sy = height.toFloat() / video.height
        val scale = if (fit == ImageFit.Cover) max(sx, sy) else min(sx, sy)
        (video.width * scale).roundToInt() to (video.height * scale).roundToInt()
    }
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(width, height) { placeable.place((width - w) / 2, (height - h) / 2) }
}

/** The videos backgrounds have played, kept in `cache/layout-videos` up to [MAX_BYTES]. */
@OptIn(UnstableApi::class)
private object VideoCache {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private var cache: SimpleCache? = null

    fun dataSource(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext
        val simpleCache = cache ?: SimpleCache(
            app.cacheDir.resolve("layout-videos"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(app),
        ).also { cache = it }
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

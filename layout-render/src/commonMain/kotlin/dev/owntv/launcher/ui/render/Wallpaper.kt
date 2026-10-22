package dev.owntv.launcher.ui.render

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.owntv.layout.model.ImageAdjust
import dev.owntv.layout.model.ImageFit
import dev.owntv.layout.model.SlideshowBackground
import dev.owntv.layout.model.TextStyleName
import dev.owntv.layout.wallpaper.FolderFile
import dev.owntv.layout.wallpaper.WallpaperPick
import dev.owntv.layout.wallpaper.Wallpapers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Which wallpaper a slideshow shows now. [next] changes it at once (the next_wallpaper action). */
@Stable
class SlideshowState internal constructor(initial: WallpaperPick?, internal var index: Int?) {
    var current by mutableStateOf(initial)
        internal set
    internal var turn = 0
    internal val requests = Channel<Unit>(Channel.CONFLATED)

    /** Shows the next wallpaper now. */
    fun next() {
        requests.trySend(Unit)
    }
}

/**
 * Runs [slideshow]: picks the first wallpaper right away from its own list (the folder is read
 * after), then the next one every `interval` seconds and on [SlideshowState.next]. Pictures are
 * downloaded before they fade in. While [LocalBackgroundPaused] (or the app is in the background,
 * which stops frames) the timer waits, so returning to the launcher after a while brings a new one.
 */
@Composable
fun rememberSlideshow(slideshow: SlideshowBackground): SlideshowState {
    val state = remember(slideshow) {
        val pool = Wallpapers.pool(slideshow, emptyList())
        val index = Wallpapers.nextIndex(pool.size, null, slideshow.order)
        SlideshowState(index?.let { Wallpapers.show(pool[it], 0) }, index)
    }
    val folders = LocalWallpaperFolders.current
    val generation = folders?.generation ?: 0
    val context = LocalPlatformContext.current
    val paused by rememberUpdatedState(LocalBackgroundPaused.current)
    LaunchedEffect(state, folders, generation) {
        suspend fun folderFiles() =
            slideshow.folder?.takeIf { it.isNotBlank() }?.let { path -> folders?.let { runCatching { it.list(path) }.getOrNull() } }.orEmpty()

        suspend fun advance(files: List<FolderFile>) {
            val pool = Wallpapers.pool(slideshow, files)
            val i = Wallpapers.nextIndex(pool.size, state.index, slideshow.order)
            if (i == null) {
                state.current = null
                return
            }
            val pick = Wallpapers.show(pool[i], ++state.turn)
            if (!pick.video) preload(context, pick.src)
            state.index = i
            state.current = pick
        }
        // Read the folder now even when the list gave the first wallpaper: that asks for access early.
        val files = folderFiles()
        if (state.current == null) advance(files)
        while (true) {
            val interval = slideshow.interval
            if (interval > 0) withTimeoutOrNull(interval * 1000L) { state.requests.receive() } else state.requests.receive()
            snapshotFlow { paused }.first { !it }
            withFrameNanos { }
            advance(folderFiles())
        }
    }
    return state
}

/** Loads a picture into the image cache, so it fades in complete. Gives up after a while on a slow network. */
private suspend fun preload(context: PlatformContext, src: String) {
    withTimeoutOrNull(PRELOAD_TIMEOUT_MS) {
        SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(imageModel(src)).build())
    }
}

/** The wallpaper [state] shows, crossfading over [transition] milliseconds; the theme background until there is one. */
@Composable
internal fun SlideshowFill(state: SlideshowState, transition: Int, modifier: Modifier, shape: Shape) {
    val theme = LocalOwnTheme.current
    Box(modifier.clip(shape)) {
        Crossfade(state.current, Modifier.matchParentSize(), animationSpec = tween(transition.coerceIn(0, 5000)), label = "wallpaper") { pick ->
            if (pick == null) {
                Box(Modifier.fillMaxSize().background(theme.background))
            } else {
                MediaFill(pick.src, pick.video, pick.dim, pick.blur, pick.fit, pick.adjust, Modifier.fillMaxSize())
            }
        }
    }
}

/** A picture (animated ones play) or a video, with its blur, color adjustments and dim. */
@Composable
internal fun MediaFill(src: String, video: Boolean, dim: Float, blur: Int, fit: ImageFit, adjust: ImageAdjust?, modifier: Modifier) {
    val matrix = remember(adjust) { Wallpapers.colorMatrix(adjust) }
    Box(modifier) {
        val inner = Modifier.matchParentSize().then(if (blur > 0) Modifier.blur(blur.dp) else Modifier)
        if (video) {
            val players = LocalVideoPlayers.current
            if (players != null) {
                players.Video(imageModel(src), fit, matrix, playing = !LocalBackgroundPaused.current, modifier = inner)
            } else {
                VideoPlaceholder(inner)
            }
        } else {
            AsyncImage(
                model = imageModel(src),
                contentDescription = null,
                contentScale = fit.toContentScale(),
                colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                modifier = inner,
            )
        }
        if (dim > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f))))
    }
}

/** Stands in for a video where none can play (the web preview). */
@Composable
private fun VideoPlaceholder(modifier: Modifier) {
    val theme = LocalOwnTheme.current
    Box(modifier.background(Brush.linearGradient(listOf(theme.surface, theme.background))), contentAlignment = Alignment.Center) {
        Text("▶  Video wallpaper (plays on the TV)", style = theme.textStyle(TextStyleName.Caption), color = theme.textMuted)
    }
}

private const val PRELOAD_TIMEOUT_MS = 20_000L

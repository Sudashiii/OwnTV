package dev.owntv.layout.wallpaper

import dev.owntv.layout.model.ImageAdjust
import dev.owntv.layout.model.ImageFit
import dev.owntv.layout.model.SlideshowBackground
import dev.owntv.layout.model.SlideshowOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * One wallpaper of a slideshow with its look settled. [fresh] marks a web address that returns a new
 * picture on every request: [Wallpapers.show] gives it a new URL each time it comes up.
 */
data class WallpaperPick(
    val src: String,
    val video: Boolean,
    val dim: Float,
    val blur: Int,
    val fit: ImageFit,
    val adjust: ImageAdjust?,
    val fresh: Boolean = false,
)

/** A picture or video found in a slideshow's folder. */
data class FolderFile(val src: String, val video: Boolean)

/** Pure slideshow logic: which wallpapers there are, which comes next and how adjustments color them. */
object Wallpapers {
    private val videoExtensions = setOf("mp4", "webm", "mkv", "mov", "m3u8")

    /** True when [src] names a video by its file extension (query and fragment ignored). */
    fun isVideo(src: String): Boolean {
        val path = src.substringBefore('#').substringBefore('?')
        return path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in videoExtensions
    }

    /** The wallpapers of [slideshow] in order: its images, then the [folder] files, then the random address as one entry. */
    fun pool(slideshow: SlideshowBackground, folder: List<FolderFile>): List<WallpaperPick> = buildList {
        slideshow.images.forEach { image ->
            add(
                WallpaperPick(
                    src = image.src,
                    video = isVideo(image.src),
                    dim = image.dim ?: slideshow.dim,
                    blur = image.blur ?: slideshow.blur,
                    fit = image.fit ?: slideshow.fit,
                    adjust = image.adjust ?: slideshow.adjust,
                ),
            )
        }
        folder.forEach { add(WallpaperPick(it.src, it.video, slideshow.dim, slideshow.blur, slideshow.fit, slideshow.adjust)) }
        slideshow.randomUrl?.takeIf { it.isNotBlank() }?.let {
            add(WallpaperPick(it, video = false, dim = slideshow.dim, blur = slideshow.blur, fit = slideshow.fit, adjust = slideshow.adjust, fresh = true))
        }
    }

    /**
     * The index to show after [current] (null: nothing shown yet) among [size] wallpapers, or null when
     * there are none. Shuffle never repeats the current one unless it is the only one.
     */
    fun nextIndex(size: Int, current: Int?, order: SlideshowOrder, random: Random = Random): Int? {
        if (size <= 0) return null
        val now = current?.takeIf { it in 0 until size }
        return when (order) {
            SlideshowOrder.Sequential -> if (current == null) 0 else (current + 1) % size
            SlideshowOrder.Shuffle -> when {
                now == null -> random.nextInt(size)
                size == 1 -> 0
                else -> random.nextInt(size - 1).let { if (it >= now) it + 1 else it }
            }
        }
    }

    /** [pick] as it is shown for the [turn]-th time: a fresh address gets a new URL, so it is not served from the cache. */
    fun show(pick: WallpaperPick, turn: Int): WallpaperPick =
        if (!pick.fresh) pick else pick.copy(src = pick.src + (if ('?' in pick.src) "&" else "?") + "owntv=$turn")

    /**
     * The 4 × 5 color matrix (row-major, offsets in 0–255 like Android's ColorMatrix) for [adjust], or
     * null when it leaves colors unchanged. Hue turns first, then saturation, contrast and brightness.
     */
    fun colorMatrix(adjust: ImageAdjust?): FloatArray? {
        if (adjust == null) return null
        val brightness = adjust.brightness.coerceIn(-1f, 1f)
        val contrast = adjust.contrast.coerceIn(0f, 2f)
        val saturation = adjust.saturation.coerceIn(0f, 2f)
        val hue = adjust.hue.coerceIn(-180, 180)
        if (brightness == 0f && contrast == 1f && saturation == 1f && hue == 0) return null
        var m = identity()
        if (hue != 0) m = concat(hueMatrix(hue), m)
        if (saturation != 1f) m = concat(saturationMatrix(saturation), m)
        if (contrast != 1f) m = concat(scaleMatrix(contrast, 127.5f * (1f - contrast)), m)
        if (brightness != 0f) m = concat(scaleMatrix(1f, brightness * 255f), m)
        return m
    }

    /** [argb] colored by [matrix] from [colorMatrix], as a drawn picture would be. */
    fun applyMatrix(argb: Int, matrix: FloatArray): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        fun channel(row: Int): Int {
            val o = row * 5
            val v = matrix[o] * r + matrix[o + 1] * g + matrix[o + 2] * b + matrix[o + 3] * a + matrix[o + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        return (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    private fun identity() = scaleMatrix(1f, 0f)

    private fun scaleMatrix(scale: Float, offset: Float) = floatArrayOf(
        scale, 0f, 0f, 0f, offset,
        0f, scale, 0f, 0f, offset,
        0f, 0f, scale, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )

    // Luminance weights and formulas as in SVG's feColorMatrix (saturate, hueRotate).
    private fun saturationMatrix(s: Float) = rgb(
        0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
        0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
        0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s,
    )

    private fun hueMatrix(degrees: Int): FloatArray {
        val r = degrees * PI / 180.0
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return rgb(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f,
        )
    }

    private fun rgb(vararg m: Float) = floatArrayOf(
        m[0], m[1], m[2], 0f, 0f,
        m[3], m[4], m[5], 0f, 0f,
        m[6], m[7], m[8], 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** [a] applied after [b]. */
    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var v = if (col == 4) a[row * 5 + 4] else 0f
                for (k in 0 until 4) v += a[row * 5 + k] * b[k * 5 + col]
                out[row * 5 + col] = v
            }
        }
        return out
    }
}

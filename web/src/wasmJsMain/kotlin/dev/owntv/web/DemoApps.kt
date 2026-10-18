package dev.owntv.web

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.owntv.launcher.ui.render.AppCatalog
import dev.owntv.launcher.ui.render.AppImages
import dev.owntv.launcher.ui.render.AppInfo
import dev.owntv.layout.model.AppGridNode
import dev.owntv.layout.model.AppRowNode
import dev.owntv.layout.model.AppSource
import dev.owntv.layout.model.AppTileNode
import dev.owntv.layout.model.FolderNode
import dev.owntv.layout.model.HeroNode
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.PackagesSource
import dev.owntv.layout.model.ShortcutNode
import dev.owntv.layout.model.walkNodes

/** Pretend installed apps, so layouts show tiles in the browser. */
object DemoApps {
    private data class Demo(val packageName: String, val label: String, val isGame: Boolean = false, val isTvApp: Boolean = true)

    private val demos = listOf(
        Demo("com.netflix.ninja", "Netflix"),
        Demo("com.google.android.youtube.tv", "YouTube"),
        Demo("com.disney.disneyplus", "Disney+"),
        Demo("com.amazon.amazonvideo.livingroom", "Prime Video"),
        Demo("com.wbd.stream", "Max"),
        Demo("com.apple.atve.androidtv.appletv", "Apple TV"),
        Demo("com.plexapp.android", "Plex"),
        Demo("com.spotify.tv.android", "Spotify"),
        Demo("com.google.android.youtube.tvmusic", "YouTube Music"),
        Demo("com.google.android.youtube.tvkids", "YouTube Kids"),
        Demo("tv.twitch.android.app", "Twitch"),
        Demo("org.xbmc.kodi", "Kodi"),
        Demo("com.valvesoftware.steamlink", "Steam Link", isGame = true),
        Demo("com.nvidia.geforcenow", "GeForce NOW", isGame = true),
        Demo("com.limelight", "Moonlight", isGame = true),
        Demo("com.retroarch", "RetroArch", isGame = true),
        Demo("com.android.vending", "Play Store"),
        Demo("com.android.tv.settings", "Settings"),
        Demo("com.android.chrome", "Chrome", isTvApp = false),
    )

    private val recent = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.spotify.tv.android", "com.disney.disneyplus", "com.plexapp.android",
    )

    /** Ignored when naming unknown packages: "com.example.tv.app" becomes "Example". */
    private val genericParts = setOf("com", "org", "net", "tv", "app", "apps", "android", "androidtv", "livingroom", "mobile", "google")

    /** The demo apps plus any package [doc] mentions, so pasted layouts never lose their tiles. */
    fun catalogFor(doc: LayoutDocument?): AppCatalog {
        val known = demos.map { it.packageName }.toSet()
        val extra = doc?.let(::referencedPackages).orEmpty().filter { it !in known }.map { Demo(it, labelFor(it)) }
        val all = demos + extra
        val apps = all.mapIndexed { index, demo ->
            AppInfo(
                packageName = demo.packageName,
                label = demo.label,
                activityName = "${demo.packageName}.MainActivity",
                isGame = demo.isGame,
                isTvApp = demo.isTvApp,
                // Newer installs first in list order, so "recently installed" sorting is visible.
                firstInstallTime = 1_700_000_000_000L - index * 86_400_000L,
                lastUpdateTime = 0L,
            )
        }
        return AppCatalog(apps, loaded = true, recent = recent)
    }

    private fun referencedPackages(doc: LayoutDocument): Set<String> {
        val packages = linkedSetOf<String>()
        packages += doc.apps.keys
        fun source(source: AppSource) {
            if (source is PackagesSource) packages += source.packages
        }
        doc.walkNodes { _, node, _, _ ->
            when (node) {
                is AppTileNode -> packages += node.packageName
                is AppRowNode -> source(node.source)
                is AppGridNode -> source(node.source)
                is FolderNode -> source(node.source)
                is ShortcutNode -> (node.action as? LaunchAppAction)?.let { packages += it.packageName }
                is HeroNode -> node.slides.forEach { slide -> (slide.action as? LaunchAppAction)?.let { packages += it.packageName } }
                else -> Unit
            }
        }
        return packages
    }

    private fun labelFor(packageName: String): String {
        val part = packageName.split('.').drop(1).firstOrNull { it.lowercase() !in genericParts } ?: packageName.substringAfterLast('.')
        return part.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Draws stand-in banners and icons: a color from the package name and the app's label. No logos. */
class DemoAppImages(private val measurer: TextMeasurer, private val font: FontFamily) : AppImages {
    private val cache = mutableMapOf<String, ImageBitmap>()

    override fun cached(app: AppInfo, kind: AppImages.Kind): ImageBitmap? = cache[key(app, kind)]

    override suspend fun load(app: AppInfo, kind: AppImages.Kind): ImageBitmap =
        cache.getOrPut(key(app, kind)) { draw(app, kind) }

    private fun key(app: AppInfo, kind: AppImages.Kind) = "${app.packageName}/${app.label}/$kind"

    private fun draw(app: AppInfo, kind: AppImages.Kind): ImageBitmap {
        val banner = kind == AppImages.Kind.Banner
        val (width, height) = if (banner) 320 to 180 else 192 to 192
        val hue = (app.packageName.hashCode().toUInt() % 360u).toFloat()
        val brush = Brush.linearGradient(
            listOf(Color.hsv(hue, 0.55f, 0.78f), Color.hsv((hue + 40f) % 360f, 0.65f, 0.52f)),
            start = Offset.Zero,
            end = Offset(width.toFloat(), height.toFloat()),
        )
        val text = if (banner) app.label else initials(app.label)
        val style = TextStyle(
            color = Color.White,
            fontSize = if (banner) 34.sp else 76.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
            textAlign = TextAlign.Center,
        )
        // Measured at density 1: the bitmap is in pixels, not in the page's dp.
        val layout = measurer.measure(text, style, TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = width - 32), density = Density(1f))
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            if (banner) drawRect(brush) else drawRoundRect(brush, cornerRadius = CornerRadius(44f))
            drawText(layout, topLeft = Offset((width - layout.size.width) / 2f, (height - layout.size.height) / 2f))
        }
        return bitmap
    }

    private fun initials(label: String): String =
        label.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
}

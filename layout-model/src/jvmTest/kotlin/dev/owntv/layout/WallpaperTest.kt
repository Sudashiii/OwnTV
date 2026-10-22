package dev.owntv.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.ImageAdjust
import dev.owntv.layout.model.ImageBackground
import dev.owntv.layout.model.ImageFit
import dev.owntv.layout.model.SlideshowBackground
import dev.owntv.layout.model.SlideshowImage
import dev.owntv.layout.model.SlideshowOrder
import dev.owntv.layout.model.VideoBackground
import dev.owntv.layout.validation.Severity
import dev.owntv.layout.wallpaper.FolderFile
import dev.owntv.layout.wallpaper.Wallpapers
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WallpaperTest {
    private val shortcut = """{ "type": "shortcut", "id": "next", "label": "Next", "action": { "type": "next_wallpaper" } }"""

    @Test
    fun `slideshow, video and adjusted image backgrounds survive a round trip`() {
        val background = """
            "background": { "type": "slideshow", "interval": 600, "order": "sequential", "transition": 800, "dim": 0.2,
              "adjust": { "saturation": 1.2 },
              "images": [
                { "src": "https://example.com/a.jpg" },
                { "src": "https://example.com/b.gif", "dim": 0, "fit": "contain", "adjust": { "brightness": -0.1, "hue": 30 } }
              ],
              "folder": "Pictures/OwnTV", "randomUrl": "https://picsum.photos/1920/1080" },
        """
        val json = docWithRoot(
            """{ "type": "column", "id": "root", "children": [ $shortcut,
                 { "type": "column", "id": "video", "background": { "type": "video", "src": "https://example.com/loop.mp4", "fit": "contain" }, "children": [] },
                 { "type": "column", "id": "picture", "background": { "type": "image", "src": "asset:bg.jpg", "adjust": { "contrast": 1.3 } }, "children": [] } ] }""",
            background,
        )
        val result = LayoutCodec.decode(json)
        val doc = result.requireDocument()
        assertEquals(emptyList(), result.issues.filter { it.severity == Severity.Error }.map { it.message })
        val slideshow = assertIs<SlideshowBackground>(doc.background)
        assertEquals(SlideshowOrder.Sequential, slideshow.order)
        assertEquals(SlideshowImage("https://example.com/b.gif", dim = 0f, fit = ImageFit.Contain, adjust = ImageAdjust(brightness = -0.1f, hue = 30)), slideshow.images[1])
        val root = doc.pages[0].root as dev.owntv.layout.model.ColumnNode
        assertEquals(VideoBackground("https://example.com/loop.mp4", fit = ImageFit.Contain), root.children[1].common.background)
        assertEquals(ImageAdjust(contrast = 1.3f), (root.children[2].common.background as ImageBackground).adjust)
        assertEquals(doc, LayoutCodec.decode(LayoutCodec.encode(doc)).requireDocument())
    }

    @Test
    fun `slideshows need something to show and web addresses for randomUrl`() {
        val empty = LayoutCodec.decode(docWithRoot(TILE, """ "background": { "type": "slideshow" }, """))
        assertTrue("$.background" in empty.paths(Severity.Error), empty.issues.toString())
        val badUrl = LayoutCodec.decode(docWithRoot(TILE, """ "background": { "type": "slideshow", "randomUrl": "picsum.photos" }, """))
        assertTrue("$.background.randomUrl" in badUrl.paths(Severity.Error), badUrl.issues.toString())
        val fast = LayoutCodec.decode(docWithRoot(TILE, """ "background": { "type": "slideshow", "interval": 3, "images": [ { "src": "https://e.com/a.jpg" } ] }, """))
        assertTrue("$.background.interval" in fast.paths(Severity.Warning), fast.issues.toString())
        val badSrc = LayoutCodec.decode(docWithRoot(TILE, """ "background": { "type": "slideshow", "images": [ { "src": "a.jpg" } ] }, """))
        assertTrue("$.background.images[0].src" in badSrc.paths(Severity.Error), badSrc.issues.toString())
    }

    @Test
    fun `next_wallpaper warns without a slideshow`() {
        val without = LayoutCodec.decode(docWithRoot(shortcut))
        assertTrue("$.pages[0].root.action" in without.paths(Severity.Warning), without.issues.toString())
        val onPage = LayoutCodec.decode(
            """{ "schemaVersion": 3, "pages": [
                 { "id": "home", "background": { "type": "slideshow", "randomUrl": "https://picsum.photos/1920/1080" }, "root": $shortcut },
                 { "id": "settings_page", "root": $SETTINGS_LINK } ] }""",
        )
        assertEquals(emptyList(), onPage.issues.map { it.message })
    }

    @Test
    fun `videos are recognized by their extension`() {
        assertTrue(Wallpapers.isVideo("https://example.com/loop.MP4"))
        assertTrue(Wallpapers.isVideo("https://example.com/live/stream.m3u8?token=1"))
        assertTrue(Wallpapers.isVideo("asset:videos/fire.webm"))
        assertTrue(!Wallpapers.isVideo("https://example.com/a.gif"))
        assertTrue(!Wallpapers.isVideo("https://example.mp4/picture"))
    }

    @Test
    fun `the pool lists images, then folder files, then the random address, each with its look`() {
        val slideshow = SlideshowBackground(
            images = listOf(SlideshowImage("https://e.com/a.jpg"), SlideshowImage("https://e.com/b.mp4", dim = 0f, adjust = ImageAdjust(hue = 90))),
            folder = "Pictures/OwnTV",
            randomUrl = "https://picsum.photos/1920/1080",
            dim = 0.5f,
            blur = 4,
            adjust = ImageAdjust(saturation = 0.5f),
        )
        val pool = Wallpapers.pool(slideshow, listOf(FolderFile("content://media/1", video = false)))
        assertEquals(listOf("https://e.com/a.jpg", "https://e.com/b.mp4", "content://media/1", "https://picsum.photos/1920/1080"), pool.map { it.src })
        assertEquals(listOf(false, true, false, false), pool.map { it.video })
        assertEquals(listOf(0.5f, 0f, 0.5f, 0.5f), pool.map { it.dim })
        assertEquals(ImageAdjust(hue = 90), pool[1].adjust)
        assertEquals(ImageAdjust(saturation = 0.5f), pool[2].adjust)
        assertEquals(listOf(false, false, false, true), pool.map { it.fresh })
    }

    @Test
    fun `random addresses get a new URL every turn`() {
        val pool = Wallpapers.pool(SlideshowBackground(randomUrl = "https://picsum.photos/1920/1080"), emptyList())
        assertEquals("https://picsum.photos/1920/1080?owntv=3", Wallpapers.show(pool[0], 3).src)
        val withQuery = Wallpapers.pool(SlideshowBackground(randomUrl = "https://e.com/random?size=hd"), emptyList())
        assertEquals("https://e.com/random?size=hd&owntv=4", Wallpapers.show(withQuery[0], 4).src)
        val fixed = Wallpapers.pool(SlideshowBackground(images = listOf(SlideshowImage("https://e.com/a.jpg"))), emptyList())
        assertEquals("https://e.com/a.jpg", Wallpapers.show(fixed[0], 4).src)
    }

    @Test
    fun `sequential order wraps and shuffle never repeats the current wallpaper`() {
        assertNull(Wallpapers.nextIndex(0, null, SlideshowOrder.Shuffle))
        assertEquals(0, Wallpapers.nextIndex(3, null, SlideshowOrder.Sequential))
        assertEquals(2, Wallpapers.nextIndex(3, 1, SlideshowOrder.Sequential))
        assertEquals(0, Wallpapers.nextIndex(3, 2, SlideshowOrder.Sequential))
        assertEquals(0, Wallpapers.nextIndex(1, 0, SlideshowOrder.Shuffle))
        val random = Random(7)
        var current = 0
        val seen = mutableSetOf<Int>()
        repeat(200) {
            val next = Wallpapers.nextIndex(4, current, SlideshowOrder.Shuffle, random)!!
            assertNotEquals(current, next)
            seen += next
            current = next
        }
        assertEquals(setOf(0, 1, 2, 3), seen)
        // A folder that shrank: the old index is out of range and any wallpaper may come next.
        assertTrue(Wallpapers.nextIndex(2, 5, SlideshowOrder.Shuffle, random)!! in 0..1)
    }

    @Test
    fun `color adjustments build the expected matrices`() {
        assertNull(Wallpapers.colorMatrix(null))
        assertNull(Wallpapers.colorMatrix(ImageAdjust()))

        val brighter = Wallpapers.colorMatrix(ImageAdjust(brightness = 0.2f))!!
        assertEquals(51f, brighter[4], 0.01f)
        assertEquals(1f, brighter[0], 0.001f)

        val flat = Wallpapers.colorMatrix(ImageAdjust(contrast = 0f))!!
        assertEquals(0f, flat[0], 0.001f)
        assertEquals(127.5f, flat[4], 0.01f)

        // Black and white: every color channel reads the same mix of the input.
        val gray = Wallpapers.colorMatrix(ImageAdjust(saturation = 0f))!!
        for (row in 1..2) for (col in 0..2) assertEquals(gray[col], gray[row * 5 + col], 0.001f)

        // Hue and saturation keep grays gray: each row still sums to 1.
        val turned = Wallpapers.colorMatrix(ImageAdjust(hue = 120, saturation = 1.5f))!!
        for (row in 0..2) assertEquals(1f, turned[row * 5] + turned[row * 5 + 1] + turned[row * 5 + 2], 0.001f)
        // Turning by 120° moves pure red mostly into green.
        val red = Wallpapers.colorMatrix(ImageAdjust(hue = 120))!!
        assertTrue(red[5] > red[0] && red[5] > red[10], red.toList().toString())
        // Alpha is never touched.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), turned.slice(15..19))
    }

    @Test
    fun `matrices color single pixels like the drawn picture`() {
        val gray = Wallpapers.colorMatrix(ImageAdjust(saturation = 0f))!!
        val pixel = Wallpapers.applyMatrix(0xFF20A0E0.toInt(), gray)
        val (r, g, b) = listOf(16, 8, 0).map { (pixel shr it) and 0xFF }
        assertEquals(r, g)
        assertEquals(g, b)
        assertEquals(0xFF, pixel ushr 24)
        val brighter = Wallpapers.colorMatrix(ImageAdjust(brightness = 1f))!!
        assertEquals(0xFFFFFFFF.toInt(), Wallpapers.applyMatrix(0xFF808080.toInt(), brighter))
        assertEquals(0x80FFFFFF.toInt(), Wallpapers.applyMatrix(0x80000000.toInt(), brighter))
    }
}

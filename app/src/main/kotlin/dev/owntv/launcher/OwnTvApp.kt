package dev.owntv.launcher

import android.app.Application
import android.content.Context
import coil3.SingletonImageLoader
import dev.owntv.launcher.actions.ActionExecutor
import dev.owntv.launcher.data.apps.AppImageLoader
import dev.owntv.launcher.data.apps.AppListsRepository
import dev.owntv.launcher.data.apps.InstalledAppsRepository
import dev.owntv.launcher.data.apps.RecentAppsRepository
import dev.owntv.launcher.data.apps.WatchNextRepository
import dev.owntv.launcher.data.layout.BundledAssets
import dev.owntv.launcher.data.layout.LayoutImageLoader
import dev.owntv.launcher.data.layout.LayoutRepository
import dev.owntv.launcher.importexport.FileImporter
import dev.owntv.launcher.importexport.PasteSession
import dev.owntv.launcher.importexport.UrlImporter
import dev.owntv.launcher.metrics.PerformanceMonitor
import dev.owntv.launcher.update.Updater
import dev.owntv.layout.ai.AiPromptBuilder
import dev.owntv.layout.codec.DecodeResult
import dev.owntv.layout.codec.LayoutCodec
import kotlinx.coroutines.MainScope

class OwnTvApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Images linked from a layout are cached on the TV, see [LayoutImageLoader]. */
    override fun newImageLoader(context: coil3.PlatformContext) = LayoutImageLoader.create(this, container.perf.imageEvents)
}

/** Creates and holds the app's long-lived objects. */
class AppContainer(context: Context) {
    val scope = MainScope()
    val perf: PerformanceMonitor = PerformanceMonitor(context, scope, appImageCacheBytes = { images.cacheBytes() })
    val assets = BundledAssets(context)
    val apps = InstalledAppsRepository(context, scope)
    val recent = RecentAppsRepository(context, scope)
    val watchNext = WatchNextRepository(context, scope)
    val images: AppImageLoader = AppImageLoader(context, onLoaded = perf::appImageLoaded)
    val appLists = AppListsRepository(context, scope, assets)
    val layouts = LayoutRepository(context, scope, assets, keepLists = appLists::seed, onLoaded = perf::layoutDecoded)
    val actions = ActionExecutor(context, onLaunch = recent::recordLaunch)
    val urlImporter = UrlImporter()
    val files = FileImporter(context)
    val updater = Updater(context, scope, BuildConfig.UPDATE_URL, BuildConfig.RELEASES_URL)

    val paste = PasteSession(context, scope, object : PasteSession.Provider {
        override fun pastePage() = assets.pastePage()
        override fun layoutJson() = layouts.currentJson()
        override fun aiPrompt() = this@AppContainer.aiPrompt()
        override fun decode(text: String) = this@AppContainer.decode(text)
    })

    /** Decodes and validates a layout, warning about apps that are not installed. */
    fun decode(text: String): DecodeResult = LayoutCodec.decode(text, apps.installedPackagesOrNull())

    fun aiPrompt(): String = AiPromptBuilder.build(
        instructions = assets.aiInstructions(),
        spec = assets.layoutSpec(),
        apps = apps.apps.value.map { AiPromptBuilder.AppEntry(it.label, it.packageName, it.isGame, it.isTvApp) },
        currentLayoutJson = layouts.currentJson(),
        lists = appLists.lists.value.lists,
    )
}

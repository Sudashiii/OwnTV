package dev.owntv.layout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Doc("What happens when a focusable widget is clicked.")
sealed interface Action

@Serializable
@SerialName("launch_app")
@Doc("Opens an installed app.")
data class LaunchAppAction(
    @SerialName("package") @Doc("Package name, e.g. com.netflix.ninja.")
    val packageName: String,
) : Action

@Serializable
@SerialName("open_url")
@Doc("Opens a URL with whatever app handles it (deep links work too).")
data class OpenUrlAction(
    @Doc("The URL or deep link.")
    val url: String,
) : Action

@Serializable
@SerialName("intent")
@Doc("Starts an Android activity intent. Needs at least one of action, package or component.")
data class IntentAction(
    @Doc("Intent action, e.g. android.intent.action.VIEW.")
    val action: String? = null,
    @Doc("Data URI.")
    val data: String? = null,
    @SerialName("package") @Doc("Target package.")
    val packageName: String? = null,
    @Doc("Target component as \"package/.Activity\" or \"package/full.class.Name\".")
    val component: String? = null,
    @Doc("Intent categories.")
    val categories: List<String> = emptyList(),
    @Doc("String extras.")
    val extras: Map<String, String> = emptyMap(),
) : Action

@Serializable
@SerialName("go_to_page")
@Doc("Switches to another page of this layout.")
data class GoToPageAction(
    @Doc("Id of the target page.")
    val page: String,
) : Action

@Serializable
@SerialName("open_input")
@Doc("Switches the TV to one of its inputs, e.g. HDMI 1. Only TVs have inputs; streaming boxes show a message.")
data class OpenInputAction(
    @Doc("hdmi1 to hdmi4, or av (composite).")
    val input: TvInput,
) : Action

@Serializable
enum class TvInput {
    @SerialName("hdmi1") Hdmi1,
    @SerialName("hdmi2") Hdmi2,
    @SerialName("hdmi3") Hdmi3,
    @SerialName("hdmi4") Hdmi4,
    @SerialName("av") Av,
}

@Serializable
@SerialName("open_app_drawer")
@Doc("Opens the built-in list of all installed apps.")
data object OpenAppDrawerAction : Action

@Serializable
@SerialName("open_system_settings")
@Doc("Opens Android TV system settings.")
data class OpenSystemSettingsAction(
    @Doc("Which settings screen. Default main.")
    val screen: SettingsScreen = SettingsScreen.Main,
) : Action

@Serializable
@SerialName("next_wallpaper")
@Doc("Changes a slideshow background to its next wallpaper at once: the page's, else the layout's.")
data object NextWallpaperAction : Action

@Serializable
@SerialName("standby")
@Doc("Puts the TV box into standby; with HDMI-CEC the TV switches off too. Needs OwnTV's standby service, turned on once in the Android accessibility settings. Without it the screen goes black until a key is pressed.")
data object StandbyAction : Action

@Serializable
@SerialName("open_launcher_settings")
@Doc("Opens OwnTV's own settings (import, templates, history, Android settings). Every layout needs at least one visible shortcut with this action, or it is rejected.")
data object OpenLauncherSettingsAction : Action

@Serializable
enum class SettingsScreen {
    @SerialName("main") Main,
    @SerialName("network") Network,
    @SerialName("bluetooth") Bluetooth,
    @SerialName("display") Display,
    @SerialName("sound") Sound,
    @SerialName("apps") Apps,
    @SerialName("date_time") DateTime,
    @SerialName("accessibility") Accessibility,
}

@Serializable
@Doc("Which apps a row or grid shows.")
sealed interface AppSource

@Serializable
@SerialName("list")
@Doc("A named app list such as \"favorites\". The layout only names the list; the TV stores which apps are in it and in what order, and you add, remove and move apps with a long press. Lists with the same name share their apps, so switching layouts keeps them.")
data class ListSource(
    @Doc("List name, e.g. \"favorites\", \"games\" or \"kids\". Letters, digits, _ and -.")
    val list: String,
) : AppSource

@Serializable
@SerialName("all")
@Doc("All installed TV apps.")
data class AllAppsSource(
    @Doc("Order of the apps: custom (default: the order arranged on the TV, apps never moved follow alphabetically), alphabetical or recently_installed.")
    val sort: AppSort = AppSort.Custom,
    @Doc("Package names to leave out.")
    val exclude: List<String> = emptyList(),
    @Doc("Also show phone apps that have no TV launcher entry. Default false.")
    val includeNonTvApps: Boolean = false,
) : AppSource

@Serializable
@SerialName("recent")
@Doc("Recently used apps, most recent first. Launches from OwnTV always count; with OwnTV's remote service turned on in the Android accessibility settings, or usage access granted, launches from anywhere count.")
data class RecentSource(
    @Doc("Package names to leave out.")
    val exclude: List<String> = emptyList(),
) : AppSource

@Serializable
@SerialName("category")
@Doc("Installed TV apps of one category.")
data class CategorySource(
    @Doc("games = apps marked as games; apps = everything that is not a game; mobile = phone apps sideloaded onto the TV (no TV launcher entry).")
    val category: AppCategory,
    @Doc("Order of the apps. Default alphabetical.")
    val sort: AppSort = AppSort.Alphabetical,
    @Doc("Package names to leave out.")
    val exclude: List<String> = emptyList(),
) : AppSource

@Serializable
enum class AppSort {
    @SerialName("alphabetical") Alphabetical,
    @SerialName("recently_installed") RecentlyInstalled,
    @SerialName("custom") Custom,
}

@Serializable
enum class AppCategory {
    @SerialName("games") Games,
    @SerialName("apps") Apps,
    @SerialName("mobile") Mobile,
}

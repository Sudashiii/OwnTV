package dev.owntv.layout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Doc("OwnTV's quick settings panel, opened with the open_quick_settings action (usually from a remote button). It slides in from the right over whatever is on screen and uses the layout's theme colors.")
data class QuickSettings(
    @Doc("Title at the top of the panel. Default \"Quick settings\".")
    val title: String = "Quick settings",
    @Doc("The rows of the panel, top to bottom. Default: brightness, volume, sleep_timer, network, bluetooth, android_settings, owntv_settings.")
    val items: List<QuickSetting> = DEFAULT_ITEMS,
) {
    companion object {
        val DEFAULT_ITEMS: List<QuickSetting> = listOf(
            QuickSetting(QuickSettingType.Brightness),
            QuickSetting(QuickSettingType.Volume),
            QuickSetting(QuickSettingType.SleepTimer),
            QuickSetting(QuickSettingType.Network),
            QuickSetting(QuickSettingType.Bluetooth),
            QuickSetting(QuickSettingType.AndroidSettings),
            QuickSetting(QuickSettingType.OwnTvSettings),
        )
    }
}

@Serializable
@Doc("One row of the quick settings panel.")
data class QuickSetting(
    @Doc("What the row does. brightness: dims the picture (left/right). volume: media volume (left/right, OK mutes). sleep_timer: standby after 15 to 120 minutes (left/right). network: connection state, OK opens network settings. bluetooth: OK opens Bluetooth settings. standby: OK puts the TV into standby. android_settings and owntv_settings: OK opens them. action: OK runs \"action\".")
    val type: QuickSettingType,
    @Doc("Text of the row. Default: a name that fits the type; needed for type action.")
    val label: String? = null,
    @Doc("Icon name (see shortcut icons) or an emoji. Default: an icon that fits the type.")
    val icon: String? = null,
    @Doc("Only for type action: what OK runs. Any action works; the panel closes afterwards.")
    val action: Action? = null,
)

@Serializable
enum class QuickSettingType {
    @SerialName("brightness") Brightness,
    @SerialName("volume") Volume,
    @SerialName("sleep_timer") SleepTimer,
    @SerialName("network") Network,
    @SerialName("bluetooth") Bluetooth,
    @SerialName("standby") Standby,
    @SerialName("android_settings") AndroidSettings,
    @SerialName("owntv_settings") OwnTvSettings,
    @SerialName("action") Action,
}

/** The quick settings this layout shows: its own, else the defaults. */
val LayoutDocument.quickSettingsOrDefault: QuickSettings
    get() = quickSettings ?: QuickSettings()

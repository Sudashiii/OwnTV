package dev.owntv.layout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Doc("Makes a remote button run an action, also while another app is open. Needs OwnTV's remote service, turned on once in the Android accessibility settings (Settings > Remote buttons); without it nothing happens. Only the TV has it: the web preview ignores key bindings.")
data class KeyBinding(
    @Doc("The remote button. D-pad, OK, Back, Home and Power cannot be remapped.")
    val key: RemoteKey,
    @Doc("short (default) runs the action when the button is released; long runs it while the button is held (about half a second) and the button then does nothing else.")
    val press: KeyPress = KeyPress.Short,
    @Doc("What the button does. Same actions as a shortcut. Pages and OwnTV's own screens open in OwnTV, which comes to the front.")
    val action: Action,
)

@Serializable
enum class KeyPress {
    @SerialName("short") Short,
    @SerialName("long") Long,
}

/** Remote buttons a layout can bind. Not every remote has all of them, and some never reach apps: the TV's Remote buttons screen shows which keys arrive. */
@Serializable
enum class RemoteKey {
    @SerialName("menu") Menu,
    @SerialName("guide") Guide,
    @SerialName("info") Info,
    @SerialName("settings") Settings,
    @SerialName("captions") Captions,
    @SerialName("bookmark") Bookmark,
    @SerialName("tv") Tv,
    @SerialName("tv_input") TvInput,
    @SerialName("dvr") Dvr,
    @SerialName("channel_up") ChannelUp,
    @SerialName("channel_down") ChannelDown,
    @SerialName("last_channel") LastChannel,
    @SerialName("red") Red,
    @SerialName("green") Green,
    @SerialName("yellow") Yellow,
    @SerialName("blue") Blue,
}

package dev.owntv.layout.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Doc("Makes a remote button run an action, also while another app is open. Needs OwnTV's remote service, turned on once in the Android accessibility settings (Settings > Remote buttons); without it nothing happens. Only the TV has it: the web preview ignores key bindings.")
data class KeyBinding(
    @Doc("The remote button. Use custom with keyCode for another Android button. D-pad, OK, Back, Home and Power cannot be remapped.")
    val key: RemoteKey,
    @Doc("short (default) runs the action when the button is released; long runs it while the button is held (about half a second) and the button then does nothing else.")
    val press: KeyPress = KeyPress.Short,
    @Doc("What the button does. Same actions as a shortcut. Pages and OwnTV's own screens open in OwnTV, which comes to the front.")
    val action: Action,
    @Doc("Android key code for a custom button, shown when it is pressed in Settings > Remote buttons. Use only with key: custom.")
    @Range(1.0, 2147483647.0)
    val keyCode: Int? = null,
) {
    /** The same physical Android key has the same identity whether named or specified by code. */
    val androidKeyCode: Int? get() = if (key == RemoteKey.Custom) keyCode else key.androidKeyCode
}

@Serializable
enum class KeyPress {
    @SerialName("short") Short,
    @SerialName("long") Long,
}

/** Remote buttons a layout can bind. Not every remote has all of them, and some never reach apps: the TV's Remote buttons screen shows which keys arrive. */
@Serializable
enum class RemoteKey(val androidKeyCode: Int?) {
    @SerialName("menu") Menu(82),
    @SerialName("guide") Guide(172),
    @SerialName("info") Info(165),
    @SerialName("settings") Settings(176),
    @SerialName("captions") Captions(175),
    @SerialName("bookmark") Bookmark(174),
    @SerialName("tv") Tv(170),
    @SerialName("tv_input") TvInput(178),
    @SerialName("dvr") Dvr(173),
    @SerialName("channel_up") ChannelUp(166),
    @SerialName("channel_down") ChannelDown(167),
    @SerialName("last_channel") LastChannel(229),
    @SerialName("red") Red(183),
    @SerialName("green") Green(184),
    @SerialName("yellow") Yellow(185),
    @SerialName("blue") Blue(186),
    @SerialName("custom") Custom(null),
}

/** Android's navigation and system buttons remain available to operate the TV and this screen. */
val reservedRemoteKeyCodes: Set<Int> = setOf(3, 4, 19, 20, 21, 22, 23, 26, 66, 160, 268, 269, 270, 271)

fun isRemappableRemoteKeyCode(keyCode: Int): Boolean = keyCode > 0 && keyCode !in reservedRemoteKeyCodes

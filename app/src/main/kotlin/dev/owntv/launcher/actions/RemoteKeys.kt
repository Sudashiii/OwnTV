package dev.owntv.launcher.actions

import android.view.KeyEvent
import dev.owntv.layout.model.RemoteKey

/** The Android key code a layout's [RemoteKey] stands for. */
fun RemoteKey.keyCode(): Int = when (this) {
    RemoteKey.Menu -> KeyEvent.KEYCODE_MENU
    RemoteKey.Guide -> KeyEvent.KEYCODE_GUIDE
    RemoteKey.Info -> KeyEvent.KEYCODE_INFO
    RemoteKey.Settings -> KeyEvent.KEYCODE_SETTINGS
    RemoteKey.Captions -> KeyEvent.KEYCODE_CAPTIONS
    RemoteKey.Bookmark -> KeyEvent.KEYCODE_BOOKMARK
    RemoteKey.Tv -> KeyEvent.KEYCODE_TV
    RemoteKey.TvInput -> KeyEvent.KEYCODE_TV_INPUT
    RemoteKey.Dvr -> KeyEvent.KEYCODE_DVR
    RemoteKey.ChannelUp -> KeyEvent.KEYCODE_CHANNEL_UP
    RemoteKey.ChannelDown -> KeyEvent.KEYCODE_CHANNEL_DOWN
    RemoteKey.LastChannel -> KeyEvent.KEYCODE_LAST_CHANNEL
    RemoteKey.Red -> KeyEvent.KEYCODE_PROG_RED
    RemoteKey.Green -> KeyEvent.KEYCODE_PROG_GREEN
    RemoteKey.Yellow -> KeyEvent.KEYCODE_PROG_YELLOW
    RemoteKey.Blue -> KeyEvent.KEYCODE_PROG_BLUE
}

/** What a remote button is called in the layout (`red`, `channel_up`), or its Android name for keys layouts cannot bind. */
fun keyName(keyCode: Int): String {
    val key = RemoteKey.entries.firstOrNull { it.keyCode() == keyCode }
    return key?.let { it.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase() }
        ?: KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase()
}

/** Whether a layout can bind this key (some keys, like Back and the D-pad, never are). */
fun isRemappable(keyCode: Int): Boolean = RemoteKey.entries.any { it.keyCode() == keyCode }

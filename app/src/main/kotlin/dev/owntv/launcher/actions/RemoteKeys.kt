package dev.owntv.launcher.actions

import android.view.KeyEvent
import dev.owntv.layout.model.RemoteKey
import dev.owntv.layout.model.isRemappableRemoteKeyCode

/** The Android key code of a named remote button. Custom buttons supply their own code. */
fun RemoteKey.keyCode(): Int = requireNotNull(androidKeyCode)

fun namedRemoteKey(keyCode: Int): RemoteKey? = RemoteKey.entries.firstOrNull { it.androidKeyCode == keyCode }

/** The layout's name for known buttons, otherwise Android's name (or the numeric code). */
fun keyName(keyCode: Int): String = namedRemoteKey(keyCode)?.let {
    it.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
} ?: KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase()

fun isRemappable(keyCode: Int): Boolean = isRemappableRemoteKeyCode(keyCode)

package dev.owntv.launcher.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.owntv.launcher.ui.render.LayoutView
import dev.owntv.launcher.ui.render.LocalBackgroundPaused
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.model.screensaverPage
import kotlinx.coroutines.delay

/** What covers the home screen while the remote is idle. */
enum class IdleCover { None, Screensaver, Black }

/**
 * Remote input for the layout's screensaver. The activity hands it every key first: while the screensaver
 * (or the black standby fallback) is up, a key only ends it, and neither that key nor the rest of its press
 * reaches the screen.
 */
@Stable
class IdleTracker {
    @Volatile
    private var lastKeyAt = SystemClock.uptimeMillis()

    /** Key code of the press that ended the cover, until it is released. */
    private var swallowed: Int? = null

    var cover by mutableStateOf(IdleCover.None)
        private set

    val idleMillis: Long get() = SystemClock.uptimeMillis() - lastKeyAt

    fun start() {
        cover = IdleCover.Screensaver
    }

    /** Covers the screen in black, where standby is not possible. */
    fun blackout() {
        cover = IdleCover.Black
    }

    fun stop() {
        cover = IdleCover.None
    }

    /** Ends the cover and starts the idle time over, e.g. when OwnTV comes back to the front. */
    fun reset() {
        cover = IdleCover.None
        lastKeyAt = SystemClock.uptimeMillis()
    }

    /** True when [event] belongs to the cover and must not be dispatched. */
    fun onKey(event: KeyEvent): Boolean {
        lastKeyAt = SystemClock.uptimeMillis()
        val newPress = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (cover != IdleCover.None) {
            if (newPress) {
                cover = IdleCover.None
                swallowed = event.keyCode
            }
            return true
        }
        if (event.keyCode == swallowed) {
            // A new press of the same key means the release got lost; let it through.
            if (newPress) {
                swallowed = null
                return false
            }
            if (event.action == KeyEvent.ACTION_UP) swallowed = null
            return true
        }
        return false
    }
}

/**
 * Starts the layout's screensaver once the remote has been idle for its `after` seconds while [allowed],
 * and shows it, or the black standby fallback, over the home screen. After the screensaver's
 * `standby.after` seconds it calls [onStandby]. [content] is the home screen; its videos pause while it
 * is covered.
 */
@Composable
fun ScreensaverHost(doc: LayoutDocument, idle: IdleTracker, allowed: Boolean, onStandby: () -> Unit, content: @Composable () -> Unit) {
    val saver = doc.screensaver?.takeIf { doc.screensaverPage != null }
    LaunchedEffect(allowed, saver?.after, idle.cover) {
        if (!allowed) {
            idle.stop()
            return@LaunchedEffect
        }
        if (saver == null || idle.cover != IdleCover.None) return@LaunchedEffect
        val after = saver.after * 1000L
        while (true) {
            val left = after - idle.idleMillis
            if (left <= 0) break
            delay(left)
        }
        idle.start()
    }
    val shown = allowed && saver != null && idle.cover == IdleCover.Screensaver
    val black = allowed && idle.cover == IdleCover.Black
    val standbyAfter = saver?.standby?.after
    val standby by rememberUpdatedState(onStandby)
    LaunchedEffect(shown, standbyAfter) {
        if (!shown || standbyAfter == null) return@LaunchedEffect
        delay(standbyAfter * 1000L)
        standby()
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBackgroundPaused provides (LocalBackgroundPaused.current || shown || black)) {
            content()
        }
        AnimatedVisibility(shown, enter = fadeIn(tween(FADE_IN_MS)), exit = fadeOut(tween(FADE_OUT_MS))) {
            // The screensaver keeps its own videos and slideshow running.
            CompositionLocalProvider(LocalBackgroundPaused provides false) {
                LayoutView(doc, pageId = saver?.page, onSelectPage = {}, focusTrigger = 0)
            }
        }
        AnimatedVisibility(black, enter = fadeIn(tween(BLACK_FADE_MS)), exit = fadeOut(tween(FADE_OUT_MS))) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

private const val FADE_IN_MS = 1200
private const val FADE_OUT_MS = 250
private const val BLACK_FADE_MS = 2000

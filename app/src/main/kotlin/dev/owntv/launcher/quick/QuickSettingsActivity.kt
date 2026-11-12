package dev.owntv.launcher.quick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.MutableTransitionState
import dev.owntv.launcher.OwnTvApp

/**
 * OwnTV's quick settings: a translucent activity in a task of its own, so it opens over whatever app is in
 * front (the app keeps showing, and usually playing, behind it) without bringing OwnTV's home screen along.
 * The panel slides in from the right ([QuickSettingsPanel]); Back, the bound remote button again, or leaving
 * for another app closes it.
 */
class QuickSettingsActivity : ComponentActivity() {
    private val visible = MutableTransitionState(false).apply { targetState = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val container = (application as OwnTvApp).container
        setContent { QuickSettingsPanel(container, visible, onClose = ::close, onClosed = ::finish) }
    }

    /** Slides the panel out; the activity finishes once it is gone. */
    fun close() {
        visible.targetState = false
    }

    override fun onStop() {
        super.onStop()
        // An app or OwnTV opened from the panel covers it: it is done.
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var current: QuickSettingsActivity? = null

        /** Opens the panel, or closes it when it is already open (the bound button pressed again). */
        fun toggle(context: Context) {
            val open = current
            if (open != null && !open.isFinishing) {
                open.close()
                return
            }
            context.startActivity(Intent(context, QuickSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

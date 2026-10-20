package dev.owntv.launcher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.owntv.launcher.ui.Navigator
import dev.owntv.launcher.ui.OwnTvRoot
import dev.owntv.launcher.ui.Screen

class MainActivity : ComponentActivity() {
    private val nav = Navigator()
    private val container: AppContainer get() = (application as OwnTvApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        setContent { OwnTvRoot(container, nav) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        when {
            intent.component?.className == "$packageName.SettingsShortcut" -> {
                nav.home()
                nav.push(Screen.Settings)
            }
            // Pressing Home while OwnTV is in front returns to the first page.
            intent.hasCategory(Intent.CATEGORY_HOME) -> nav.home()
        }
    }

    override fun onResume() {
        super.onResume()
        container.apps.refresh()
        container.recent.refresh()
        container.updater.takeUpdateNotice()?.let(container.actions::toast)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && nav.current == Screen.Home) {
            nav.push(Screen.Settings)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

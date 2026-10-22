package dev.owntv.launcher.actions

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dev.owntv.launcher.OwnTvApp
import dev.owntv.launcher.data.apps.ForegroundFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OwnTV's remote service, a second accessibility service next to [StandbyService]. It sees which app is in
 * front, so the `recent` source fills for apps opened any way. Nothing it sees is stored or sent, apart from
 * the time an app came to the front. The user turns it on once under Settings > Accessibility.
 *
 * It is separate from the standby service on purpose: that one promises to read nothing, and enabling a
 * service that observes more asks the user for more.
 */
class RemoteService : AccessibilityService() {
    private var filter: ForegroundFilter? = null

    override fun onServiceConnected() {
        instance = this
        filter = ForegroundFilter(
            ownPackage = packageName,
            isInputMethod = { pkg -> inputMethodPackages().contains(pkg) },
            hasLauncherEntry = { pkg ->
                packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null
            },
        )
        running.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (instance === this) {
            instance = null
            running.value = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = filter?.accept(event.packageName) ?: return
        (applicationContext as OwnTvApp).container.recent.recordUse(pkg)
    }

    override fun onInterrupt() = Unit

    private fun inputMethodPackages(): Set<String> =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.mapTo(mutableSetOf()) { it.packageName }.orEmpty()

    companion object {
        @Volatile
        private var instance: RemoteService? = null
        private val running = MutableStateFlow(false)

        /** True while the user has the service turned on. */
        val enabled: StateFlow<Boolean> = running.asStateFlow()
    }
}

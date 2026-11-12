package dev.owntv.launcher.actions

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dev.owntv.launcher.MainActivity
import dev.owntv.launcher.AppContainer
import dev.owntv.launcher.OwnTvApp
import dev.owntv.launcher.data.apps.ForegroundFilter
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.StandbyAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * OwnTV's remote service, a second accessibility service next to [StandbyService]. It sees
 *  - which app is in front, so the `recent` source fills for apps opened any way, and
 *  - the remote buttons, so a layout's `keyBindings` also work while another app is open.
 * Only buttons a layout binds are taken over (see [KeyBindingMatcher]); every other key passes through.
 * Nothing it sees is stored or sent, apart from the time an app came to the front. The user turns it on
 * once under Settings > Accessibility.
 *
 * It is separate from the standby service on purpose: that one promises to read nothing, and enabling a
 * service that observes more asks the user for more.
 */
class RemoteService : AccessibilityService() {
    private val container: AppContainer get() = (applicationContext as OwnTvApp).container
    private val handler = Handler(Looper.getMainLooper())
    private var filter: ForegroundFilter? = null
    private var matcher = KeyBindingMatcher(emptyList())
    private var bindingsJob: Job? = null
    private val longPresses = mutableMapOf<Int, Runnable>()

    override fun onServiceConnected() {
        instance = this
        filter = ForegroundFilter(
            ownPackage = packageName,
            isInputMethod = { pkg -> inputMethodPackages().contains(pkg) },
            hasLauncherEntry = { pkg ->
                packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null
            },
        )
        bindingsJob = container.scope.launch {
            container.layouts.state.map { it.document.keyBindings }.distinctUntilChanged().collect { matcher = KeyBindingMatcher(it) }
        }
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
            bindingsJob?.cancel()
            cancelLongPresses()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = filter?.accept(event.packageName) ?: return
        container.recent.recordUse(pkg)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        KeyProbe.report(event)
        // The Remote buttons screen shows what each button sends, so the bindings stay out of its way.
        if (KeyProbe.listening) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (val result = matcher.onKey(event.keyCode, down, event.repeatCount, event.eventTime)) {
            KeyBindingMatcher.Result.PassThrough -> false
            KeyBindingMatcher.Result.Consume -> {
                if (!down) cancelLongPress(event.keyCode) else if (event.repeatCount == 0) armLongPress(event.keyCode)
                true
            }
            is KeyBindingMatcher.Result.Fire -> {
                cancelLongPress(event.keyCode)
                run(result.action)
                true
            }
        }
    }

    private fun armLongPress(keyCode: Int) {
        cancelLongPress(keyCode)
        val delay = matcher.longPressDelayMs(keyCode) ?: return
        val timeout = Runnable {
            longPresses.remove(keyCode)
            matcher.onLongPressTimeout(keyCode)?.let { run(it.action) }
        }
        longPresses[keyCode] = timeout
        handler.postDelayed(timeout, delay)
    }

    private fun cancelLongPress(keyCode: Int) {
        longPresses.remove(keyCode)?.let(handler::removeCallbacks)
    }

    private fun cancelLongPresses() {
        longPresses.values.forEach(handler::removeCallbacks)
        longPresses.clear()
    }

    /** Runs a bound action: apps, URLs and intents directly, OwnTV's own actions through its home screen. */
    private fun run(action: Action) {
        if (!action.runsInOwnTv()) {
            container.actions.execute(action)
            return
        }
        // Standby does not need OwnTV in front; without the standby service OwnTV shows its black cover instead.
        if (action == StandbyAction && StandbyService.sleep()) return
        container.remoteActions.trySend(action)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
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

package dev.owntv.launcher.actions

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OwnTV's standby service. No normal app may put the device to sleep, but an accessibility service may
 * "lock the screen", which sends the device to sleep; with HDMI-CEC the TV switches off with it. The user
 * turns the service on once under Settings > Accessibility. It gets no events and cannot read the screen.
 */
class StandbyService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: StandbyService? = null
        private val running = MutableStateFlow(false)

        /** True while the user has the service turned on. */
        val enabled: StateFlow<Boolean> = running.asStateFlow()

        /** Puts the device to sleep. False when the service is off or the system refused. */
        fun sleep(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
    }
}

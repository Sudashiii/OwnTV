package dev.owntv.launcher.actions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * What the TV's accessibility settings hold, and a way for OwnTV to switch single services on and off.
 * Switching needs WRITE_SECURE_SETTINGS, which only adb can grant (`pm grant`); then OwnTV turns its own
 * services on without the Accessibility screen, and can turn off a service that speaks (TalkBack) when the
 * TV reads things aloud. It only ever changes the one service asked for.
 */
class AccessibilitySettings(private val context: Context) {
    data class Service(val component: String, val label: String, val speaks: Boolean, val ownTv: Boolean)

    data class State(
        /** Services turned on, OwnTV's included, in the order the setting lists them. */
        val services: List<Service>,
        /** The TV behaves as if a screen reader runs (spoken feedback, explore by touch). */
        val screenReaderMode: Boolean,
        /** OwnTV may change the setting itself (WRITE_SECURE_SETTINGS granted). */
        val canChange: Boolean,
    ) {
        fun isOn(component: String): Boolean = services.any { it.component == ServiceList.normalize(component) }
    }

    private val manager = context.getSystemService(AccessibilityManager::class.java)

    fun read(): State {
        val enabled = ServiceList.parse(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        val installed = manager?.installedAccessibilityServiceList.orEmpty().associateBy { info ->
            info.resolveInfo?.serviceInfo?.let { ComponentName(it.packageName, it.name).flattenToString() } ?: ServiceList.normalize(info.id)
        }
        val services = enabled.map { component ->
            val info = installed[component]
            Service(
                component = component,
                label = info?.resolveInfo?.loadLabel(context.packageManager)?.toString() ?: component.substringAfterLast('.'),
                speaks = info != null && info.feedbackType and AccessibilityServiceInfo.FEEDBACK_SPOKEN != 0,
                ownTv = component.startsWith(context.packageName + "/"),
            )
        }
        return State(services, manager?.isTouchExplorationEnabled == true, canChange())
    }

    fun canChange(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Turns [component] on or off and leaves every other service as it is. False without the permission. */
    fun setEnabled(component: String, enabled: Boolean): Boolean {
        if (!canChange()) return false
        return runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val updated = ServiceList.with(current, component, enabled)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (updated.isEmpty()) 0 else 1)
        }.isSuccess
    }

    companion object {
        const val REMOTE = "dev.owntv.launcher/dev.owntv.launcher.actions.RemoteService"
        const val STANDBY = "dev.owntv.launcher/dev.owntv.launcher.actions.StandbyService"
    }
}

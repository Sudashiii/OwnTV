package dev.owntv.launcher.actions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.IntentAction
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.OpenSystemSettingsAction
import dev.owntv.layout.model.OpenUrlAction
import dev.owntv.layout.model.SettingsScreen

/** Runs the actions that leave the launcher (apps, URLs, intents, system settings). */
class ActionExecutor(private val context: Context) {

    /** Returns false for actions the UI handles itself (pages, drawer, launcher settings). */
    fun execute(action: Action): Boolean = when (action) {
        is LaunchAppAction -> launchApp(action.packageName)
        is OpenUrlAction -> start(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)), "Nothing on this TV can open ${action.url}")
        is IntentAction -> start(buildIntent(action), "Could not start the intent")
        is OpenSystemSettingsAction -> start(Intent(settingsAction(action.screen)), null) ||
            start(Intent(Settings.ACTION_SETTINGS), "Could not open settings")
        else -> false
    }

    fun launchApp(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            toast("$packageName is not installed")
            return false
        }
        return start(intent, "Could not open $packageName")
    }

    fun openAppInfo(packageName: String) {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")), "Could not open app info")
    }

    /** Opens the Play Store page of an app that is not installed. */
    fun openStorePage(packageName: String) {
        start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")), "$packageName is not installed")
    }

    fun openSystemSettings() {
        start(Intent(Settings.ACTION_SETTINGS), "Could not open settings")
    }

    private fun buildIntent(action: IntentAction): Intent = Intent().apply {
        action.action?.let { setAction(it) }
        action.data?.let { data = Uri.parse(it) }
        action.packageName?.let { setPackage(it) }
        action.component?.let { ComponentName.unflattenFromString(it)?.let(::setComponent) }
        action.categories.forEach { addCategory(it) }
        action.extras.forEach { (key, value) -> putExtra(key, value) }
    }

    private fun settingsAction(screen: SettingsScreen): String = when (screen) {
        SettingsScreen.Main -> Settings.ACTION_SETTINGS
        SettingsScreen.Network -> Settings.ACTION_WIFI_SETTINGS
        SettingsScreen.Bluetooth -> Settings.ACTION_BLUETOOTH_SETTINGS
        SettingsScreen.Display -> Settings.ACTION_DISPLAY_SETTINGS
        SettingsScreen.Sound -> Settings.ACTION_SOUND_SETTINGS
        SettingsScreen.Apps -> Settings.ACTION_APPLICATION_SETTINGS
        SettingsScreen.DateTime -> Settings.ACTION_DATE_SETTINGS
        SettingsScreen.Accessibility -> Settings.ACTION_ACCESSIBILITY_SETTINGS
    }

    private fun start(intent: Intent, failureMessage: String?): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        failureMessage?.let(::toast)
        false
    } catch (e: SecurityException) {
        failureMessage?.let(::toast)
        false
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

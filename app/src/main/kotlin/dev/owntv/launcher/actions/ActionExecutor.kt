package dev.owntv.launcher.actions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.owntv.layout.model.Action
import dev.owntv.layout.model.IntentAction
import dev.owntv.layout.model.LaunchAppAction
import dev.owntv.layout.model.OpenInputAction
import dev.owntv.layout.model.OpenSystemSettingsAction
import dev.owntv.layout.model.OpenUrlAction
import dev.owntv.layout.model.SettingsScreen
import dev.owntv.layout.model.TvInput

/** Runs the actions that leave the launcher (apps, URLs, intents, system settings). */
class ActionExecutor(
    private val context: Context,
    private val onLaunch: (packageName: String) -> Unit = {},
) {

    /** Returns false for actions the UI handles itself (pages, drawer, launcher settings). */
    fun execute(action: Action): Boolean = when (action) {
        is LaunchAppAction -> launchApp(action.packageName)
        is OpenUrlAction -> start(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)), "Nothing on this TV can open ${action.url}")
        is IntentAction -> start(buildIntent(action), "Could not start the intent")
        is OpenInputAction -> openInput(action.input)
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
        return start(intent, "Could not open $packageName").also { if (it) onLaunch(packageName) }
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

    /** Opens the Android accessibility settings, where the user turns on OwnTV's standby service. */
    fun openAccessibilitySettings() {
        if (!start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null)) openSystemSettings()
    }

    /** Switches to a TV input through the system's TV app, like the remote's input button. */
    private fun openInput(input: TvInput): Boolean {
        val name = input.displayName()
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val info = manager?.let { findInput(it.tvInputList, input) }
        if (info == null) {
            toast("This device has no $name input")
            return false
        }
        return start(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id)), "Could not switch to $name")
    }

    /**
     * Finds the input for [input] among the TV's own ports (devices behind HDMI-CEC have a parent and
     * are skipped). HDMI ports are matched by the number in their label, else by their order.
     */
    private fun findInput(inputs: List<TvInputInfo>, input: TvInput): TvInputInfo? {
        if (input == TvInput.Av) return inputs.firstOrNull { it.type == TvInputInfo.TYPE_COMPOSITE && it.parentId == null }
        val number = input.ordinal + 1
        val ports = inputs.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }.sortedBy { it.id }
        val numbered = Regex("(^|\\D)$number(\\D|$)")
        return ports.firstOrNull { numbered.containsMatchIn(it.loadLabel(context).toString()) } ?: ports.getOrNull(number - 1)
    }

    private fun TvInput.displayName(): String = if (this == TvInput.Av) "AV" else "HDMI ${ordinal + 1}"

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

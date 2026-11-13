package dev.owntv.launcher.adb

import dev.owntv.launcher.metrics.PerformanceMonitor

/**
 * One adb command OwnTV tells people about. [line] is what to type on a computer. `adb shell …` lines also run on
 * the TV itself through [SelfAdb], unless [onComputer]: their result only helps on a computer (it prints a file).
 */
data class AdbCommand(
    val title: String,
    val description: String,
    val line: String,
    val onComputer: Boolean = false,
) {
    /** What [SelfAdb] runs on this TV, or null when the command needs a computer. */
    val shellCommand: String? get() = if (onComputer) null else AdbProtocol.shellCommandOf(line)
}

data class AdbCommandGroup(val title: String, val commands: List<AdbCommand>)

/** Every adb command OwnTV shows, so the help texts and Settings > Developer > ADB commands say the same. */
object AdbCommands {
    private const val PACKAGE = "dev.owntv.launcher"
    private const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"

    val SetHome = AdbCommand(
        "Make OwnTV the Home app",
        "Home on the remote opens OwnTV instead of the TV's own launcher.",
        "adb shell cmd package set-home-activity $PACKAGE/.MainActivity",
    )
    val DisableGoogleTvLauncher = AdbCommand(
        "Turn off the Google TV launcher",
        "For TVs where Home still opens Google TV after making OwnTV the Home app. Nothing is deleted; the next command undoes it.",
        "adb shell pm disable-user --user 0 $GOOGLE_TV_LAUNCHER",
    )
    val EnableGoogleTvLauncher = AdbCommand(
        "Turn the Google TV launcher back on",
        "Undoes turning it off, for example before you uninstall OwnTV.",
        "adb shell pm enable $GOOGLE_TV_LAUNCHER",
    )
    val AllowRestrictedSettings = AdbCommand(
        "Allow restricted settings",
        "Android 13 and newer grey out the Accessibility switches of apps that were not installed from a store. This lifts that for OwnTV.",
        "adb shell appops set $PACKAGE ACCESS_RESTRICTED_SETTINGS allow",
    )
    val EnableRemoteServices = AdbCommand(
        "Turn on OwnTV remote and standby",
        "Turns on both of OwnTV's accessibility services without the Accessibility screen. Other accessibility services that were on are turned off.",
        "adb shell settings put secure enabled_accessibility_services $PACKAGE/.actions.RemoteService:$PACKAGE/.actions.StandbyService",
    )
    val UsageAccess = AdbCommand(
        "Count every app as recent",
        "Grants usage access, so rows with a \"recent\" source also include apps opened any other way (voice search, notifications).",
        "adb shell appops set $PACKAGE GET_USAGE_STATS allow",
    )
    val OpenAdbPort = AdbCommand(
        "Open adb on port ${SelfAdb.PORT}",
        "For TVs that only offer wireless debugging with a pairing code: lets ADB on this TV connect, until the TV restarts.",
        "adb tcpip ${SelfAdb.PORT}",
    )
    val ReadPerformanceReport = AdbCommand(
        "Read the performance report",
        "Prints the report of Settings > Developer > Performance metrics; save it there first.",
        PerformanceMonitor.REPORT_COMMAND,
        onComputer = true,
    )

    /** Copies a layout file into [folder] (the app's import folder, as adb sees it). */
    fun pushLayout(folder: String) = AdbCommand(
        "Copy a layout file to the TV",
        "Then open it from Settings > Layout > Import layout > Files.",
        "adb push my-layout.json $folder/",
    )

    /** All commands, grouped as Settings > Developer > ADB commands lists them. */
    fun groups(layoutFolder: String): List<AdbCommandGroup> = listOf(
        AdbCommandGroup("Home screen", listOf(SetHome, DisableGoogleTvLauncher, EnableGoogleTvLauncher)),
        AdbCommandGroup("Remote & standby", listOf(AllowRestrictedSettings, EnableRemoteServices)),
        AdbCommandGroup("Apps", listOf(UsageAccess)),
        AdbCommandGroup("From a computer", listOf(OpenAdbPort, pushLayout(layoutFolder), ReadPerformanceReport)),
    )
}

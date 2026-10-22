package dev.owntv.launcher.data.apps

/**
 * Decides which packages the remote service reports as "used" when its window changes: real apps the
 * user opened, not OwnTV itself, system dialogs, keyboards or packages without a launcher entry.
 * Pure; the Android lookups are passed in.
 */
class ForegroundFilter(
    private val ownPackage: String,
    private val isInputMethod: (String) -> Boolean,
    private val hasLauncherEntry: (String) -> Boolean,
    private val ignored: Set<String> = SYSTEM_PACKAGES,
) {
    private var last: String? = null

    /** The package to record, or null when this event does not count. */
    fun accept(packageName: CharSequence?): String? {
        val pkg = packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        if (pkg == ownPackage) {
            // Coming back from OwnTV, the same app opened again is a new use.
            last = null
            return null
        }
        if (pkg == last) return null
        if (pkg in ignored || isInputMethod(pkg) || !hasLauncherEntry(pkg)) return null
        last = pkg
        return pkg
    }

    companion object {
        val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.tv.assist",
            "com.google.android.katniss",
        )
    }
}

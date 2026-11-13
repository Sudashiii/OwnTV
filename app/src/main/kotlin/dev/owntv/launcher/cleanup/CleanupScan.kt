package dev.owntv.launcher.cleanup

/**
 * What the TV says about its packages, read in one `adb shell` run through SelfAdb. A null field is a
 * question the TV did not answer; the rules then fail closed, as cleanyourtv's do.
 */
data class DeviceState(
    val enabled: Set<String>,
    val disabled: Set<String>,
    /** The package that gets the Home button ("android" while Android asks which launcher to use). */
    val homeOwner: String?,
    /** Every package with a Home activity. */
    val homeApps: Set<String>?,
    /** Packages that open on `android.settings.SETTINGS`: without them Settings does not open. */
    val settingsHandlers: Set<String>?,
    /** Packages that serve a TV input (HDMI, tuner): off, the input can show No Signal for good. */
    val tvInputs: Set<String>?,
    /** The package of the keyboard in use. */
    val keyboard: String?,
    /** Packages that own a saved Wi-Fi network: Android forgets the network when its owner is turned off. */
    val wifiOwners: Set<String>?,
) {
    val ownTvIsHome: Boolean get() = homeOwner == CleanupRules.OWNTV
}

object CleanupScan {
    private const val MARK = "=="

    private val SECTIONS = listOf(
        "enabled" to "pm list packages -e --user 0",
        "disabled" to "pm list packages -d --user 0",
        "home" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
        "homes" to "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
        "settings" to "cmd package query-activities --brief -a android.settings.SETTINGS",
        "inputs" to "cmd package query-services --brief -a android.media.tv.TvInputService",
        "keyboard" to "settings get secure default_input_method",
        "wifi" to "dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u",
    )

    /** The shell script the scan runs: each command after an `==name` line. */
    val SCRIPT: String = SECTIONS.joinToString("; ") { (name, command) -> "echo $MARK$name; $command" }

    /** Just the enabled packages, to read the TV back after a change. */
    const val ENABLED = "pm list packages -e --user 0"

    private val COMPONENT = Regex("^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)/[A-Za-z0-9_.$]+$")
    private val NONE_FOUND = Regex("^No (activities|activity|services) found", RegexOption.IGNORE_CASE)
    private val FOUND = Regex("^\\d+ (activities|services) found", RegexOption.IGNORE_CASE)

    /** Splits the scan's output; null when it does not look like a scan at all. */
    fun parse(raw: String): DeviceState? {
        val sections = sections(raw)
        val enabled = sections["enabled"]?.let(::packages).orEmpty()
        if (enabled.isEmpty()) return null
        return DeviceState(
            enabled = enabled,
            disabled = sections["disabled"]?.let(::packages).orEmpty(),
            homeOwner = sections["home"]?.let(::components)?.lastOrNull(),
            homeApps = sections["homes"]?.let(::componentSet),
            settingsHandlers = sections["settings"]?.let(::componentSet),
            tvInputs = sections["inputs"]?.let(::componentSet),
            keyboard = sections["keyboard"]?.let(::components)?.firstOrNull(),
            wifiOwners = sections["wifi"]?.let(::wifiOwners),
        )
    }

    /** `package:name` lines, as `pm list packages` prints them. */
    fun packages(lines: List<String>): Set<String> =
        lines.mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it != line.trim() && CleanupRules.isValidPackage(it) } }.toSet()

    private fun sections(raw: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        raw.lineSequence().map { it.trimEnd('\r') }.forEach { line ->
            if (line.startsWith(MARK) && line.length > MARK.length && line.drop(MARK.length).all { it.isLetter() }) {
                current = result.getOrPut(line.drop(MARK.length)) { mutableListOf() }
            } else {
                current?.add(line)
            }
        }
        return result
    }

    /** The packages of `package/activity` lines, in order. */
    private fun components(lines: List<String>): List<String> =
        lines.mapNotNull { COMPONENT.matchEntire(it.trim())?.groupValues?.get(1) }

    /** The packages a query names, an empty set when it found none, null when it did not answer. */
    private fun componentSet(lines: List<String>): Set<String>? {
        val found = components(lines)
        val trimmed = lines.map { it.trim() }
        return when {
            found.isNotEmpty() -> found.toSet()
            trimmed.any { NONE_FOUND.containsMatchIn(it) || FOUND.containsMatchIn(it) } -> emptySet()
            else -> null
        }
    }

    /** `cname=package` lines; owners such as `android.uid.system:1000` are users, not packages. */
    private fun wifiOwners(lines: List<String>): Set<String>? {
        val owners = lines.mapNotNull { it.trim().removePrefix("cname=").takeIf { value -> value != it.trim() } }
        if (owners.isEmpty()) return null
        return owners.filter { CleanupRules.isValidPackage(it) }.toSet()
    }
}

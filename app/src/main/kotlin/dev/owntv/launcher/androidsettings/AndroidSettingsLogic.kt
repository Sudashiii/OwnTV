package dev.owntv.launcher.androidsettings

/** The tables of Android's `settings` command. */
enum class SettingsTable(val shellName: String) {
    System("system"),
    Secure("secure"),
    Global("global"),
}

/**
 * The shell lines OwnTV runs on the TV itself to change Android settings (see `SelfAdb`). Pure: only the
 * handful of commands built here ever run, and every value is checked so nothing but a plain key, number,
 * word or time zone id can reach the shell.
 */
object AndroidCommands {
    private val Safe = Regex("[A-Za-z0-9_.:/+-]{1,64}")

    private fun safe(text: String): String = text.also { require(Safe.matches(it)) { "Not a plain value: $it" } }

    /** `settings put <table> <key> <value>`. */
    fun put(table: SettingsTable, key: String, value: String): String =
        "settings put ${table.shellName} ${safe(key)} ${safe(value)}"

    fun wifi(on: Boolean): String = "svc wifi " + if (on) "enable" else "disable"

    fun bluetooth(on: Boolean): String = "svc bluetooth " + if (on) "enable" else "disable"

    /** Sets the time zone like the Date & time settings do. */
    fun timeZone(id: String): String = "cmd alarm set-timezone ${safe(id)}"

    /** Lets OwnTV write Android's system, secure and global settings itself from then on. */
    fun grantSecureSettings(packageName: String): String =
        "pm grant ${safe(packageName)} android.permission.WRITE_SECURE_SETTINGS"

    /** Lets OwnTV see which app is open, for rows with a "recent" source. */
    fun allowUsageAccess(packageName: String): String = "appops set ${safe(packageName)} GET_USAGE_STATS allow"

    fun grantBluetooth(packageName: String): String ="pm grant ${safe(packageName)} android.permission.BLUETOOTH_CONNECT"

    /** The commands that turn debugging on, in the order they run. */
    fun developerOptions(on: Boolean): String =
        put(SettingsTable.Global, "development_settings_enabled", if (on) "1" else "0")

    fun usbDebugging(on: Boolean): String = put(SettingsTable.Global, "adb_enabled", if (on) "1" else "0")
}

/** The steps of "Turn the screen off after", in milliseconds; [Never] is Android's largest value. */
object ScreenTimeout {
    const val Never = Int.MAX_VALUE
    private const val Minute = 60_000

    val Steps: List<Int> = listOf(15_000, 30_000, Minute, 2 * Minute, 5 * Minute, 10 * Minute, 15 * Minute, 30 * Minute, 60 * Minute, Never)

    /** "15 seconds", "30 minutes", "1 hour", "Never". */
    fun label(millis: Int): String = when {
        millis >= Never -> "Never"
        millis < Minute -> unit(millis / 1000, "second")
        millis < 60 * Minute -> unit(millis / Minute, "minute")
        else -> unit(millis / (60 * Minute), "hour")
    }

    /** The step [delta] places from the one closest to [current], kept inside the list. */
    fun step(current: Int, delta: Int): Int {
        val index = Steps.indices.minByOrNull { kotlin.math.abs(Steps[it].toLong() - current.toLong()) } ?: 0
        return Steps[(index + delta).coerceIn(0, Steps.lastIndex)]
    }

    private fun unit(n: Int, name: String) = "$n $name" + if (n == 1) "" else "s"
}

/** `encoded_surround_output`: which surround formats go to the TV or soundbar. */
enum class SurroundMode(val value: Int, val label: String) {
    Automatic(0, "Automatic"),
    Never(1, "Never"),
    Always(2, "Always"),
    ;

    companion object {
        fun of(value: Int): SurroundMode = entries.firstOrNull { it.value == value } ?: Automatic

        /** The mode [delta] places on, wrapping around, so a long press on a stepper cycles. */
        fun step(current: SurroundMode, delta: Int): SurroundMode = entries[Math.floorMod(current.ordinal + delta, entries.size)]
    }
}

/** Time zone ids worth listing: regions ("Europe/Berlin"), not the legacy aliases and fixed offsets. */
object TimeZones {
    private val Regions = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    fun listable(ids: Collection<String>): List<String> =
        ids.filter { it.substringBefore('/') in Regions && '/' in it }.sorted()

    /** "Europe/Berlin" -> "Berlin", "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
    fun city(id: String): String = id.substringAfterLast('/').replace('_', ' ')

    /** "Europe/Berlin" -> "Europe". */
    fun region(id: String): String = id.substringBefore('/')
}

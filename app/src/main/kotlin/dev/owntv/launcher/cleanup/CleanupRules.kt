package dev.owntv.launcher.cleanup

/**
 * Which packages Clean up this TV may turn off, and what turning one off costs. The rules come from
 * cleanyourtv's `tools/disable.sh`, where each of them was learned on a real TV.
 */
object CleanupRules {
    const val OWNTV = "dev.owntv.launcher"
    const val SETUP_WIZARD = "com.google.android.tungsten.setupwraith"

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * Core Android and TV middleware: with one of these off, a TV can stop booting (cleanyourtv #884, #1578).
     * Keep in step with `CORE` in `scripts/cleanyourtv_catalog.py`.
     */
    private val CORE = Regex(
        "^(android|android\\.ext\\.(services|shared)|com\\.android\\.sdksandbox|com\\.android\\.location\\.fused" +
            "|com\\.android\\.systemui|com\\.android\\.shell|com\\.android\\.phone|com\\.android\\.settings|com\\.android\\.tv\\.settings" +
            "|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)" +
            "|com\\.google\\.android\\.overlay\\.modules.*|com\\.android\\.providers\\..*|com\\.cltv\\.mal" +
            "|com\\.mediatek\\.(tis|tv\\.service|tv\\.agent))$",
    )
    private val CORE_OK = Regex("^com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)$")

    /** Never offered either: OwnTV itself, the Play Store and Play services (updates, sign-in, most apps). */
    private val NEVER = setOf(OWNTV, "com.android.vending", "com.google.android.gms", "com.google.android.gsf")

    /** Stock launchers, for when the TV cannot say which apps answer Home. */
    val KNOWN_LAUNCHERS = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome",
    )

    /** What is lost with these packages off. They are never selected for you. */
    private val WARNINGS = mapOf(
        "com.google.android.katniss" to "Google Assistant: the remote's mic button and voice search stop working.",
        "com.google.android.apps.mediashell" to "Chromecast built-in: you can no longer cast to this TV.",
        SETUP_WIZARD to "Setup wizard: on Google TV it holds the Cast credentials, so casting stops.",
        "com.google.android.tv.remote.service" to "The Google TV phone remote stops working.",
        "com.google.android.marvin.talkback" to "TalkBack screen reader.",
        "com.google.android.tts" to "Text to speech, used by screen readers and some voice features.",
        "com.google.android.tv.dfuservice" to "Remote firmware updates.",
        "com.google.android.apps.tv.launcherx" to "The Google TV home screen. Home stays with OwnTV.",
        "com.google.android.tvlauncher" to "The Android TV home screen. Home stays with OwnTV.",
        "com.google.android.leanbacklauncher" to "The Android TV home screen. Home stays with OwnTV.",
    )

    /** Shown on every package the catalog does not know. */
    const val OTHER_WARNING = "No tested TV list turns this off. Only turn it off if you know what it does."

    /** Only names that are plain package names go into a shell line. */
    fun isValidPackage(name: String): Boolean = PACKAGE.matches(name)

    /** Packages Clean up this TV never lists. */
    fun isProtected(name: String): Boolean =
        !isValidPackage(name) || name in NEVER || (CORE.matches(name) && !CORE_OK.matches(name))

    /**
     * Resource overlays restyle other packages; turning one off can break the app it belongs to. They are
     * left out of "Other packages" (a tested list may still turn one off).
     */
    fun isOverlay(name: String): Boolean = ".overlay" in name || RRO.containsMatchIn(name.substringAfterLast('.'))

    private val RRO = Regex("(^|_)rro(_|$)")

    fun warningFor(name: String): String? = WARNINGS[name]
}

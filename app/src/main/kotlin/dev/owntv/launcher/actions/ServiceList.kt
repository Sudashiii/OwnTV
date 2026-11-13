package dev.owntv.launcher.actions

/**
 * Edits the `enabled_accessibility_services` setting: a `:`-separated list of components such as
 * `pkg/.Service` or `pkg/pkg.Service`. Pure, so turning one service on or off never touches the others
 * (the old adb command replaced the whole list).
 */
object ServiceList {
    /** The components in [raw], written out in full (`pkg/pkg.Service`), without blanks and duplicates. */
    fun parse(raw: String?): List<String> =
        raw.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.map(::normalize).distinct()

    /** `pkg/.Service` as `pkg/pkg.Service`; anything without a `/` stays as it is. */
    fun normalize(component: String): String {
        val slash = component.indexOf('/')
        if (slash <= 0) return component
        val pkg = component.substring(0, slash)
        val cls = component.substring(slash + 1)
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else component
    }

    /** [raw] with [component] turned on or off; every other service stays as it was. */
    fun with(raw: String?, component: String, enabled: Boolean): String {
        val target = normalize(component)
        val others = parse(raw).filter { it != target }
        return (if (enabled) others + target else others).joinToString(":")
    }

    fun contains(raw: String?, component: String): Boolean = normalize(component) in parse(raw)
}

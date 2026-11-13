package dev.owntv.launcher.cleanup

import dev.owntv.launcher.cleanup.CleanupCatalog.PackageInfo
import dev.owntv.launcher.cleanup.CleanupCatalog.TvList

/** One package Clean up this TV can turn off. [blocked] says why it cannot right now. */
data class CleanupItem(
    val packageName: String,
    val info: PackageInfo?,
    val warning: String?,
    val blocked: String?,
) {
    /** Selected without asking: in the TV's list, nothing lost and nothing in the way. */
    val suggested: Boolean get() = warning == null && blocked == null
}

/** A package that is off now; [byOwnTv] when Clean up this TV turned it off. */
data class TurnedOff(val packageName: String, val byOwnTv: Boolean, val info: PackageInfo?)

data class CleanupPlan(
    /** In the chosen tested list for this TV. */
    val suggested: List<CleanupItem>,
    /** In other TVs' lists, most often turned off first. */
    val common: List<CleanupItem>,
    /** On this TV and in no list. */
    val other: List<CleanupItem>,
    val turnedOff: List<TurnedOff>,
    /** Checks the TV did not answer, said once at the top instead of on every row. */
    val unchecked: List<String>,
)

/** The best tested list for this TV, if one names it, and the lists to choose from. */
data class ListMatch(val best: TvList?, val choices: List<TvList>)

object CleanupPlanner {
    fun build(catalog: CleanupCatalog, state: DeviceState, list: TvList?, record: Set<String>): CleanupPlan {
        val candidates = state.enabled.filterNot(CleanupRules::isProtected).toSet()
        fun item(name: String, other: Boolean = false) = CleanupItem(
            packageName = name,
            info = catalog.packages[name],
            warning = CleanupRules.warningFor(name) ?: if (other) CleanupRules.OTHER_WARNING else null,
            blocked = blockReason(name, state, strict = other),
        )
        val inList = list?.off.orEmpty().filter { it in candidates }.toSet()
        val suggested = inList.sorted().map { item(it) }
        val common = catalog.packages.keys
            .filter { it in candidates && it !in inList }
            .sortedWith(compareByDescending<String> { catalog.packages.getValue(it).off }.thenBy { it })
            .map { item(it) }
        val other = candidates
            .filter { it !in inList && it !in catalog.packages && !CleanupRules.isOverlay(it) }
            .sorted()
            .map { item(it, other = true) }
            .filter { it.blocked == null }
        val turnedOff = state.disabled
            .filter { CleanupRules.isValidPackage(it) }
            .sortedWith(compareByDescending<String> { it in record }.thenBy { it })
            .map { TurnedOff(it, it in record, catalog.packages[it]) }
        return CleanupPlan(suggested, common, other, turnedOff, unchecked(state))
    }

    /** Why [name] has to stay on, or null. [strict] for packages no tested list knows: unanswered checks block them too. */
    fun blockReason(name: String, state: DeviceState, strict: Boolean = false): String? {
        val home = state.homeApps?.contains(name) ?: (name in CleanupRules.KNOWN_LAUNCHERS)
        return when {
            name == state.homeOwner && !state.ownTvIsHome ->
                "This is your Home screen. Make OwnTV the Home app first (Settings > General > Make OwnTV your Home screen)."
            home && !state.ownTvIsHome ->
                "It can take over the Home button. Make OwnTV the Home app first (Settings > General > Make OwnTV your Home screen)."
            state.wifiOwners?.contains(name) == true ->
                "It owns your saved Wi-Fi network, which the TV would forget. Forget the network in Settings > Network & Internet, join it again, then scan again."
            name == CleanupRules.SETUP_WIZARD && state.wifiOwners == null ->
                "Could not check whether it owns your saved Wi-Fi network; turned off, the TV could forget the network."
            state.settingsHandlers?.contains(name) == true -> "It opens the TV's Settings."
            state.tvInputs?.contains(name) == true -> "It runs a TV input (HDMI, antenna); turned off, the input can show No Signal."
            name == state.keyboard -> "It is the keyboard in use."
            strict && (state.settingsHandlers == null || state.tvInputs == null || state.keyboard == null) ->
                "Could not check what it does on this TV."
            else -> null
        }
    }

    private fun unchecked(state: DeviceState): List<String> = buildList {
        if (state.homeOwner == null) add("which app gets the Home button")
        if (state.settingsHandlers == null) add("which app opens Settings")
        if (state.tvInputs == null) add("which apps run the TV inputs")
        if (state.keyboard == null) add("which keyboard is in use")
        if (state.wifiOwners == null) add("which app owns the saved Wi-Fi network")
    }

    /**
     * Picks the tested list made on this TV: the device codename (sabrina, kirkwood) or the model named in the
     * list's title or header wins; the brand alone only narrows the choices.
     */
    fun matchLists(catalog: CleanupCatalog, manufacturer: String, model: String, device: String, product: String): ListMatch {
        fun words(text: String) = text.lowercase().trim().takeIf { it.length >= 3 }
        val strong = listOfNotNull(words(device), words(product)).distinct()
        val modelText = words(model)
        fun score(list: TvList): Int {
            var score = 0
            if (strong.any { containsWord(list.match, it) }) score += 10
            if (modelText != null && containsWord(list.match, modelText)) score += 8
            if (list.brand.equals(manufacturer.trim(), ignoreCase = true)) score += 1
            return score
        }
        val scored = catalog.lists.map { it to score(it) }
        val best = scored
            .filter { it.second >= 8 }
            .sortedWith(compareByDescending<Pair<TvList, Int>> { it.second }.thenBy { it.first.variant != null }.thenBy { it.first.id })
            .firstOrNull()?.first
        val brand = catalog.lists.filter { it.brand.equals(manufacturer.trim(), ignoreCase = true) }
        val choices = (if (brand.isNotEmpty()) brand else catalog.lists).sortedBy { it.title.lowercase() }
        return ListMatch(best, choices)
    }

    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + word.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = at + 1
        }
    }

    /** `pm disable-user` lines, a few packages per `adb shell` run. Only plain package names get in. */
    fun disableCommands(packages: Collection<String>): List<String> = batches(packages) { "pm disable-user --user 0 $it" }

    /** `pm enable` lines, the undo of [disableCommands]. */
    fun enableCommands(packages: Collection<String>): List<String> = batches(packages) { "pm enable --user 0 $it" }

    private fun batches(packages: Collection<String>, line: (String) -> String): List<String> {
        require(packages.all(CleanupRules::isValidPackage)) { "not a package name: ${packages.filterNot(CleanupRules::isValidPackage)}" }
        return packages.distinct().chunked(BATCH).map { chunk -> chunk.joinToString("; ", transform = line) }
    }

    /** Which of [requested] the TV still lists as enabled after a change, from [enabledAfter]. */
    fun stillEnabled(requested: Collection<String>, enabledAfter: Set<String>): List<String> = requested.filter { it in enabledAfter }

    /** A line of the batch output about [name], to say why the TV refused it. */
    fun errorFor(name: String, output: String): String? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { line -> name in line && ("Exception" in line || "Error" in line || "cannot" in line || "Unknown" in line) }
        ?.take(200)

    private const val BATCH = 10
}

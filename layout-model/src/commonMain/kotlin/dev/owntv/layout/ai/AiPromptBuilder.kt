package dev.owntv.layout.ai

/** Builds the text a user pastes into an AI chat to get a layout back. */
object AiPromptBuilder {
    data class AppEntry(
        val label: String,
        val packageName: String,
        val isGame: Boolean,
        val isTvApp: Boolean,
    )

    fun build(
        instructions: String,
        spec: String,
        apps: List<AppEntry>,
        currentLayoutJson: String?,
        lists: Map<String, List<String>> = emptyMap(),
    ): String = buildString {
        appendLine(instructions.trim())
        appendLine()
        appendLine("## Apps installed on this TV")
        appendLine()
        if (apps.isEmpty()) {
            appendLine("(No apps were reported.)")
        } else {
            appendLine("| Name | package | game | TV app |")
            appendLine("|---|---|---|---|")
            apps.sortedBy { it.label.lowercase() }.forEach { app ->
                val name = app.label.replace("|", "/")
                appendLine("| $name | ${app.packageName} | ${if (app.isGame) "yes" else "no"} | ${if (app.isTvApp) "yes" else "no"} |")
            }
        }
        appendLine()
        appendLine("## App lists on this TV")
        appendLine()
        appendLine("Rows, grids and folders show a list with {\"type\": \"list\", \"list\": \"<name>\"}. The TV fills the lists; the layout never names their apps.")
        if (lists.isEmpty()) {
            appendLine("(No lists yet. Any name works, e.g. favorites, games or kids; the user fills new lists on the TV.)")
        } else {
            lists.forEach { (name, packages) -> appendLine("- $name: ${packages.size} app(s)") }
        }
        if (currentLayoutJson != null) {
            appendLine()
            appendLine("## Current layout")
            appendLine()
            appendLine("```json")
            appendLine(currentLayoutJson.trim())
            appendLine("```")
        }
        appendLine()
        appendLine(spec.trim())
        appendLine()
        appendLine("## My request")
        appendLine()
    }
}

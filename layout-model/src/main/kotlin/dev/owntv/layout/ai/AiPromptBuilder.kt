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

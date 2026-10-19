package dev.owntv.layout.validation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Severity {
    @SerialName("error") Error,
    @SerialName("warning") Warning,
    @SerialName("info") Info,
}

/** One problem in a layout. [path] points at the JSON location, e.g. `$.pages[0].root.children[2]`. */
@Serializable
data class ValidationIssue(
    val severity: Severity,
    val path: String,
    val message: String,
) {
    override fun toString(): String = "${severity.name.uppercase()} at $path: $message"
}

val List<ValidationIssue>.errors get() = filter { it.severity == Severity.Error }
val List<ValidationIssue>.warnings get() = filter { it.severity == Severity.Warning }

/** Text to paste back into an AI chat so it can fix the layout. */
fun List<ValidationIssue>.toAiFeedback(): String {
    val relevant = filter { it.severity != Severity.Info }
    return buildString { appendAiFeedback(relevant) }
}

private fun StringBuilder.appendAiFeedback(relevant: List<ValidationIssue>) {
    if (relevant.isEmpty()) {
        append("OwnTV accepted the layout without problems.")
        return
    }
    appendLine("OwnTV found these problems in the layout. Fix all of them and reply with the complete corrected JSON only:")
    relevant.forEach { appendLine("- $it") }
}

internal class IssueCollector {
    val issues = mutableListOf<ValidationIssue>()
    fun error(path: String, message: String) { issues += ValidationIssue(Severity.Error, path, message) }
    fun warning(path: String, message: String) { issues += ValidationIssue(Severity.Warning, path, message) }
    fun info(path: String, message: String) { issues += ValidationIssue(Severity.Info, path, message) }
}

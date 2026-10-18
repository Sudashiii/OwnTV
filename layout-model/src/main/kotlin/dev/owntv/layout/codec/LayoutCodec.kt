package dev.owntv.layout.codec

import dev.owntv.layout.model.CURRENT_SCHEMA_VERSION
import dev.owntv.layout.model.LayoutDocument
import dev.owntv.layout.validation.IssueCollector
import dev.owntv.layout.validation.LayoutValidator
import dev.owntv.layout.validation.Severity
import dev.owntv.layout.validation.StructureValidator
import dev.owntv.layout.validation.ValidationIssue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class DecodeResult(
    val document: LayoutDocument?,
    val issues: List<ValidationIssue>,
) {
    val ok: Boolean get() = document != null
}

/** Reads and writes layout JSON. Decoding never throws; problems come back as [ValidationIssue]s. */
object LayoutCodec {
    const val MAX_LENGTH = 1_000_000

    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = true
        prettyPrintIndent = "  "
        allowTrailingComma = true
        allowComments = true
    }

    private val migrator = Migrator(CURRENT_SCHEMA_VERSION, LayoutMigrations.steps)

    fun encode(doc: LayoutDocument): String = json.encodeToString(LayoutDocument.serializer(), doc) + "\n"

    /**
     * Parses [text] (which may be wrapped in a Markdown code fence, as AI chats do), migrates it
     * to the current version, checks its structure and meaning, and decodes it.
     */
    fun decode(text: String, installedPackages: Set<String>? = null): DecodeResult {
        val out = IssueCollector()
        if (text.length > MAX_LENGTH) {
            out.error("$", "The layout is larger than ${MAX_LENGTH / 1000} KB.")
            return DecodeResult(null, out.issues)
        }
        val body = extractJson(text, out)
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            out.error("$", "Not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            return DecodeResult(null, out.issues)
        }
        val obj = element as? JsonObject ?: run {
            out.error("$", "The layout must be a JSON object {...}.")
            return DecodeResult(null, out.issues)
        }
        val migrated = migrator.migrate(obj, out) ?: return DecodeResult(null, out.issues)

        StructureValidator(out).checkDocument(LayoutDocument.serializer().descriptor, migrated)
        if (out.issues.any { it.severity == Severity.Error }) return DecodeResult(null, out.issues)

        val doc = try {
            json.decodeFromJsonElement(LayoutDocument.serializer(), migrated)
        } catch (e: SerializationException) {
            out.error("$", "Could not read the layout: ${e.message}")
            return DecodeResult(null, out.issues)
        } catch (e: IllegalArgumentException) {
            out.error("$", "Could not read the layout: ${e.message}")
            return DecodeResult(null, out.issues)
        }
        val issues = out.issues + LayoutValidator.validate(doc, installedPackages)
        val hasErrors = issues.any { it.severity == Severity.Error }
        return DecodeResult(if (hasErrors) null else doc, issues)
    }

    /** Strips a surrounding ``` fence or chat text so pasting a whole AI answer works. */
    internal fun extractJson(text: String, out: IssueCollector): String {
        val trimmed = text.trim()
        val fence = Regex("```[a-zA-Z0-9]*\\s*\\n([\\s\\S]*?)\\n\\s*```").find(trimmed)
        if (fence != null) {
            if (fence.range.first > 0 || fence.range.last < trimmed.lastIndex) {
                out.info("$", "Used the JSON inside the code block and ignored the text around it.")
            }
            return fence.groupValues[1]
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start > 0 && end > start) {
            out.info("$", "Ignored text before the first '{'.")
            return trimmed.substring(start, end + 1)
        }
        return trimmed
    }
}

/** Upgrades older layouts step by step. [steps] maps a version to the function that lifts it by one. */
class Migrator(
    private val current: Int,
    private val steps: Map<Int, (JsonObject) -> JsonObject>,
) {
    internal fun migrate(obj: JsonObject, out: IssueCollector): JsonObject? {
        val raw = obj["schemaVersion"]
        val version = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (raw == null) {
            out.warning("$.schemaVersion", "Missing schemaVersion; assuming $current.")
            return JsonObject(obj + ("schemaVersion" to JsonPrimitive(current)))
        }
        if (version == null || version < 1) {
            out.error("$.schemaVersion", "schemaVersion must be a whole number from 1 to $current.")
            return null
        }
        if (version > current) {
            out.error("$.schemaVersion", "This layout needs a newer OwnTV (schemaVersion $version; this app supports up to $current).")
            return null
        }
        var result = obj
        for (from in version until current) {
            val step = steps[from] ?: run {
                out.error("$.schemaVersion", "No migration from version $from.")
                return null
            }
            result = JsonObject(step(result) + ("schemaVersion" to JsonPrimitive(from + 1)))
        }
        if (version < current) out.info("$.schemaVersion", "Upgraded the layout from version $version to $current.")
        return result
    }
}

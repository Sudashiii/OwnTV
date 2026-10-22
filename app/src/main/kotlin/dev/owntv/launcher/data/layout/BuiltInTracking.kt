package dev.owntv.launcher.data.layout

import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.model.LayoutDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

enum class BuiltInKind(val label: String) {
    Look("look"),
    Template("template"),
}

/** A look or template packed into the APK (`BundledAssets`). */
data class BuiltInRef(val kind: BuiltInKind, val file: String)

/**
 * Which built-in layout the stored layout was taken from. [fileHash] is the hash of `current.json` right after
 * it was applied: while the file still has that hash the user has not changed anything. [dismissedHash] is the
 * built-in version (see [BuiltInTracking.documentHash]) the user answered "Not now" to.
 */
data class BuiltInOrigin(val ref: BuiltInRef, val fileHash: String, val dismissedHash: String? = null)

/** A newer version of the built-in layout the user is on, ready to be applied. */
data class BuiltInUpdate(
    val ref: BuiltInRef,
    val name: String,
    val document: LayoutDocument,
    /** [BuiltInTracking.documentHash] of [document]. */
    val hash: String,
    /** The user chose "Not now" for this version: no prompt, but Settings still offers it. */
    val dismissed: Boolean,
)

/**
 * Pure rules for "the built-in layout you use changed in a new OwnTV version". A layout counts as unmodified
 * while its stored file is byte-for-byte what was applied; the editor, an import or a revert all write a different
 * file (or none that is linked to a built-in layout), so those layouts are never offered a replacement.
 */
object BuiltInTracking {
    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Hash of a layout's canonical JSON, so an old file (migrated when read) and a new asset compare by content. */
    fun documentHash(doc: LayoutDocument): String = hash(LayoutCodec.encode(doc))

    /**
     * The update to offer for the stored layout, or null when the layout was changed by the user, has no
     * built-in source, or is already the same as the built-in version in this APK ([bundled]; null when this
     * APK no longer ships it).
     */
    fun update(origin: BuiltInOrigin?, currentFileHash: String?, current: LayoutDocument, bundled: LayoutDocument?): BuiltInUpdate? {
        if (origin == null || bundled == null || currentFileHash != origin.fileHash) return null
        val hash = documentHash(bundled)
        if (hash == documentHash(current)) return null
        return BuiltInUpdate(origin.ref, bundled.name, bundled, hash, dismissed = origin.dismissedHash == hash)
    }

    /**
     * For layouts stored before this was tracked: the built-in layout that [current] is identical to, if any.
     * Looks win over templates when both match.
     */
    fun adopt(current: LayoutDocument, candidates: List<Pair<BuiltInRef, LayoutDocument>>, currentFileHash: String): BuiltInOrigin? {
        val hash = documentHash(current)
        val match = candidates.sortedBy { it.first.kind }.firstOrNull { documentHash(it.second) == hash } ?: return null
        return BuiltInOrigin(match.first, currentFileHash)
    }
}

/** The small file next to `current.json` that remembers [BuiltInOrigin]; `{}` means "not from a built-in layout". */
object BuiltInOriginFile {
    fun encode(origin: BuiltInOrigin?): String {
        val json: JsonObject = if (origin == null) {
            JsonObject(emptyMap())
        } else {
            buildJsonObject {
                put("kind", JsonPrimitive(origin.ref.kind.name.lowercase()))
                put("file", JsonPrimitive(origin.ref.file))
                put("fileHash", JsonPrimitive(origin.fileHash))
                origin.dismissedHash?.let { put("dismissedHash", JsonPrimitive(it)) }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), json)
    }

    /** Null for `{}` and for anything unreadable: a damaged file just means no update is offered. */
    fun decode(text: String): BuiltInOrigin? = runCatching {
        val obj = Json.parseToJsonElement(text).jsonObject
        fun string(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
        val kind = BuiltInKind.entries.firstOrNull { it.name.equals(string("kind"), ignoreCase = true) } ?: return null
        BuiltInOrigin(BuiltInRef(kind, string("file") ?: return null), string("fileHash") ?: return null, string("dismissedHash"))
    }.getOrNull()
}

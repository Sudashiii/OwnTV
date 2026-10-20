package dev.owntv.layout

import dev.owntv.layout.codec.DecodeResult
import dev.owntv.layout.validation.Severity
import java.io.File
import kotlin.test.fail

val rootDir: File = File(System.getProperty("owntv.rootDir") ?: "..")
val examplesDir: File = File(rootDir, "examples")

fun DecodeResult.requireDocument() = document ?: fail("Expected a document, got:\n${issues.joinToString("\n")}")

fun DecodeResult.paths(severity: Severity) = issues.filter { it.severity == severity }.map { it.path }

/** Every layout needs a visible link to OwnTV's settings. */
const val SETTINGS_LINK = """{ "type": "shortcut", "id": "settings_link", "label": "Settings", "icon": "settings", "action": { "type": "open_launcher_settings" } }"""

/** Wraps a root node in a minimal valid document: the node's page plus a second page that links to OwnTV's settings. */
fun docWithRoot(root: String, extra: String = "") = """
    {
      "schemaVersion": 3,
      $extra
      "pages": [ { "id": "home", "root": $root }, { "id": "settings_page", "root": $SETTINGS_LINK } ]
    }
""".trimIndent()

const val TILE = """{ "type": "app_tile", "id": "tile", "package": "com.example.app" }"""

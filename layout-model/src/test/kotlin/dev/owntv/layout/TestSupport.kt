package dev.owntv.layout

import dev.owntv.layout.codec.DecodeResult
import dev.owntv.layout.validation.Severity
import java.io.File
import kotlin.test.fail

val rootDir: File = File(System.getProperty("owntv.rootDir") ?: "..")
val examplesDir: File = File(rootDir, "examples")

fun DecodeResult.requireDocument() = document ?: fail("Expected a document, got:\n${issues.joinToString("\n")}")

fun DecodeResult.paths(severity: Severity) = issues.filter { it.severity == severity }.map { it.path }

/** Wraps a root node in a minimal valid document. */
fun docWithRoot(root: String, extra: String = "") = """
    {
      "schemaVersion": 1,
      $extra
      "pages": [ { "id": "home", "root": $root } ]
    }
""".trimIndent()

const val TILE = """{ "type": "app_tile", "id": "tile", "package": "com.example.app" }"""

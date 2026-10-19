package dev.owntv.layout.schema

import java.io.File

/** Entry point for `./gradlew :layout-model:generateSchema`. */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: error("Pass the output file path"))
    target.parentFile.mkdirs()
    target.writeText(JsonSchemaGenerator.generateText())
    println("Wrote ${target.path}")
}

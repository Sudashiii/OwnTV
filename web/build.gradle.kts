@file:OptIn(ExperimentalWasmDsl::class)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // Unpacks Skia's Wasm runtime (skiko) next to the bundle.
    alias(libs.plugins.compose.multiplatform)
}

// Binaryen (the Wasm optimizer) comes from the repository in settings.gradle.kts.
plugins.withType<BinaryenPlugin> { the<BinaryenEnvSpec>().downloadBaseUrl.set(null as String?) }

// The web preview: the shared renderer compiled to WebAssembly, next to a JSON editor.
kotlin {
    wasmJs {
        outputModuleName.set("owntv-web")
        browser {
            commonWebpackConfig {
                outputFileName = "owntv-web.js"
            }
        }
        binaries.executable()
    }

    compilerOptions {
        optIn.addAll(
            "kotlin.wasm.unsafe.UnsafeWasmMemoryApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":layout-render"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.js)
        }
    }
}

/** Copies the example layouts, built-in looks and JSON Schema next to the page, with an index for the picker. */
abstract class BundleWebSamples : DefaultTask() {
    @get:InputFiles
    abstract val examples: ConfigurableFileCollection

    @get:InputFiles
    abstract val looks: ConfigurableFileCollection

    @get:InputFile
    abstract val schema: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        fun copy(files: Set<File>, dir: String) = files.sortedBy { it.name }.map { file ->
            file.copyTo(File(out, "layouts/$dir/${file.name}"), overwrite = true)
            val name = runCatching { (JsonSlurper().parse(file) as Map<*, *>)["name"] as? String }.getOrNull()
            mapOf("file" to "$dir/${file.name}", "name" to (name ?: file.nameWithoutExtension))
        }
        val index = mapOf("examples" to copy(examples.files, "examples"), "looks" to copy(looks.files, "looks"))
        File(out, "layouts/index.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(index)))
        schema.get().asFile.copyTo(File(out, "schema.json"), overwrite = true)
    }
}

val bundleWebSamples = tasks.register<BundleWebSamples>("bundleWebSamples") {
    examples.from(rootProject.fileTree("examples") { include("*.json") })
    looks.from(rootProject.fileTree("looks") { include("*.json") })
    schema.set(rootProject.layout.projectDirectory.file("schema/owntv-layout.v2.schema.json"))
    outputDir.set(layout.buildDirectory.dir("generated/webSamples"))
}

kotlin.sourceSets.named("wasmJsMain") {
    resources.srcDir(bundleWebSamples.map { it.outputDir })
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.owntv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.owntv.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.tv.material3.ExperimentalTvMaterial3Api",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

/** Copies the example layouts, built-in looks and AI docs from the repo root into the APK's assets. */
abstract class BundleLayoutAssets : DefaultTask() {
    @get:InputFiles
    abstract val templates: ConfigurableFileCollection

    @get:InputFiles
    abstract val looks: ConfigurableFileCollection

    @get:InputFiles
    abstract val aiDocs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        templates.files.forEach { it.copyTo(File(out, "templates/${it.name}"), overwrite = true) }
        looks.files.forEach { it.copyTo(File(out, "looks/${it.name}"), overwrite = true) }
        aiDocs.files.forEach { it.copyTo(File(out, "ai/${it.name}"), overwrite = true) }
    }
}

val bundleLayoutAssets = tasks.register<BundleLayoutAssets>("bundleLayoutAssets") {
    templates.from(rootProject.fileTree("examples") { include("*.json") })
    looks.from(rootProject.fileTree("looks") { include("*.json") })
    aiDocs.from(rootProject.file("docs/ai-prompt.md"), rootProject.file("docs/layout-spec.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleLayoutAssets, BundleLayoutAssets::outputDir)
    }
}

dependencies {
    implementation(project(":layout-model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.zxing.core)
    testImplementation(kotlin("test-junit"))
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseVersion: String? = providers.gradleProperty("owntv.version").orNull
val releaseKeystore: String? = providers.environmentVariable("OWNTV_KEYSTORE_FILE").orNull

/** Where Settings > Updates looks for new releases. The release workflow passes its own repository's URL. */
val updateUrl: String = providers.gradleProperty("owntv.updateUrl").orNull
    ?: "https://github.com/Sudashiii/OwnTV/releases/latest/download/update.json"

/** The release list behind the version list in Settings > Updates: GitHub's releases API of the same repository. */
val releasesUrl: String = providers.gradleProperty("owntv.releasesUrl").orNull
    ?: Regex("^https://github\\.com/([^/]+)/([^/]+)/releases/").find(updateUrl)
        ?.let { "https://api.github.com/repos/${it.groupValues[1]}/${it.groupValues[2]}/releases?per_page=100" }
    ?: ""

/** 1.2.3 -> 1002003, so every release has a higher versionCode than the last and installs as an update. */
fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it in 0..999 }) { "Expected a version like 1.2.3, got $version" }
    val (major, minor, patch) = parts.map { it!! }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "dev.owntv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.owntv.launcher"
        minSdk = 29
        targetSdk = 35
        // The release workflow passes -Powntv.version=1.2.3 (the release tag); local builds are 0.0.0-dev.
        versionName = releaseVersion ?: "0.0.0-dev"
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 1
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "RELEASES_URL", "\"$releasesUrl\"")
    }

    signingConfigs {
        // Set by the release workflow from repository secrets. Without them release APKs stay unsigned.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("OWNTV_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("OWNTV_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("OWNTV_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose markedly faster on TV chips (inlining, no unused code, no checks).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":layout-render"))

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
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.okhttp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.zxing.core)
    testImplementation(kotlin("test-junit"))
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
}

// The layout renderer, shared by the TV app (android) and the web preview (wasmJs).
// Keep platform code out of commonMain: what differs lives behind the interfaces in Platform.kt.
kotlin {
    android {
        namespace = "dev.owntv.render"
        compileSdk = 37
        minSdk = 29
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    wasmJs {
        browser()
    }

    compilerOptions {
        optIn.addAll(
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":layout-model"))
            api(libs.cmp.runtime)
            api(libs.cmp.ui)
            api(libs.cmp.foundation)
            implementation(libs.cmp.animation)
            implementation(libs.cmp.material.icons.core)
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.haze)
            implementation(libs.haze.glass)
        }
    }
}

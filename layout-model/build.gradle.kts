import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Shared by the Android app (jvm) and the web preview (wasmJs).
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    wasmJs {
        browser()
    }

    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

val schemaFile = rootProject.layout.projectDirectory.file("schema/owntv-layout.v3.schema.json")

tasks.named<Test>("jvmTest") {
    useJUnit()
    systemProperty("owntv.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("examples"))
    inputs.dir(rootProject.layout.projectDirectory.dir("looks"))
    inputs.file(schemaFile).optional()
}

// Keeps `./gradlew :layout-model:test` working: the tests run on the JVM.
tasks.register("test") {
    group = "verification"
    description = "Runs the layout-model tests on the JVM."
    dependsOn("jvmTest")
}

// Writes schema/owntv-layout.v3.schema.json from the Kotlin model.
tasks.register<JavaExec>("generateSchema") {
    group = "owntv"
    description = "Regenerates the JSON Schema for OwnTV layouts."
    val main = kotlin.jvm().compilations.getByName("main")
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("dev.owntv.layout.schema.GenerateSchemaKt")
    args(schemaFile.asFile.absolutePath)
}

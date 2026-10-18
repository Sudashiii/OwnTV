import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

val schemaFile = rootProject.layout.projectDirectory.file("schema/owntv-layout.v2.schema.json")

tasks.test {
    useJUnit()
    systemProperty("owntv.rootDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("examples"))
    inputs.file(schemaFile).optional()
}

// Writes schema/owntv-layout.v2.schema.json from the Kotlin model.
tasks.register<JavaExec>("generateSchema") {
    group = "owntv"
    description = "Regenerates the JSON Schema for OwnTV layouts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.owntv.layout.schema.GenerateSchemaKt")
    args(schemaFile.asFile.absolutePath)
}

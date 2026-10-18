package dev.owntv.layout

import dev.owntv.layout.ai.AiPromptBuilder
import dev.owntv.layout.codec.LayoutCodec
import dev.owntv.layout.codec.Migrator
import dev.owntv.layout.model.NodeRegistry
import dev.owntv.layout.schema.JsonSchemaGenerator
import dev.owntv.layout.validation.IssueCollector
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaAndExamplesTest {

    @Test
    fun `checked-in schema matches the model`() {
        val file = File(rootDir, "schema/owntv-layout.v1.schema.json")
        assertTrue(file.exists(), "Run ./gradlew :layout-model:generateSchema")
        assertEquals(
            JsonSchemaGenerator.generateText(),
            file.readText(),
            "schema/owntv-layout.v1.schema.json is out of date. Run ./gradlew :layout-model:generateSchema",
        )
    }

    @Test
    fun `schema defines every node type`() {
        val defs = JsonSchemaGenerator.generate()["\$defs"]!!.jsonObject
        NodeRegistry.types.forEach { assertNotNull(defs["Node.$it"], "Missing Node.$it") }
    }

    @Test
    fun `every example is valid and warning free`() {
        val files = examplesDir.listFiles { f -> f.extension == "json" }!!.sorted()
        assertTrue(files.size >= 4)
        files.forEach { file ->
            val result = LayoutCodec.decode(file.readText())
            assertTrue(result.ok && result.issues.isEmpty(), "${file.name}:\n${result.issues.joinToString("\n")}")
        }
    }

    @Test
    fun `complete example in the spec is valid`() {
        val spec = File(rootDir, "docs/layout-spec.md").readText()
        val example = spec.substringAfter("## Complete example").substringAfter("```json\n").substringBefore("```")
        val result = LayoutCodec.decode(example)
        assertTrue(result.ok && result.issues.isEmpty(), result.issues.joinToString("\n"))
    }

    @Test
    fun `migrator applies steps in order`() {
        val migrator = Migrator(3, mapOf(
            1 to { o: JsonObject -> JsonObject(o + ("a" to JsonPrimitive(1))) },
            2 to { o: JsonObject -> JsonObject(o + ("b" to JsonPrimitive(2))) },
        ))
        val out = IssueCollector()
        val result = migrator.migrate(JsonObject(mapOf("schemaVersion" to JsonPrimitive(1))), out)!!
        assertEquals(JsonPrimitive(3), result["schemaVersion"])
        assertEquals(JsonPrimitive(1), result["a"])
        assertEquals(JsonPrimitive(2), result["b"])

        assertNull(Migrator(1, emptyMap()).migrate(JsonObject(mapOf("schemaVersion" to JsonPrimitive(2))), IssueCollector()))
    }

    @Test
    fun `ai prompt contains apps, layout and request marker`() {
        val prompt = AiPromptBuilder.build(
            instructions = "INSTRUCTIONS",
            spec = "SPEC",
            apps = listOf(AiPromptBuilder.AppEntry("Netflix", "com.netflix.ninja", isGame = false, isTvApp = true)),
            currentLayoutJson = "{ }",
        )
        assertTrue(prompt.startsWith("INSTRUCTIONS"))
        assertTrue(prompt.contains("| Netflix | com.netflix.ninja | no | yes |"))
        assertTrue(prompt.contains("```json\n{ }\n```"))
        assertTrue(prompt.trimEnd().endsWith("## My request"))
    }
}

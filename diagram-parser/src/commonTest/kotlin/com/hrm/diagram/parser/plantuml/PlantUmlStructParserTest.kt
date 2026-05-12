package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlStructParserTest {
    @Test
    fun parses_json_objects_arrays_and_scalars() {
        val ir = parse(
            PlantUmlStructParser.Format.JSON,
            """{"name":"Alice","age":30,"skills":["KMP","PlantUML"],"meta":{"active":true}}""",
        )

        assertEquals(SourceLanguage.PLANTUML, ir.sourceLanguage)
        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals(listOf("name", "age", "skills", "meta"), root.entries.map { it.key })
        assertEquals("Alice", assertIs<StructNode.Scalar>(root.entries[0]).value)
        val skills = assertIs<StructNode.ArrayNode>(root.entries[2])
        assertEquals(listOf("KMP", "PlantUML"), skills.items.map { assertIs<StructNode.Scalar>(it).value })
        val meta = assertIs<StructNode.ObjectNode>(root.entries[3])
        assertEquals("true", assertIs<StructNode.Scalar>(meta.entries.single()).value)
    }

    @Test
    fun parses_yaml_nested_objects_arrays_and_inline_lists() {
        val ir = parse(
            PlantUmlStructParser.Format.YAML,
            """
            name: Alice
            skills: [KMP, PlantUML]
            children:
              - name: Bob
                age: 7
              - Carol
            """.trimIndent(),
        )

        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals(listOf("name", "skills", "children"), root.entries.map { it.key })
        val skills = assertIs<StructNode.ArrayNode>(root.entries[1])
        assertEquals(listOf("KMP", "PlantUML"), skills.items.map { assertIs<StructNode.Scalar>(it).value })
        val children = assertIs<StructNode.ArrayNode>(root.entries[2])
        val bob = assertIs<StructNode.ObjectNode>(children.items.first())
        assertEquals(listOf("name", "age"), bob.entries.map { it.key })
        assertEquals("Carol", assertIs<StructNode.Scalar>(children.items.last()).value)
    }

    @Test
    fun parses_yaml_literal_and_folded_block_scalars() {
        val ir = parse(
            PlantUmlStructParser.Format.YAML,
            """
            description: |
              first line
              second line
            summary: >
              folded
              text
            items:
              - |
                item line 1
                item line 2
            """.trimIndent(),
        )

        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals("first line\nsecond line", assertIs<StructNode.Scalar>(root.entries[0]).value)
        assertEquals("folded text", assertIs<StructNode.Scalar>(root.entries[1]).value)
        val items = assertIs<StructNode.ArrayNode>(root.entries[2])
        assertEquals("item line 1\nitem line 2", assertIs<StructNode.Scalar>(items.items.single()).value)
    }

    @Test
    fun invalid_final_json_reports_diagnostic() {
        val parser = PlantUmlStructParser(PlantUmlStructParser.Format.JSON)
        parser.acceptLine("""{"name":""")
        parser.finish(blockClosed = true)
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E013" })
    }

    private fun parse(format: PlantUmlStructParser.Format, source: String): StructIR {
        val parser = PlantUmlStructParser(format)
        source.lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics: ${parser.diagnosticsSnapshot()}")
        return parser.snapshot()
    }
}

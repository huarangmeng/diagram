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
    fun parses_json_unicode_escapes_and_strict_literals_with_type_extras() {
        val ir = parse(
            PlantUmlStructParser.Format.JSON,
            """{"emoji":"\u263A","count":1.5e2,"ok":false,"none":null}""",
        )

        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals("☺", assertIs<StructNode.Scalar>(root.entries[0]).value)
        assertEquals("1.5e2", assertIs<StructNode.Scalar>(root.entries[1]).value)
        assertEquals("false", assertIs<StructNode.Scalar>(root.entries[2]).value)
        assertEquals("null", assertIs<StructNode.Scalar>(root.entries[3]).value)
        assertTrue(ir.styleHints.extras[PlantUmlStructParser.COLLAPSIBLE_PATHS_KEY].orEmpty().contains("root"))
        assertTrue(ir.styleHints.extras[PlantUmlStructParser.SCALAR_KINDS_KEY].orEmpty().contains("root.1|number"))
        assertTrue(ir.styleHints.extras[PlantUmlStructParser.SCALAR_KINDS_KEY].orEmpty().contains("root.2|boolean"))
        assertTrue(ir.styleHints.extras[PlantUmlStructParser.SCALAR_KINDS_KEY].orEmpty().contains("root.3|null"))
    }

    @Test
    fun parses_yaml_comments_quotes_inline_objects_and_block_chomping() {
        val ir = parse(
            PlantUmlStructParser.Format.YAML,
            """
            ---
            title: "A # quoted value" # trailing comment
            flags: { enabled: yes, count: 3, tags: ["a,b", c] }
            items:
              - meta:
                  id: 1
                  active: off
              - { name: "Inline", score: 2.5 }
            literal: |-
              keep
              newlines
            folded: >+
              fold
              me
            ...
            """.trimIndent(),
        )

        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals("A # quoted value", assertIs<StructNode.Scalar>(root.entries[0]).value)
        val flags = assertIs<StructNode.ObjectNode>(root.entries[1])
        assertEquals("true", assertIs<StructNode.Scalar>(flags.entries[0]).value)
        assertEquals("3", assertIs<StructNode.Scalar>(flags.entries[1]).value)
        val tags = assertIs<StructNode.ArrayNode>(flags.entries[2])
        assertEquals(listOf("a,b", "c"), tags.items.map { assertIs<StructNode.Scalar>(it).value })
        val items = assertIs<StructNode.ArrayNode>(root.entries[2])
        val meta = assertIs<StructNode.ObjectNode>(assertIs<StructNode.ObjectNode>(items.items[0]).entries.single())
        assertEquals(listOf("id", "active"), meta.entries.map { it.key })
        assertEquals("false", assertIs<StructNode.Scalar>(meta.entries[1]).value)
        val inline = assertIs<StructNode.ObjectNode>(items.items[1])
        assertEquals(listOf("name", "score"), inline.entries.map { it.key })
        assertEquals("keep\nnewlines", assertIs<StructNode.Scalar>(root.entries[3]).value)
        assertEquals("fold me\n", assertIs<StructNode.Scalar>(root.entries[4]).value)
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

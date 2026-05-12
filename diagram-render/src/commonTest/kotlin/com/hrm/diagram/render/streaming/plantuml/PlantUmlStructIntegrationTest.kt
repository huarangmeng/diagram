package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlStructIntegrationTest {
    @Test
    fun json_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startjson
            {
              "name": "Alice",
              "skills": ["KMP", "PlantUML"],
              "meta": { "active": true }
            }
            @endjson
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<StructIR>(one.ir)
        val chunkedIr = assertIs<StructIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "name: Alice" })
    }

    @Test
    fun yaml_diagram_renders_nested_arrays() {
        val snapshot = run(
            """
            @startyaml
            name: Alice
            children:
              - name: Bob
                age: 7
              - Carol
            @endyaml
            """.trimIndent() + "\n",
            4,
        )

        val ir = assertIs<StructIR>(snapshot.ir)
        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals(listOf("name", "children"), root.entries.map { it.key })
        val laid = assertNotNull(snapshot.laidOut)
        assertTrue(laid.nodePositions.size >= 6, "nested YAML rows should be laid out")
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue("children: [2]" in texts)
        assertTrue("name: Bob" in texts)
        assertTrue("[1]: Carol" in texts)
    }

    @Test
    fun yaml_block_scalars_render_and_are_streaming_consistent() {
        val src =
            """
            @startyaml
            description: |
              first line
              second line
            summary: >
              folded
              text
            @endyaml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 3)
        val oneIr = assertIs<StructIR>(one.ir)
        val chunkedIr = assertIs<StructIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val root = assertIs<StructNode.ObjectNode>(oneIr.root)
        assertEquals("first line\nsecond line", assertIs<StructNode.Scalar>(root.entries[0]).value)
        assertEquals("folded text", assertIs<StructNode.Scalar>(root.entries[1]).value)
        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(texts.any { it.startsWith("description: first line") })
        assertTrue("summary: folded text" in texts)
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.PLANTUML).let { s ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            s.finish()
        } finally {
            s.close()
        }
    }
}

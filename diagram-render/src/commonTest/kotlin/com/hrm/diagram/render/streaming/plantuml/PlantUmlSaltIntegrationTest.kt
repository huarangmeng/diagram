package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlSaltIntegrationTest {
    @Test
    fun startsalt_renders_plain_text_and_button_with_streaming_consistency() {
        val src =
            """
            @startsalt
            salt {
              Login form
              [Submit]
            }
            @endsalt
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<WireframeIR>(one.ir)
        val chunkedIr = assertIs<WireframeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val root = assertIs<WireBox.Plain>(oneIr.root)
        assertEquals(2, root.children.size)
        assertIs<WireBox.Button>(root.children[1])
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Login form" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Submit" })
    }

    @Test
    fun startsalt_renders_inputs_dropdowns_and_tabs_with_streaming_consistency() {
        val src =
            """
            @startsalt
            salt {
              Username: "alice@example.com"
              "password"
              ^Role^
              {* General | Advanced | Help }
            }
            @endsalt
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<WireframeIR>(one.ir)
        val chunkedIr = assertIs<WireframeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val root = assertIs<WireBox.Plain>(oneIr.root)
        assertIs<WireBox.Input>(root.children[0])
        assertIs<WireBox.Input>(root.children[1])
        assertIs<WireBox.Button>(root.children[2])
        assertIs<WireBox.TabbedGroup>(root.children[3])
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Username" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Role v" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Advanced" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
    }

    @Test
    fun startsalt_renders_tree_and_frame_with_streaming_consistency() {
        val src =
            """
            @startsalt
            salt {
              {T Menu
                + File
                ++ Open
                + Edit
              }
              {+ Login
                Username: "alice@example.com"
                [Submit]
              }
            }
            @endsalt
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<WireframeIR>(one.ir)
        val chunkedIr = assertIs<WireframeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val root = assertIs<WireBox.Plain>(oneIr.root)
        val tree = assertIs<WireBox.Plain>(root.children[0])
        val frame = assertIs<WireBox.Plain>(root.children[1])
        assertEquals("Menu", labelOf(tree))
        assertEquals("Frame: Login", labelOf(frame))
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Menu" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Login" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Open" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().size >= 2)
    }

    @Test
    fun startsalt_renders_table_grid_with_streaming_consistency() {
        val src =
            """
            @startsalt
            salt {
              Name | Role | Active
              Alice | Admin | Yes
              Bob | Viewer | No
              {+ Details
                Key | Value
                Region | APAC
              }
            }
            @endsalt
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<WireframeIR>(one.ir)
        val chunkedIr = assertIs<WireframeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val root = assertIs<WireBox.Plain>(oneIr.root)
        val table = assertIs<WireBox.Plain>(root.children[0])
        val frame = assertIs<WireBox.Plain>(root.children[1])
        assertEquals("Table", labelOf(table))
        assertEquals("Frame: Details", labelOf(frame))
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Name" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Alice" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "APAC" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().size >= 8)
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

    private fun labelOf(box: WireBox): String =
        when (val label = box.label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
        }
}

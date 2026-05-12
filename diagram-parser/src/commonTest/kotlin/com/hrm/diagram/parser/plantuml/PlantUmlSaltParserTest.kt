package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.WireBox
import com.hrm.diagram.core.ir.WireframeIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlSaltParserTest {
    @Test
    fun parses_plain_text_and_buttons() {
        val parser = PlantUmlSaltParser()
        """
        salt {
          Login form
          [Submit]
        }
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<WireframeIR>(parser.snapshot())
        val root = assertIs<WireBox.Plain>(ir.root)
        assertEquals(2, root.children.size)
        assertEquals("Login form", labelOf(assertIs<WireBox.Plain>(root.children[0])))
        assertEquals("Submit", labelOf(assertIs<WireBox.Button>(root.children[1])))
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_inputs_dropdowns_and_tabs() {
        val parser = PlantUmlSaltParser()
        """
        salt {
          Username: "alice@example.com"
          "password"
          ^Role^
          {* General | Advanced | Help }
        }
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val root = assertIs<WireBox.Plain>(parser.snapshot().root)
        assertEquals(4, root.children.size)
        val namedInput = assertIs<WireBox.Input>(root.children[0])
        assertEquals("Username", labelOf(namedInput))
        assertEquals("alice@example.com", namedInput.placeholder)
        val bareInput = assertIs<WireBox.Input>(root.children[1])
        assertEquals("password", labelOf(bareInput))
        assertEquals("password", bareInput.placeholder)
        assertEquals("Role v", labelOf(assertIs<WireBox.Button>(root.children[2])))
        val tabs = assertIs<WireBox.TabbedGroup>(root.children[3])
        assertEquals(listOf("General", "Advanced", "Help"), tabs.tabs.map { labelOf(it) })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_tree_and_frame_blocks() {
        val parser = PlantUmlSaltParser()
        """
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
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val root = assertIs<WireBox.Plain>(parser.snapshot().root)
        assertEquals(2, root.children.size)
        val tree = assertIs<WireBox.Plain>(root.children[0])
        assertEquals("Menu", labelOf(tree))
        assertEquals(listOf("File", "Edit"), tree.children.map { labelOf(it) })
        val file = assertIs<WireBox.Plain>(tree.children[0])
        assertEquals("Open", labelOf(assertIs<WireBox.Plain>(file.children.single())))

        val frame = assertIs<WireBox.Plain>(root.children[1])
        assertEquals("Frame: Login", labelOf(frame))
        assertIs<WireBox.Input>(frame.children[0])
        assertIs<WireBox.Button>(frame.children[1])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_table_grid_rows() {
        val parser = PlantUmlSaltParser()
        """
        salt {
          Name | Role | Active
          Alice | Admin | Yes
          Bob | Viewer | No
          {+ Details
            Key | Value
            Region | APAC
          }
        }
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val root = assertIs<WireBox.Plain>(parser.snapshot().root)
        assertEquals(2, root.children.size)
        val table = assertIs<WireBox.Plain>(root.children[0])
        assertEquals("Table", labelOf(table))
        assertEquals(3, table.children.size)
        val header = assertIs<WireBox.Plain>(table.children[0])
        assertEquals("Row", labelOf(header))
        assertEquals(listOf("Name", "Role", "Active"), header.children.map { labelOf(it) })

        val frame = assertIs<WireBox.Plain>(root.children[1])
        assertEquals("Frame: Details", labelOf(frame))
        val nestedTable = assertIs<WireBox.Plain>(frame.children.single())
        assertEquals("Table", labelOf(nestedTable))
        val nestedRow = assertIs<WireBox.Plain>(nestedTable.children[1])
        assertEquals(listOf("Region", "APAC"), nestedRow.children.map { labelOf(it) })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun reports_missing_closing_delimiter() {
        val parser = PlantUmlSaltParser()
        parser.acceptLine("Hello")
        parser.finish(blockClosed = false)
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E017" })
    }

    private fun labelOf(box: WireBox): String =
        when (val label = box.label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
        }
}

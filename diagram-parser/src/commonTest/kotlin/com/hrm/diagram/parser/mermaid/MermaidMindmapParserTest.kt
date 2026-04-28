package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidMindmapParserTest {
    private fun feedAll(src: String): MermaidMindmapParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur
        val p = MermaidMindmapParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun parses_official_sample_subset() {
        val p = feedAll(
            """
            mindmap
              root((mindmap))
                Origins
                  Long history
                  ::icon(fa fa-book)
                  Popularisation
                    British popular psychology author Tony Buzan
                Tools
                  Pen and paper
                  Mermaid
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<TreeIR>(ir)
        assertEquals("root", ir.root.id.value)
        assertEquals("mindmap", (ir.root.label as RichLabel.Plain).text)
        assertEquals(2, ir.root.children.size)
    }

    @Test
    fun class_line_is_ignored_with_warning() {
        val p = feedAll(
            """
            mindmap
              Root
                A[A]
                :::urgent large
            """.trimIndent() + "\n",
        )
        assertTrue(p.diagnosticsSnapshot().any { it.code == "MERMAID-W010" })
    }

    @Test
    fun parses_bang_cloud_and_icon_nodes() {
        val p = feedAll(
            """
            mindmap
              Root
                boom))Bang((
                sky)Cloud(
                ::icon(mdi mdi-skull-outline)
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertEquals(3, ir.root.children.size)
        val shapes = p.snapshotNodeShapes()
        assertEquals(NodeShape.Custom("bang"), shapes[ir.root.children[0].id])
        assertEquals(NodeShape.Cloud, shapes[ir.root.children[1].id])
        assertTrue(p.snapshotNodeIcons().containsKey(ir.root.children[2].id))
    }
}

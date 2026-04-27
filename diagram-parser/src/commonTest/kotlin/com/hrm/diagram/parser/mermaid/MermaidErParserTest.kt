package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidErParserTest {

    private fun feedAll(src: String): MermaidErParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        // Group tokens into logical lines (drop NEWLINE tokens).
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur

        val p = MermaidErParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun header_required() {
        val p = MermaidErParser()
        p.acceptLine(listOf(Token(MermaidTokenKind.IDENT, 0, 1, "X")))
        assertTrue(p.diagnosticsSnapshot().isNotEmpty())
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("erDiagram") })
    }

    @Test
    fun entity_block_attributes_and_relationship() {
        val p = feedAll(
            """
            erDiagram
            CAR {
              string make PK
              string model
            }
            CAR ||--o{ PERSON : allows
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val nodeIds = ir.nodes.map { it.id.value }.toSet()
        assertTrue("CAR" in nodeIds)
        assertTrue("PERSON" in nodeIds)
        assertTrue("CAR::make" in nodeIds)
        assertTrue("CAR::model" in nodeIds)

        // 2 attribute edges + 1 relationship edge.
        assertEquals(3, ir.edges.size)
        val rel = ir.edges.last()
        assertEquals(NodeId("CAR"), rel.from)
        assertEquals(NodeId("PERSON"), rel.to)
        assertEquals("||--o{ allows", (rel.label as com.hrm.diagram.core.ir.RichLabel.Plain).text)
    }
}


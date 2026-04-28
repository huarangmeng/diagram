package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidKanbanParserTest {
    private fun feedAll(src: String): MermaidKanbanParser {
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
        val p = MermaidKanbanParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun parses_official_sample_subset_with_metadata() {
        val p = feedAll(
            """
            kanban
              todo[Todo]
                [Create Documentation]
                docs[Create Blog about the new diagram]
              id10[Ready for test]
                id4[Create parsing tests]@{ ticket: MC-2038, assigned: 'K.Sveidqvist', priority: 'High' }
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<KanbanIR>(ir)
        assertEquals(2, ir.columns.size)
        assertEquals("Todo", (ir.columns[0].label as RichLabel.Plain).text)
        assertEquals(2, ir.columns[0].cards.size)
        assertEquals("MC-2038", ir.columns[1].cards[0].payload["ticket"])
        assertEquals("K.Sveidqvist", ir.columns[1].cards[0].payload["assigned"])
        assertEquals("High", ir.columns[1].cards[0].payload["priority"])
    }

    @Test
    fun malformed_card_line_is_error() {
        val p = feedAll(
            """
            kanban
              Todo
                @{ ticket: MC-1 }
            """.trimIndent() + "\n",
        )
        assertTrue(p.diagnosticsSnapshot().any { it.code == "MERMAID-E205" })
    }
}

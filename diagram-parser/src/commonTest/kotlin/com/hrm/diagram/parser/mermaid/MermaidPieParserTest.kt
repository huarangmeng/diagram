package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidPieParserTest {

    private fun feedAll(src: String): MermaidPieParser {
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

        val p = MermaidPieParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun header_required() {
        val p = MermaidPieParser()
        p.acceptLine(listOf(Token(MermaidTokenKind.IDENT, 0, 1, "X")))
        assertTrue(p.diagnosticsSnapshot().isNotEmpty())
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("pie") })
    }

    @Test
    fun parses_title_and_slices() {
        val p = feedAll(
            """
            pie
              title Pets adopted by volunteers
              "Dogs" : 386
              "Cats" : 85
              Rats : 15
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<PieIR>(ir)
        assertEquals("Pets adopted by volunteers", ir.title)
        assertEquals(3, ir.slices.size)
        assertEquals("Dogs", (ir.slices[0].label as RichLabel.Plain).text)
        assertEquals(386.0, ir.slices[0].value)
    }
}


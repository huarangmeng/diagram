package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidGaugeParserTest {

    private fun feedAll(src: String): MermaidGaugeParser {
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

        val p = MermaidGaugeParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun header_required() {
        val p = MermaidGaugeParser()
        p.acceptLine(listOf(Token(MermaidTokenKind.IDENT, 0, 1, "X")))
        assertTrue(p.diagnosticsSnapshot().isNotEmpty())
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("gauge") })
    }

    @Test
    fun parses_title_min_max_value() {
        val p = feedAll(
            """
            gauge
              title CPU Usage
              min 0
              max 200
              value 60
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<GaugeIR>(ir)
        assertEquals("CPU Usage", ir.title)
        assertEquals(0.0, ir.min)
        assertEquals(200.0, ir.max)
        assertEquals(60.0, ir.value)
    }
}


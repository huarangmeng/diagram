package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidSankeyParserTest {
    private fun feedAll(src: String): MermaidSankeyParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) {
                    lines += cur
                    cur = ArrayList()
                }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur
        return MermaidSankeyParser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_basic_flows() {
        val parser = feedAll(
            """
            sankey-beta
              A,B,10
              A,C,5
              B,D,2.5
            """.trimIndent() + "\n",
        )
        val ir = assertIs<SankeyIR>(parser.snapshot())
        assertEquals(4, ir.nodes.size)
        assertEquals(3, ir.flows.size)
        assertEquals(10.0, ir.flows[0].value)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }
}

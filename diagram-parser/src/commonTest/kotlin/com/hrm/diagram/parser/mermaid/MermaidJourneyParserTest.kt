package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.JourneyIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidJourneyParserTest {
    private fun feedAll(src: String): MermaidJourneyParser {
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
        return MermaidJourneyParser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_title_sections_and_steps() {
        val parser = feedAll(
            """
            journey
              title My working day
              section Go to work
                Make tea: 5: Me
                Do work: 1: Me, Cat
              section Go home
                Sit down: 5: Me
            """.trimIndent() + "\n",
        )
        val ir = assertIs<JourneyIR>(parser.snapshot())
        assertEquals("My working day", ir.title)
        assertEquals(2, ir.stages.size)
        assertEquals(2, ir.stages[0].steps.size)
        assertEquals(5, ir.stages[0].steps[0].score)
        assertEquals(2, ir.stages[0].steps[1].actors.size)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }
}

package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidTimelineParserTest {

    private fun feedAll(src: String): MermaidTimelineParser {
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

        val p = MermaidTimelineParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun parses_official_basic_sample_with_continuation_and_multi_event() {
        val p = feedAll(
            """
            timeline
                title History of Social Media Platform
                2002 : LinkedIn
                2004 : Facebook
                     : Google
                2005 : YouTube
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<TimeSeriesIR>(ir)
        assertEquals("History of Social Media Platform", ir.title)
        assertTrue(ir.tracks.isNotEmpty())
        // Events: 2002(1) + 2004(2) + 2005(1) = 4
        assertEquals(4, ir.items.size)
    }

    @Test
    fun parses_sections_and_direction() {
        val p = feedAll(
            """
            timeline TD
              title Timeline of Industrial Revolution
              section 17th-20th century
                Industry 1.0 : Machinery
              section 21st century
                Industry 4.0 : Internet : Robotics
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<TimeSeriesIR>(ir)
        assertEquals(2, ir.tracks.size)
        assertEquals(3, ir.items.size)
    }
}


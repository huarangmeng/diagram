package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.QuadrantChartIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidQuadrantChartParserTest {
    private fun feedAll(src: String): MermaidQuadrantChartParser {
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
        val p = MermaidQuadrantChartParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun parses_official_sample_subset() {
        val p = feedAll(
            """
            quadrantChart
                title Reach and engagement of campaigns
                x-axis Low Reach --> High Reach
                y-axis Low Engagement --> High Engagement
                quadrant-1 We should expand
                quadrant-2 Need to promote
                quadrant-3 Re-evaluate
                quadrant-4 May be improved
                Campaign A: [0.3, 0.6]
                Campaign B: [0.45, 0.23]
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<QuadrantChartIR>(ir)
        assertEquals("Reach and engagement of campaigns", ir.title)
        assertEquals("Low Reach", (ir.xMinLabel as RichLabel.Plain).text)
        assertEquals("High Reach", (ir.xMaxLabel as RichLabel.Plain).text)
        assertEquals(2, ir.points.size)
    }

    @Test
    fun parses_point_inline_style() {
        val p = feedAll(
            """
            quadrantChart
              Point A: [0.9, 0.0] radius: 12, color: #ff3300, stroke-color: #00ff0f, stroke-width: 5px
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<QuadrantChartIR>(ir)
        assertEquals(12.0, ir.points.single().payload["radius"]?.toDouble())
        assertTrue(ir.points.single().payload.containsKey("color"))
        assertTrue(ir.points.single().payload.containsKey("stroke-color"))
    }
}

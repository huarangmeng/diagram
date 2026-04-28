package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.AxisKind
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MermaidXYChartParserTest {
    private fun feedAll(src: String): MermaidXYChartParser {
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
        val p = MermaidXYChartParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun parses_official_sample_subset() {
        val p = feedAll(
            """
            xychart
                title "Sales Revenue"
                x-axis [jan, feb, mar]
                y-axis "Revenue (in $)" 4000 --> 11000
                bar [5000, 6000, 7500]
                line [5000, 6000, 7500]
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<XYChartIR>(ir)
        assertEquals("Sales Revenue", ir.title)
        assertEquals(AxisKind.Category, ir.xAxis.kind)
        assertEquals(listOf("jan", "feb", "mar"), ir.xAxis.categories)
        assertEquals(2, ir.series.size)
        assertEquals(SeriesKind.Bar, ir.series[0].kind)
        assertEquals(SeriesKind.Line, ir.series[1].kind)
    }

    @Test
    fun parses_horizontal_numeric_x_and_scatter() {
        val p = feedAll(
            """
            xychart-beta horizontal
              x-axis "Index" 0 --> 10
              y-axis "Value" -2 --> 2
              scatter [1, 0, -1]
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<XYChartIR>(ir)
        assertEquals(Direction.LR, ir.styleHints.direction)
        assertEquals(AxisKind.Linear, ir.xAxis.kind)
        assertEquals(SeriesKind.Scatter, ir.series.single().kind)
    }
}


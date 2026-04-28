package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidGanttParserTest {
    private fun feedAll(src: String): MermaidGanttParser {
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

        val p = MermaidGanttParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun parses_official_basic_sample_with_dateFormat_and_after() {
        val p = feedAll(
            """
            gantt
                title A Gantt Diagram
                dateFormat YYYY-MM-DD
                section Section
                    A task :a1, 2014-01-01, 30d
                    Another task :after a1, 20d
                section Another
                    Task in Another :2014-01-12, 12d
                    another task :24d
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<TimeSeriesIR>(ir)
        assertEquals("A Gantt Diagram", ir.title)
        assertEquals(2, ir.tracks.size)
        assertTrue(ir.items.isNotEmpty())
    }

    @Test
    fun parses_milestone_and_excludes_weekends() {
        val p = feedAll(
            """
            gantt
                dateFormat YYYY-MM-DD
                excludes weekends
                weekend friday
                section S
                    Functionality added :milestone, isadded, 2014-01-25, 0d
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertIs<TimeSeriesIR>(ir)
        assertEquals(1, ir.items.size, "items=${ir.items} diags=${p.diagnosticsSnapshot()}")
        // milestone line format: tags, id, startDate, duration
        assertEquals("isadded", ir.items[0].id.value)
    }

    @Test
    fun parses_axis_tick_and_click_metadata() {
        val p = feedAll(
            """
            gantt
                dateFormat YYYY-MM-DD
                axisFormat %m/%d
                tickInterval 1day
                section Clickable
                    Visit site :cl1, 2014-01-07, 3d
                click cl1 href https://mermaid.js.org/
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertEquals("%m/%d", ir.styleHints.extras["gantt.axisFormat"])
        assertEquals("1day", ir.styleHints.extras["gantt.tickInterval"])
        assertEquals("https://mermaid.js.org/", ir.items.single().payload["gantt.href"])
    }

    @Test
    fun parses_vert_marker_without_turning_it_into_a_normal_task() {
        val p = feedAll(
            """
            gantt
                dateFormat HH:mm
                axisFormat %H:%M
                section Markers
                    Initial vert :vert, v1, 17:30, 2m
                    Task A : 3m
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val vert = ir.items.first { it.id.value == "v1" }
        val task = ir.items.first { it.id.value != "v1" }
        assertEquals("vert", vert.payload["gantt.kind"])
        assertEquals(2 * 60_000L, vert.range.endMs - vert.range.startMs)
        assertEquals(0L, task.range.startMs, "vert should not advance the sequential baseline: task=$task vert=$vert")
    }
}

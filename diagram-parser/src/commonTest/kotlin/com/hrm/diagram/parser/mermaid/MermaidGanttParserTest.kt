package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    @Test
    fun parses_mermaid_prefix_vert_marker_without_task_row() {
        val p = feedAll(
            """
            gantt
                dateFormat YYYY-MM-DD
                section Build
                    API implementation :crit, api, 2026-01-12, 10d
                    vert "Code freeze" : 2026-01-23
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val vert = ir.items.single { it.payload["gantt.kind"] == "vert" }
        val task = ir.items.single { it.id.value == "api" }

        assertEquals("Code freeze", (vert.label as com.hrm.diagram.core.ir.RichLabel.Plain).text)
        assertEquals(vert.range.startMs, vert.range.endMs)
        assertTrue(vert.range.startMs >= task.range.endMs, "marker should be represented on the timeline without becoming a task row")
    }

    @Test
    fun parses_mermaid_dateformat_token_variants_in_tasks() {
        val p = feedAll(
            """
            gantt
                dateFormat MMMM D, YYYY h:mm A
                section Fancy
                    Kickoff :k1, January 2, 2014 5:00 PM, 2h
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val item = ir.items.single()
        assertEquals("k1", item.id.value)
        assertEquals(2 * 3_600_000L, item.range.endMs - item.range.startMs)
    }

    @Test
    fun parses_calendar_month_and_year_duration_precisely() {
        val p = feedAll(
            """
            gantt
                dateFormat YYYY-MM-DD
                section Calendar
                    Month Clamp :m1, 2024-01-31, 1M
                    Leap Clamp  :y1, 2024-02-29, 1y
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val month = ir.items.first { it.id.value == "m1" }
        val year = ir.items.first { it.id.value == "y1" }
        val expectedMonth = MermaidGanttTime.parseDateTime("2024-02-29", "YYYY-MM-DD")
        val expectedYear = MermaidGanttTime.parseDateTime("2025-02-28", "YYYY-MM-DD")
        assertNotNull(expectedMonth)
        assertNotNull(expectedYear)
        assertEquals(expectedMonth.epochMs, month.range.endMs)
        assertEquals(expectedYear.epochMs, year.range.endMs)
    }
}

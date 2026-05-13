package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.TimeSeriesIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlGanttParserTest {
    @Test
    fun parses_project_tasks_sections_and_dependencies() {
        val parser = PlantUmlGanttParser()
        """
        Project starts 2024-01-01
        -- Planning --
        [Design] starts 2024-01-01
        [Design] lasts 3 days
        [Build] lasts 5 days
        [Design] -> [Build]
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertEquals(2, ir.tracks.size)
        assertEquals(2, ir.items.size)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
        assertEquals(listOf("design"), ir.items.single { it.id.value == "build" }.depends.map { it.value })
        assertTrue(ir.items.single { it.id.value == "build" }.range.startMs >= ir.items.single { it.id.value == "design" }.range.endMs)
    }

    @Test
    fun parses_resource_tracks_and_task_colors() {
        val parser = PlantUmlGanttParser()
        """
        Project starts 2024-01-01
        [Design] lasts 3 days on {Alice}
        [Design] is colored in #FF8800
        [Build] lasts 2 days on {Bob}
        [Build] is colored in Green
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertTrue(ir.tracks.any { it.label.toString().contains("Resource: Alice") })
        assertTrue(ir.tracks.any { it.label.toString().contains("Resource: Bob") })
        val design = ir.items.single { it.id.value == "design" }
        val build = ir.items.single { it.id.value == "build" }
        assertEquals("Alice", design.payload["gantt.resource"])
        assertEquals("#FF8800", design.payload["gantt.color"])
        assertEquals("Bob", build.payload["gantt.resource"])
        assertEquals("Green", build.payload["gantt.color"])
        assertTrue(design.trackId.value.startsWith("gantt:resource:"))
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_closed_weekdays_and_holiday_ranges() {
        val parser = PlantUmlGanttParser()
        """
        Project starts 2024-01-01
        saturday are closed
        sunday are closed
        2024-01-03 is closed
        2024-01-05 to 2024-01-06 are closed
        [Design] starts 2024-01-01
        [Design] lasts 7 days
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertEquals("6,7", ir.styleHints.extras["gantt.closedWeekdays"])
        val ranges = ir.styleHints.extras["gantt.closedRanges"].orEmpty()
        assertTrue(ranges.contains(":"))
        assertTrue(ranges.contains("|"))
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_progress_milestones_notes_and_task_styles() {
        val parser = PlantUmlGanttParser()
        """
        Project starts 2024-01-01
        [Design] lasts 3 days
        [Design] is 40% complete
        [Design] is critical
        note bottom of [Design] : review pending
        [Release] happens at 2024-01-05
        [Release] is milestone
        [Audit] lasts 1 day
        [Audit] is dashed
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        val design = ir.items.single { it.id.value == "design" }
        val release = ir.items.single { it.id.value == "release" }
        val audit = ir.items.single { it.id.value == "audit" }
        assertEquals("40", design.payload["gantt.progress"])
        assertEquals("critical", design.payload["gantt.style"])
        assertEquals("review pending", design.payload["gantt.note"])
        assertEquals("milestone", release.payload["gantt.kind"])
        assertEquals("dashed", audit.payload["gantt.style"])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun infers_task_end_from_working_calendar() {
        val parser = PlantUmlGanttParser()
        """
        Project starts 2024-01-05
        saturday are closed
        sunday are closed
        [Design] starts 2024-01-05
        [Design] lasts 2 days
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        val design = ir.items.single { it.id.value == "design" }
        assertEquals("2", design.payload["gantt.workingDays"])
        assertEquals(4 * PlantUmlTemporalSupport.MS_PER_DAY, design.range.endMs - design.range.startMs)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }
}

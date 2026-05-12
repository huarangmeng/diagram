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
}

package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.TimeSeriesIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlTimingParserTest {
    @Test
    fun parses_tracks_time_markers_and_state_segments() {
        val parser = PlantUmlTimingParser()
        """
        binary "Enable" as EN
        concise "Mode" as M
        @0
        EN is low
        M is Idle
        @100
        EN is high
        M is Busy
        @250
        EN is low
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertEquals(2, ir.tracks.size)
        assertEquals("compact", ir.styleHints.extras["gantt.displayMode"])
        assertTrue(ir.items.size >= 5)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
        assertTrue(ir.items.any { it.label.toString().contains("Busy") || it.payload["timing.state"] == "Busy" })
        assertTrue(ir.range.endMs >= 251L)
    }

    @Test
    fun parses_messages_and_constraints() {
        val parser = PlantUmlTimingParser()
        """
        binary "Request" as REQ
        robust "Response" as RES
        @0
        REQ is low
        RES is idle
        @50
        REQ -> RES : send
        @60 <-> @120 : max latency
        @100
        RES is busy
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertTrue(ir.items.any { it.payload["timing.kind"] == "message" && it.payload["timing.from"] == "REQ" && it.payload["timing.to"] == "RES" })
        assertTrue(ir.items.any { it.payload["timing.kind"] == "constraint" && it.label.toString().contains("max latency") })
        assertTrue(ir.tracks.any { it.id.value == "timing:track:constraints" })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_scale_and_track_kinds_for_waveform_rendering() {
        val parser = PlantUmlTimingParser()
        """
        scale 50 as 25 pixels
        clock "Clock" as CLK
        binary "Enable" as EN
        @0
        CLK is low
        EN is low
        @50
        CLK is high
        EN is high
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertEquals("50", ir.styleHints.extras["timing.scaleMs"])
        assertEquals("25 pixels", ir.styleHints.extras["timing.scaleLabel"])
        assertTrue(ir.items.any { it.payload["timing.trackKind"] == "clock" })
        assertTrue(ir.items.any { it.payload["timing.trackKind"] == "binary" })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun auto_generates_clock_segments_from_period_when_no_explicit_states_exist() {
        val parser = PlantUmlTimingParser()
        """
        scale 50
        clock "Clock" as CLK with period 50
        @200
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        val clock = ir.items.filter { it.trackId.value == "timing:track:CLK" }
        assertEquals(listOf("low", "high", "low", "high"), clock.map { it.payload["timing.state"] })
        assertEquals(listOf(0L, 50L, 100L, 150L), clock.map { it.range.startMs })
        assertEquals("50", clock.first().payload["timing.clockPeriodMs"])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun auto_generates_clock_segments_with_duty_cycle() {
        val parser = PlantUmlTimingParser()
        """
        clock "Clock" as CLK with period 100 duty 25%
        @200
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        val clock = ir.items.filter { it.trackId.value == "timing:track:CLK" }
        assertEquals(listOf("low", "high", "low", "high"), clock.map { it.payload["timing.state"] })
        assertEquals(listOf(0L, 75L, 100L, 175L), clock.map { it.range.startMs })
        assertEquals(listOf(75L, 100L, 175L, 200L), clock.map { it.range.endMs })
        assertEquals("25.0", clock.first().payload["timing.clockDutyPercent"])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_relative_time_markers_clock_offset_time_labels_and_hidden_axis() {
        val parser = PlantUmlTimingParser()
        """
        hide time-axis
        clock "Clock" as CLK with period 100 duty 25% offset 10
        concise "Mode" as M
        @0 : boot
        M is Idle
        @+50
        M is Busy
        @210
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        assertEquals("true", ir.styleHints.extras["timing.hideAxis"])
        assertTrue(ir.items.any { it.payload["timing.kind"] == "timeLabel" && it.label.toString().contains("boot") })
        val mode = ir.items.filter { it.trackId.value == "timing:track:M" }
        assertEquals(0L, mode[0].range.startMs)
        assertEquals(50L, mode[0].range.endMs)
        assertEquals(50L, mode[1].range.startMs)
        val clock = ir.items.filter { it.trackId.value == "timing:track:CLK" }
        assertEquals(listOf(0L, 10L, 35L, 110L, 135L), clock.take(5).map { it.range.startMs })
        assertEquals("10", clock.first().payload["timing.clockOffsetMs"])
        assertTrue(ir.tracks.any { it.id.value == "timing:track:events" })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun marks_robust_segments_with_track_kind() {
        val parser = PlantUmlTimingParser()
        """
        robust "Response" as RES
        @0
        RES is idle
        @100
        RES is busy
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        val robust = ir.items.filter { it.trackId.value == "timing:track:RES" }
        assertTrue(robust.isNotEmpty())
        assertTrue(robust.all { it.payload["timing.trackKind"] == "robust" })
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_state_display_text_and_boundary_style_hints() {
        val parser = PlantUmlTimingParser()
        """
        concise "Mode" as M
        @0
        M is Idle : Waiting
        @50
        M is Busy : Processing <<dashed>>
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<TimeSeriesIR>(parser.snapshot())
        val mode = ir.items.filter { it.trackId.value == "timing:track:M" }
        assertEquals("Idle", mode[0].payload["timing.state"])
        assertEquals("Waiting", mode[0].payload["timing.text"])
        assertEquals("Busy", mode[1].payload["timing.state"])
        assertEquals("Processing", mode[1].payload["timing.text"])
        assertEquals("dashed", mode[1].payload["timing.boundary"])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }
}

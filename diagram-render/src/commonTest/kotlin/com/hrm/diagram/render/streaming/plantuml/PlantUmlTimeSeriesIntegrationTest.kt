package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlTimeSeriesIntegrationTest {
    @Test
    fun startgantt_renders_and_is_streaming_consistent() {
        val src =
            """
            @startgantt
            Project starts 2024-01-01
            -- Planning --
            [Design] starts 2024-01-01
            [Design] lasts 3 days
            [Build] lasts 5 days
            [Design] -> [Build]
            @endgantt
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(2, oneIr.items.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Design" })
    }

    @Test
    fun startgantt_resources_and_colors_render_and_are_streaming_consistent() {
        val src =
            """
            @startgantt
            Project starts 2024-01-01
            [Design] lasts 3 days on {Alice}
            [Design] is colored in #FF8800
            [Build] lasts 2 days on {Bob}
            [Build] is colored in Green
            @endgantt
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(oneIr.tracks.any { it.label.toString().contains("Resource: Alice") })
        assertEquals("#FF8800", oneIr.items.single { it.id.value == "design" }.payload["gantt.color"])
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == 0xFFFF8800.toInt() })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Resource: Alice" })
    }

    @Test
    fun timing_inside_startuml_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
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
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("timing", oneIr.styleHints.extras["plantuml.timeseries.kind"])
        assertTrue(oneIr.items.size >= 5)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Busy" })
    }

    @Test
    fun timing_messages_and_constraints_render_and_are_streaming_consistent() {
        val src =
            """
            @startuml
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
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(oneIr.items.any { it.payload["timing.kind"] == "message" })
        assertTrue(oneIr.items.any { it.payload["timing.kind"] == "constraint" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawArrow>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "send" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "max latency" })
    }

    @Test
    fun timing_scale_and_waveform_render_and_are_streaming_consistent() {
        val src =
            """
            @startuml
            scale 50 as 25 pixels
            clock "Clock" as CLK
            binary "Enable" as EN
            @0
            CLK is low
            EN is low
            @50
            CLK is high
            EN is high
            @100
            CLK is low
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("50", oneIr.styleHints.extras["timing.scaleMs"])
        assertTrue(oneIr.items.any { it.payload["timing.trackKind"] == "clock" })
        assertTrue(oneIr.items.any { it.payload["timing.trackKind"] == "binary" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.dash == listOf(3f, 4f) })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "50 (25 pixels)" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 2f })
    }

    @Test
    fun timing_clock_period_auto_flips_and_renders_waveform_consistently() {
        val src =
            """
            @startuml
            scale 50
            clock "Clock" as CLK with period 50
            @200
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val clock = oneIr.items.filter { it.trackId.value == "timing:track:CLK" }
        assertEquals(listOf("low", "high", "low", "high"), clock.map { it.payload["timing.state"] })
        assertEquals("50", clock.first().payload["timing.clockPeriodMs"])
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 2f })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "150" })
    }

    @Test
    fun timing_clock_duty_cycle_renders_as_asymmetric_waveform_consistently() {
        val src =
            """
            @startuml
            clock "Clock" as CLK with period 100 duty 25%
            @200
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val clock = oneIr.items.filter { it.trackId.value == "timing:track:CLK" }
        assertEquals(listOf(0L, 75L, 100L, 175L), clock.map { it.range.startMs })
        assertEquals("25.0", clock.first().payload["timing.clockDutyPercent"])
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 2f })
    }

    @Test
    fun timing_relative_markers_clock_offset_time_labels_and_hidden_axis_render_consistently() {
        val src =
            """
            @startuml
            hide time-axis
            clock "Clock" as CLK with period 100 duty 25% offset 10
            concise "Mode" as M
            @0 : boot
            M is Idle
            @+50
            M is Busy
            @210
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 8)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("true", oneIr.styleHints.extras["timing.hideAxis"])
        assertTrue(oneIr.items.any { it.payload["timing.kind"] == "timeLabel" && it.label.toString().contains("boot") })
        assertEquals(listOf(0L, 10L, 35L), oneIr.items.filter { it.trackId.value == "timing:track:CLK" }.take(3).map { it.range.startMs })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "boot" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.dash == listOf(2f, 3f) })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().none { it.color.argb == 0xFFB0BEC5.toInt() })
    }

    @Test
    fun timing_robust_states_render_with_dedicated_visuals_consistently() {
        val src =
            """
            @startuml
            robust "Response" as RES
            @0
            RES is idle
            @100
            RES is busy
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(oneIr.items.any { it.payload["timing.trackKind"] == "robust" && it.payload["timing.state"] == "idle" })
        assertTrue(oneIr.items.any { it.payload["timing.trackKind"] == "robust" && it.payload["timing.state"] == "busy" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().any { it.stroke.width == 1.8f && it.corner == 8f })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 1.4f })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "busy" })
    }

    @Test
    fun timing_concise_states_render_as_continuous_single_lane_band() {
        val src =
            """
            @startuml
            concise "Mode" as M
            @0
            M is Idle
            @50
            M is Busy
            @100
            M is Done
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("compact", oneIr.styleHints.extras["gantt.displayMode"])
        val concise = oneIr.items.filter { it.trackId.value == "timing:track:M" }
        assertTrue(concise.all { it.payload["timing.trackKind"] == "concise" })
        val barTops = concise.mapNotNull { item -> one.laidOut?.nodePositions?.get(com.hrm.diagram.core.ir.NodeId("gantt:item:${item.id.value}"))?.top }.distinct()
        assertEquals(1, barTops.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 1.4f && it.color.argb == 0xFFFFFFFF.toInt() })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Busy" })
    }

    @Test
    fun timing_concise_state_text_and_boundary_style_render_consistently() {
        val src =
            """
            @startuml
            concise "Mode" as M
            @0
            M is Idle : Waiting
            @50
            M is Busy : Processing <<dashed>>
            @100
            M is Done : Complete <<thick>>
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val mode = oneIr.items.filter { it.trackId.value == "timing:track:M" }
        assertEquals("Busy", mode[1].payload["timing.state"])
        assertEquals("Processing", mode[1].payload["timing.text"])
        assertEquals("dashed", mode[1].payload["timing.boundary"])
        assertEquals("thick", mode[2].payload["timing.boundary"])
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Processing" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.dash == listOf(3f, 2f) })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().any { it.stroke.width == 2.2f })
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.PLANTUML).let { s ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            s.finish()
        } finally {
            s.close()
        }
    }
}

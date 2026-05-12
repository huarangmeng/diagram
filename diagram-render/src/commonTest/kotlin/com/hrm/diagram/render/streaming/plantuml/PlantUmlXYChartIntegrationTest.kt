package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlXYChartIntegrationTest {
    @Test
    fun startbar_renders_with_streaming_consistency() {
        val src =
            """
            @startbar
            title Sales
            y-axis 0 --> 100
            Jan : 30
            Feb : 45
            Mar : 20
            @endbar
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<XYChartIR>(one.ir)
        val chunkedIr = assertIs<XYChartIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(SourceLanguage.PLANTUML, oneIr.sourceLanguage)
        assertEquals("Sales", oneIr.title)
        assertEquals(SeriesKind.Bar, oneIr.series.single().kind)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Jan" })
    }

    @Test
    fun startline_renders_with_streaming_consistency() {
        val src =
            """
            @startline
            title Trend
            x-axis [Q1, Q2, Q3]
            line [12, 18, 15]
            @endline
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<XYChartIR>(one.ir)
        val chunkedIr = assertIs<XYChartIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(SeriesKind.Line, oneIr.series.single().kind)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Q2" })
    }

    @Test
    fun startuml_bar_cue_renders_with_streaming_consistency() {
        val src =
            """
            @startuml
            bar
            title Votes
            Yes : 70
            No : 30
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<XYChartIR>(one.ir)
        val chunkedIr = assertIs<XYChartIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(SeriesKind.Bar, oneIr.series.single().kind)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun startscatter_renders_with_streaming_consistency() {
        val src =
            """
            @startscatter
            title Samples
            x-axis [A, B, C]
            scatter [4, 9, 16]
            @endscatter
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<XYChartIR>(one.ir)
        val chunkedIr = assertIs<XYChartIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(SourceLanguage.PLANTUML, oneIr.sourceLanguage)
        assertEquals("Samples", oneIr.title)
        assertEquals(SeriesKind.Scatter, oneIr.series.single().kind)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "B" })
    }

    @Test
    fun startuml_scatter_cue_renders_with_streaming_consistency() {
        val src =
            """
            @startuml
            scatter
            title Scores
            Low : 2
            Mid : 5
            High : 9
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<XYChartIR>(one.ir)
        val chunkedIr = assertIs<XYChartIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(SeriesKind.Scatter, oneIr.series.single().kind)
        assertEquals(listOf("Low", "Mid", "High"), oneIr.xAxis.categories)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.PLANTUML).let { session ->
        try {
            var index = 0
            while (index < src.length) {
                val end = (index + chunkSize).coerceAtMost(src.length)
                session.append(src.substring(index, end))
                index = end
            }
            session.finish()
        } finally {
            session.close()
        }
    }
}

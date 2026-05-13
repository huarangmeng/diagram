package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.XYChartIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlXYChartParserTest {
    @Test
    fun parses_bar_category_rows() {
        val parser = PlantUmlXYChartParser(SeriesKind.Bar)
        """
        title Sales
        y-axis 0 --> 100
        Jan : 30
        Feb : 45
        Mar : 20
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<XYChartIR>(parser.snapshot())
        assertEquals("Sales", ir.title)
        assertEquals(listOf("Jan", "Feb", "Mar"), ir.xAxis.categories)
        assertEquals(1, ir.series.size)
        assertEquals(SeriesKind.Bar, ir.series[0].kind)
        assertEquals(listOf(30.0, 45.0, 20.0), ir.series[0].ys)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_line_array_series() {
        val parser = PlantUmlXYChartParser(SeriesKind.Line)
        """
        title Trend
        x-axis [Q1, Q2, Q3]
        line [12, 18, 15]
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<XYChartIR>(parser.snapshot())
        assertEquals(listOf("Q1", "Q2", "Q3"), ir.xAxis.categories)
        assertEquals(1, ir.series.size)
        assertEquals(SeriesKind.Line, ir.series[0].kind)
        assertEquals(listOf(12.0, 18.0, 15.0), ir.series[0].ys)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_scatter_array_series() {
        val parser = PlantUmlXYChartParser(SeriesKind.Scatter)
        """
        title Samples
        x-axis [A, B, C]
        scatter [4, 9, 16]
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<XYChartIR>(parser.snapshot())
        assertEquals("Samples", ir.title)
        assertEquals(listOf("A", "B", "C"), ir.xAxis.categories)
        assertEquals(1, ir.series.size)
        assertEquals(SeriesKind.Scatter, ir.series[0].kind)
        assertEquals(listOf(4.0, 9.0, 16.0), ir.series[0].ys)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun parses_chart_axis_aliases_named_series_colors_and_coordinate_pairs() {
        val parser = PlantUmlXYChartParser(SeriesKind.Line)
        """
        skinparam chart {
          BackgroundColor White
          FontColor Navy
          BorderColor Gray
        }
        h-axis "t" -10 --> 10 spacing 2
        v-axis "f(t)" -10 --> 50 spacing 10
        line "Trajectory" [(-10,0), (2,10), (5,30)] #3498db
        scatter "Checkpoints" [(1,12), (6,34)] #e74c3c
        legend right
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<XYChartIR>(parser.snapshot())
        assertEquals("t", (ir.xAxis.title as com.hrm.diagram.core.ir.RichLabel.Plain).text)
        assertEquals(-10.0, ir.xAxis.min)
        assertEquals(10.0, ir.xAxis.max)
        assertEquals("f(t)", (ir.yAxis.title as com.hrm.diagram.core.ir.RichLabel.Plain).text)
        assertEquals(-10.0, ir.yAxis.min)
        assertEquals(50.0, ir.yAxis.max)
        assertEquals(2, ir.series.size)
        assertEquals("Trajectory", ir.series[0].name)
        assertEquals(listOf(-10.0, 2.0, 5.0), ir.series[0].xs)
        assertEquals(listOf(0.0, 10.0, 30.0), ir.series[0].ys)
        assertEquals(SeriesKind.Scatter, ir.series[1].kind)
        assertEquals("White", ir.styleHints.extras[PlantUmlXYChartParser.STYLE_BACKGROUND_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlXYChartParser.STYLE_TEXT_KEY])
        assertEquals("Gray", ir.styleHints.extras[PlantUmlXYChartParser.STYLE_AXIS_COLOR_KEY])
        assertEquals("#3498db", ir.styleHints.extras["${PlantUmlXYChartParser.STYLE_SERIES_COLOR_PREFIX}0"])
        assertEquals("#e74c3c", ir.styleHints.extras["${PlantUmlXYChartParser.STYLE_SERIES_COLOR_PREFIX}1"])
        assertEquals("right", ir.styleHints.extras[PlantUmlXYChartParser.STYLE_LEGEND_KEY])
        assertEquals("10", ir.styleHints.extras[PlantUmlXYChartParser.STYLE_Y_SPACING_KEY])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }
}

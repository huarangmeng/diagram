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
}

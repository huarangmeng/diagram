package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlPieParserTest {
    @Test
    fun parses_title_and_slices() {
        val parser = PlantUmlPieParser()
        """
        title Pets
        "Dogs" : 42
        Cats : 28
        Birds : 10.5
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<PieIR>(parser.snapshot())
        assertEquals("Pets", ir.title)
        assertEquals(3, ir.slices.size)
        assertEquals("Dogs", labelOf(ir.slices[0].label))
        assertEquals(42.0, ir.slices[0].value)
        assertEquals("Cats", labelOf(ir.slices[1].label))
        assertEquals(28.0, ir.slices[1].value)
        assertEquals("Birds", labelOf(ir.slices[2].label))
        assertEquals(10.5, ir.slices[2].value)
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    @Test
    fun reports_invalid_slice_lines() {
        val parser = PlantUmlPieParser()
        parser.acceptLine("Dogs = 42")

        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E021" })
    }

    @Test
    fun parses_colors_legend_and_skinparam_style_hints() {
        val parser = PlantUmlPieParser()
        """
        skinparam pie {
          BackgroundColor Ivory
          BorderColor Gray
          FontColor Navy
          LineThickness 2
        }
        legend left
        #3498db; "Dogs" : 42
        Cats : 28 #2ecc71
        Birds : 10%
        """.trimIndent().lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed = true)

        val ir = assertIs<PieIR>(parser.snapshot())
        assertEquals(3, ir.slices.size)
        assertEquals(10.0, ir.slices[2].value)
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlPieParser.STYLE_BACKGROUND_KEY])
        assertEquals("Gray", ir.styleHints.extras[PlantUmlPieParser.STYLE_BORDER_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlPieParser.STYLE_TEXT_KEY])
        assertEquals("2", ir.styleHints.extras[PlantUmlPieParser.STYLE_LINE_THICKNESS_KEY])
        assertEquals("left", ir.styleHints.extras[PlantUmlPieParser.STYLE_LEGEND_KEY])
        assertEquals("#3498db", ir.styleHints.extras["${PlantUmlPieParser.STYLE_SLICE_COLOR_PREFIX}0"])
        assertEquals("#2ecc71", ir.styleHints.extras["${PlantUmlPieParser.STYLE_SLICE_COLOR_PREFIX}1"])
        assertTrue(parser.diagnosticsSnapshot().isEmpty())
    }

    private fun labelOf(label: RichLabel): String =
        when (label) {
            is RichLabel.Plain -> label.text
            is RichLabel.Markdown -> label.source
            is RichLabel.Html -> label.html
        }
}

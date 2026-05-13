package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlPieIntegrationTest {
    @Test
    fun startpie_renders_with_streaming_consistency() {
        val src =
            """
            @startpie
            title Pets
            "Dogs" : 42
            "Cats" : 28
            "Birds" : 10
            @endpie
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<PieIR>(one.ir)
        val chunkedIr = assertIs<PieIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(SourceLanguage.PLANTUML, oneIr.sourceLanguage)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("Pets", oneIr.title)
        assertEquals(3, oneIr.slices.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillPath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Dogs" })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "42" })
    }

    @Test
    fun startuml_pie_cue_renders_with_streaming_consistency() {
        val src =
            """
            @startuml
            pie
            title Votes
            Yes : 70
            No : 30
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<PieIR>(one.ir)
        val chunkedIr = assertIs<PieIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("Votes", oneIr.title)
        assertEquals(2, oneIr.slices.size)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Yes" })
    }

    @Test
    fun startpie_renders_styled_colored_slices_with_streaming_consistency() {
        val src =
            """
            @startpie
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
            @endpie
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<PieIR>(one.ir)
        val chunkedIr = assertIs<PieIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertEquals(3, oneIr.slices.size)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillPath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "Dogs" })
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

package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlC4IntegrationTest {
    @Test
    fun c4_plantuml_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            !include <C4/C4_Container>
            C4Container
            Person(u, "User", "Customer")
            Container_Boundary(app, "Application") {
              Container(api, "API", "Ktor", "Backend")
              ContainerDb(db, "Database", "PostgreSQL", "Stores data")
            }
            Rel(u, api, "uses", "HTTPS")
            Rel_Back(db, api, "reads")
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 7)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(3, oneIr.nodes.size)
        assertEquals(2, oneIr.edges.size)
        assertEquals(1, oneIr.clusters.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("API") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Application") })
    }

    @Test
    fun c4_style_macros_render_and_are_streaming_consistent() {
        val src =
            """
            @startuml
            C4Container
            AddRelTag("async", ${'$'}lineColor="#8E24AA", ${'$'}lineStyle="DashedLine()")
            Person(user, "User")
            Container(api, "API", "Ktor", "Backend")
            Rel(user, api, "Uses", "HTTPS", ${'$'}tags="async")
            UpdateElementStyle(api, ${'$'}bgColor="#FFF3E0", ${'$'}fontColor="#123456", ${'$'}borderColor="#333333")
            UpdateRelStyle(user, api, ${'$'}lineColor="#111111", ${'$'}lineStyle="BoldLine()")
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(0xFFFFF3E0.toInt(), oneIr.nodes.single { it.id.value == "api" }.style.fill?.argb)
        assertEquals(0xFF111111.toInt(), oneIr.edges.single().style.color?.argb)
        assertEquals(2.5f, oneIr.edges.single().style.width)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Uses") })
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

package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlObjectParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlObjectIntegrationTest {
    @Test
    fun object_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            object Order {
              id = 1
              total = 99
            }
            object Customer
            Order --> Customer : owner
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun object_members_affect_rendering() {
        val snapshot = run(
            """
            @startuml
            object Order {
              id = 1
              total = 99
            }
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.nodes.size)
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun object_relations_yield_routes() {
        val snapshot = run(
            """
            @startuml
            object A
            object B
            A ..> B : ref
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.edges.size)
        assertEquals(1, snapshot.laidOut!!.edgeRoutes.size)
    }

    @Test
    fun object_note_package_and_map_json_render_consistently() {
        val src =
            """
            @startuml
            package "Domain" {
              namespace "Orders" {
                object Order {
                  id = 1
                }
                map Cache {
                  key => value
                }
                json Payload {
                  orderId: 1
                }
                note right of Order
                  aggregate
                  root
                end note
              }
            }
            Order --> Cache
            Payload ..> Order
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(oneIr.clusters.isNotEmpty())
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertTrue(one.laidOut!!.clusterRects.isNotEmpty())
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "note" })
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "map" })
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "json" })
    }

    @Test
    fun object_skinparam_styles_render_consistently() {
        val src =
            """
            @startuml
            skinparam object {
              BackgroundColor LightYellow
              BorderColor Orange
              FontColor Navy
              FontSize 17
              FontName monospace
              LineThickness 2.5
              Shadowing true
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Peru
              FontColor Red
              FontSize 15
              FontName serif
              LineThickness 2
              Shadowing true
            }
            skinparam package {
              BackgroundColor LightGray
              BorderColor Silver
              FontColor Green
              FontSize 16
              FontName sans-serif
              LineThickness 2.25
              Shadowing true
            }
            skinparam ArrowColor Blue
            package Domain {
              object Order
              note right of Order : aggregate
            }
            Order --> Order : self
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        val fillRects = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>().map { it.color.argb }
        val strokeRects = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokeRect>()
        val strokePaths = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokePath>()
        val texts = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        val textColors = texts.map { it.color.argb }
        val textFamilies = texts.map { it.font.family }
        val textSizes = texts.map { it.font.sizeSp }
        assertTrue(fillRects.contains(0xFFD3D3D3.toInt()))
        assertTrue(fillRects.contains(0xFFFFFFE0.toInt()))
        assertTrue(fillRects.contains(0xFFFFFFF0.toInt()))
        assertTrue(fillRects.contains(0x26000000))
        assertTrue(strokeRects.any { it.color.argb == 0xFFC0C0C0.toInt() && it.stroke.width == 2.25f })
        assertTrue(strokeRects.any { it.color.argb == 0xFFFFA500.toInt() && it.stroke.width == 2.5f })
        assertTrue(strokeRects.any { it.color.argb == 0xFFCD853F.toInt() && it.stroke.width == 2f })
        assertTrue(strokePaths.any { it.color.argb == 0xFF0000FF.toInt() })
        assertTrue(textColors.contains(0xFF008000.toInt()))
        assertTrue(textColors.contains(0xFF000080.toInt()))
        assertTrue(textColors.contains(0xFFFF0000.toInt()))
        assertTrue(textFamilies.contains("monospace"))
        assertTrue(textFamilies.contains("serif"))
        assertTrue(textFamilies.contains("sans-serif"))
        assertTrue(textSizes.contains(17f))
        assertTrue(textSizes.contains(15f))
        assertTrue(textSizes.contains(16f))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
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

package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlUsecaseParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlUsecaseIntegrationTest {
    @Test
    fun usecase_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            rectangle Checkout {
              actor User
              (Pay) as PayUc
            }
            User --> PayUc : starts
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun usecase_clusters_produce_cluster_rects() {
        val snapshot = run(
            """
            @startuml
            package Portal {
              rectangle Auth {
                actor User
                usecase Login
              }
            }
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.clusters.size)
        assertTrue(snapshot.laidOut!!.clusterRects.isNotEmpty())
    }

    @Test
    fun actor_and_usecase_edges_yield_routes() {
        val snapshot = run(
            """
            @startuml
            actor User
            (Checkout) as CheckoutUc
            User .> CheckoutUc : include
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.edges.size)
        assertEquals(1, snapshot.laidOut!!.edgeRoutes.size)
        assertEquals("<<include>>", (ir.edges.single().label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text)
    }

    @Test
    fun anchored_note_and_extend_relation_render_consistently() {
        val src =
            """
            @startuml
            note right of (Checkout) : highlighted before declarations
            actor User
            (Checkout) as CheckoutUc
            (OptionalAuth) <. (Checkout) : extends
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        val note = oneIr.nodes.first { it.payload[PlantUmlUsecaseParser.KIND_KEY] == "note" }
        val laidOut = assertNotNull(one.laidOut)
        val noteRect = laidOut.nodePositions.getValue(note.id)
        val targetRect = laidOut.nodePositions.getValue(NodeId("CheckoutUc"))
        assertTrue(noteRect.left >= targetRect.right, "usecase note should stay to the right of CheckoutUc")
        assertEquals("<<extend>>", (oneIr.edges.last().label as? com.hrm.diagram.core.ir.RichLabel.Plain)?.text)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun business_actor_variants_render_body_slash() {
        val snapshot = run(
            """
            @startuml
            actor/ "Customer" as User
            :Back Office:/ as Staff
            (Checkout) as CheckoutUc
            User --> CheckoutUc
            Staff --> CheckoutUc
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 4,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals("business", ir.nodes.first { it.id == NodeId("User") }.payload[PlantUmlUsecaseParser.ACTOR_VARIANT_KEY])
        assertEquals("business", ir.nodes.first { it.id == NodeId("Staff") }.payload[PlantUmlUsecaseParser.ACTOR_VARIANT_KEY])
        val slashPaths = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>().count { cmd ->
            cmd.path.ops.size == 2 &&
                cmd.path.ops[0] is PathOp.MoveTo &&
                cmd.path.ops[1] is PathOp.LineTo
        }
        assertTrue(slashPaths >= 2, "business actors should add body slash strokes")
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
    }

    @Test
    fun usecase_skinparam_styles_render_consistently() {
        val src =
            """
            @startuml
            skinparam actor {
              BackgroundColor Ivory
              BorderColor Navy
              FontColor SaddleBrown
              FontSize 16
              FontName monospace
              LineThickness 2.5
              Shadowing true
            }
            skinparam usecase {
              BackgroundColor LightYellow
              BorderColor Orange
              FontColor Navy
              FontSize 18
              FontName serif
              LineThickness 3
              Shadowing true
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Orange
              FontColor Navy
              FontSize 14
              FontName cursive
              LineThickness 2
              Shadowing true
            }
            skinparam rectangle {
              BackgroundColor PaleGreen
              BorderColor Green
              FontSize 15
              FontName fantasy
              LineThickness 2.25
              Shadowing true
            }
            skinparam package {
              BackgroundColor LightGray
              BorderColor Silver
              FontSize 13
              FontName system-ui
              LineThickness 1.75
              Shadowing true
            }
            skinparam ArrowColor Peru
            package Portal {
              rectangle Auth {
                actor/ "Customer" as User
                (Checkout) as CheckoutUc
                note right of CheckoutUc : highlighted
              }
            }
            User --> CheckoutUc : starts
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 5)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val fillRects = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        val fillPaths = one.drawCommands.filterIsInstance<DrawCommand.FillPath>().map { it.color.argb }
        val strokeRects = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().map { it.color.argb }
        val strokePaths = one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().map { it.color.argb }
        val textColors = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.color.argb }
        val textFamilies = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.font.family }
        val textSizes = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.font.sizeSp }
        val shadowRects = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        val strokeWidths = one.drawCommands
            .filterIsInstance<DrawCommand.StrokeRect>()
            .map { it.stroke.width } + one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().map { it.stroke.width }
        assertTrue(fillRects.contains(0xFFD3D3D3.toInt()), "expected package background color LightGray")
        assertTrue(fillRects.contains(0xFF98FB98.toInt()), "expected rectangle background color PaleGreen")
        assertTrue(fillRects.contains(0xFFFFFFF0.toInt()), "expected actor/note background color Ivory")
        assertTrue(fillPaths.contains(0xFFFFFFE0.toInt()), "expected usecase background color LightYellow")
        assertTrue(strokeRects.contains(0xFFC0C0C0.toInt()), "expected package border color Silver")
        assertTrue(strokeRects.contains(0xFF000080.toInt()), "expected actor border color Navy")
        assertTrue(strokeRects.contains(0xFF008000.toInt()), "expected rectangle border color Green")
        assertTrue(strokePaths.contains(0xFFCD853F.toInt()), "expected arrow color Peru")
        assertTrue(textColors.contains(0xFF8B4513.toInt()), "expected actor text color SaddleBrown")
        assertTrue(textColors.contains(0xFF000080.toInt()), "expected usecase/note text color Navy")
        assertTrue(textFamilies.contains("monospace"))
        assertTrue(textFamilies.contains("serif"))
        assertTrue(textFamilies.contains("cursive"))
        assertTrue(textFamilies.contains("fantasy") || textFamilies.contains("system-ui"))
        assertTrue(textSizes.contains(16f))
        assertTrue(textSizes.contains(18f))
        assertTrue(textSizes.contains(14f))
        assertTrue(shadowRects.contains(0x26000000))
        assertTrue(strokeWidths.any { it == 2.5f })
        assertTrue(strokeWidths.any { it == 3f })
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

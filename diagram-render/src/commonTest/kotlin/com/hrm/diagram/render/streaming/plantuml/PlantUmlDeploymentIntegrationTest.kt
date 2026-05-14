package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlDeploymentParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlDeploymentIntegrationTest {
    @Test
    fun deployment_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            node Server {
              [App]
            }
            database MainDb
            App --> MainDb : jdbc
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
    fun cloud_and_database_generate_layout_artifacts() {
        val snapshot = run(
            """
            @startuml
            cloud Aws {
              node Runtime {
                [App]
              }
            }
            database MainDb
            App ..> MainDb : read
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertTrue(ir.clusters.isNotEmpty())
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun artifact_only_inside_node_uses_deployment_dispatcher() {
        val snapshot = run(
            """
            @startuml
            node Server {
              [App]
            }
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<GraphIR>(snapshot.ir)
        assertEquals(1, ir.clusters.size)
        assertEquals(1, ir.nodes.size)
        val texts = snapshot.drawCommands
            .filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
            .map { it.text }
        val dashedRects = snapshot.drawCommands
            .filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokeRect>()
            .filter { it.stroke.dash != null }
        assertTrue(texts.contains("Server"), "deployment node container should render the plain node title: $texts")
        assertTrue(texts.none { it.contains("NODE") }, "deployment node container should not render a generic cluster chip: $texts")
        assertTrue(dashedRects.isEmpty(), "deployment node container should use a solid PlantUML node outline: $dashedRects")
        val laidOut = assertNotNull(snapshot.laidOut)
        val clusterRect = laidOut.clusterRects.values.single()
        assertTrue(clusterRect.top - laidOut.bounds.top >= 12f, "deployment node container should not be clipped by the viewport: cluster=$clusterRect bounds=${laidOut.bounds}")
    }

    @Test
    fun deployment_actor_queue_storage_and_note_render_consistently() {
        val src =
            """
            @startuml
            node Server {
              actor Ops
              queue Jobs
              storage Disk
              [App]
              note right of App
                deployed by ops
              end note
            }
            Ops --> App : deploy
            App --> Jobs : enqueue
            App --> Disk : write
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<GraphIR>(one.ir)
        val chunkedIr = assertIs<GraphIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlDeploymentParser.KIND_KEY] == "actor" })
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlDeploymentParser.KIND_KEY] == "queue" })
        assertTrue(oneIr.nodes.any { it.payload[PlantUmlDeploymentParser.KIND_KEY] == "storage" })
        val note = oneIr.nodes.first { it.payload[PlantUmlDeploymentParser.KIND_KEY] == "note" }
        val laidOut = assertNotNull(one.laidOut)
        val appRect = laidOut.nodePositions.getValue(com.hrm.diagram.core.ir.NodeId("App"))
        val noteRect = laidOut.nodePositions.getValue(note.id)
        assertTrue(noteRect.left >= appRect.right, "deployment note should stay on the right side of App")
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun deployment_skinparam_styles_render_consistently() {
        val src =
            """
            @startuml
            skinparam artifact {
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
            skinparam node {
              BackgroundColor LightGray
              BorderColor Silver
              FontColor Green
              FontSize 16
              FontName sans-serif
              LineThickness 2.25
              Shadowing true
            }
            skinparam ArrowColor Blue
            node Server {
              [App]
              note right of App : deploy
            }
            App --> App : self
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
        assertTrue(fillRects.contains(0xFFD3D3D3.toInt()), "fills=$fillRects")
        assertTrue(fillRects.contains(0xFFFFFFE0.toInt()), "fills=$fillRects")
        assertTrue(fillRects.contains(0xFFFFFFF0.toInt()), "fills=$fillRects")
        assertTrue(fillRects.contains(0x26000000), "fills=$fillRects")
        assertTrue(strokeRects.any { it.color.argb == 0xFFC0C0C0.toInt() && it.stroke.width == 2.25f }, "strokeRects=$strokeRects")
        assertTrue(strokeRects.any { it.color.argb == 0xFFFFA500.toInt() && it.stroke.width == 2.5f }, "strokeRects=$strokeRects")
        assertTrue(strokeRects.any { it.color.argb == 0xFFCD853F.toInt() && it.stroke.width == 2f }, "strokeRects=$strokeRects")
        assertTrue(strokePaths.any { it.color.argb == 0xFF0000FF.toInt() }, "strokePaths=$strokePaths")
        assertTrue(textColors.contains(0xFF008000.toInt()), "textColors=$textColors")
        assertTrue(textColors.contains(0xFF000080.toInt()), "textColors=$textColors")
        assertTrue(textColors.contains(0xFFFF0000.toInt()), "textColors=$textColors")
        assertTrue(textFamilies.contains("monospace"), "families=$textFamilies")
        assertTrue(textFamilies.contains("serif"), "families=$textFamilies")
        assertTrue(textFamilies.contains("sans-serif"), "families=$textFamilies")
        assertTrue(textSizes.contains(17f), "sizes=$textSizes")
        assertTrue(textSizes.contains(15f), "sizes=$textSizes")
        assertTrue(textSizes.contains(16f), "sizes=$textSizes")
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

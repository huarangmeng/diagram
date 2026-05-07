package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.parser.plantuml.PlantUmlStateParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlStateIntegrationTest {
    @Test
    fun state_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            state Active {
              [*] --> Loading
              Loading --> Ready : load [ok] / log
            }
            note right of Active : lifecycle
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 3)
        val oneIr = one.ir as? StateIR
        val chunkedIr = chunked.ir as? StateIR
        assertNotNull(oneIr)
        assertNotNull(chunkedIr)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun state_pseudo_nodes_render() {
        val snap = run(
            """
            @startuml
            [*] --> A
            A --> [*]
            @enduml
            """.trimIndent() + "\n",
            4,
        )
        val ir = snap.ir as? StateIR
        assertNotNull(ir)
        assertTrue(ir.states.any { it.kind == StateKind.Initial })
        assertTrue(ir.states.any { it.kind == StateKind.Final })
        assertEquals(2, snap.laidOut?.edgeRoutes?.size)
    }

    @Test
    fun history_and_note_generate_layout_artifacts() {
        val snap = run(
            """
            @startuml
            state Parent {
              [H] --> Ready
              --
              Ready --> [H*]
            }
            note top of Parent : regions
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        val ir = snap.ir as? StateIR
        assertNotNull(ir)
        assertTrue(ir.states.any { it.kind == StateKind.History })
        assertTrue(ir.states.any { it.kind == StateKind.DeepHistory })
        assertNotNull(snap.laidOut?.clusterRects?.get(NodeId("note#0")))
    }

    @Test
    fun state_regions_multiline_note_and_skinparam_render_consistently() {
        val src =
            """
            @startuml
            skinparam state {
              BackgroundColor LightBlue
              BorderColor Navy
              FontColor SaddleBrown
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Orange
              FontColor Navy
            }
            skinparam ArrowColor Peru
            state Parent {
              [*] --> Idle
              Idle --> Busy
              --
              Busy --> Done
              note right of Done
                region note
                line 2
              end note
            }
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = one.ir as? StateIR
        val chunkedIr = chunked.ir as? StateIR
        assertNotNull(oneIr)
        assertNotNull(chunkedIr)
        assertEquals(oneIr, chunkedIr)
        val parent = oneIr.states.first { it.id == NodeId("Parent") }
        val regions = parent.children.filter { it.value.startsWith(PlantUmlStateParser.REGION_PREFIX) }
        assertEquals(2, regions.size)
        assertTrue(one.laidOut?.nodePositions?.containsKey(regions[0]) == true)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val fillRects = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        val strokeRects = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().map { it.color.argb }
        val strokePaths = one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().map { it.color.argb }
        val textColors = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.color.argb }
        assertTrue(fillRects.contains(0xFFADD8E6.toInt()), "expected state fill LightBlue")
        assertTrue(strokeRects.contains(0xFF000080.toInt()), "expected state border Navy")
        assertTrue(fillRects.contains(0xFFFFFFF0.toInt()), "expected note fill Ivory")
        assertTrue(strokeRects.contains(0xFFFFA500.toInt()), "expected note border Orange")
        assertTrue(strokePaths.contains(0xFFCD853F.toInt()), "expected arrow/separator color Peru or border-derived strokes")
        assertTrue(textColors.contains(0xFF8B4513.toInt()), "expected state text SaddleBrown")
        assertTrue(textColors.contains(0xFF000080.toInt()), "expected note text Navy")
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(SourceLanguage.PLANTUML).let { s ->
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

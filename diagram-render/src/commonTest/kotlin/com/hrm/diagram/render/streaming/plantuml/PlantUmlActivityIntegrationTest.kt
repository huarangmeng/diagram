package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlActivityIntegrationTest {
    @Test
    fun activity_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            start
            :Step 1;
            if (ok?) then (yes)
              :Done;
            else (no)
              :Retry;
            endif
            stop
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<ActivityIR>(one.ir)
        val chunkedIr = assertIs<ActivityIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun while_loop_yields_edge_routes() {
        val snapshot = run(
            """
            @startuml
            start
            while (pending?)
              :Work;
            endwhile
            stop
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        assertIs<ActivityIR>(snapshot.ir)
        assertTrue(snapshot.laidOut!!.edgeRoutes.isNotEmpty())
    }

    @Test
    fun note_block_is_rendered() {
        val snapshot = run(
            """
            @startuml
            start
            note right: explain
            :Work;
            stop
            @enduml
            """.trimIndent() + "\n",
            3,
        )
        assertIs<ActivityIR>(snapshot.ir)
        assertTrue(snapshot.drawCommands.isNotEmpty())
    }

    @Test
    fun elseif_repeat_fork_and_swimlane_are_streaming_consistent() {
        val src =
            """
            @startuml
            start
            |Customer|
            :Submit order;
            if (valid?) then (yes)
              :Accept;
            elseif (retry?) then (again)
              repeat
                :Collect info;
              repeat while (more?)
            else (no)
              :Reject;
            endif
            |System|
            fork
              :Reserve stock;
            fork again
              :Charge card;
            end fork
            stop
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<ActivityIR>(one.ir)
        val chunkedIr = assertIs<ActivityIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun swimlane_lowering_emits_clusters_and_fork_routes() {
        val snapshot = run(
            """
            @startuml
            |A|
            fork
              :Left;
            fork again
              :Right;
            end fork
            |B|
            repeat
              :Loop;
            repeat while (again?)
            @enduml
            """.trimIndent() + "\n",
            5,
        )
        assertIs<ActivityIR>(snapshot.ir)
        val laidOut = snapshot.laidOut!!
        assertTrue(laidOut.edgeRoutes.isNotEmpty())
        val clusters = laidOut.clusterRects.keys.map { it.value }
        assertTrue(clusters.any { it.startsWith("lane_") }, "expected swimlane cluster rects")
    }

    @Test
    fun legacy_activity_partition_and_styles_render_without_diagnostics() {
        val src =
            """
            @startuml
            partition Conductor #LightSkyBlue {
            (*) --> "Climbs on Platform"
            -->[cue] if "Ready?" then
              #palegreen:"Play music"
            else
              -right-> "Wait"
            endif
            note right
              keep tempo
              and smile
            end note
            --> (*)
            }
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 5)
        assertIs<ActivityIR>(one.ir)
        assertIs<ActivityIR>(chunked.ir)
        assertEquals(one.ir, chunked.ir)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val clusterIds = one.laidOut!!.clusterRects.keys.map { it.value.lowercase() }
        assertTrue(clusterIds.any { it.startsWith("lane_conductor") }, "expected partition cluster")
        assertTrue(one.drawCommands.isNotEmpty())
    }

    @Test
    fun sync_bar_is_lowered_to_forkbar_and_keeps_label() {
        val src =
            """
            @startuml
            start
            :Load;
            === phase 1 ===
            :Ship;
            stop
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 3)
        val oneIr = assertIs<ActivityIR>(one.ir)
        val chunkedIr = assertIs<ActivityIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val hasThinBar = one.drawCommands
            .filterIsInstance<DrawCommand.FillRect>()
            .any { it.rect.size.height <= 12.5f && it.rect.size.width >= 80f }
        assertTrue(hasThinBar, "expected sync bar to render as thin bar")
        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(texts.contains("phase 1"), "expected sync bar label to render")
    }

    @Test
    fun legacy_alias_sync_and_activity_skinparam_render_consistently() {
        val src =
            """
            @startuml
            skinparam activity {
              StartColor red
              BarColor SaddleBrown
              EndColor Silver
              BackgroundColor Peru
              BorderColor Peru
              DiamondBackgroundColor PaleGreen
              DiamondBorderColor Green
              NoteBackgroundColor Ivory
              NoteBorderColor Orange
              ArrowColor Navy
            }
            (*) --> "First Action" as A1
            --> ===B1===
            --> "Parallel Action 1"
            --> ===B2===
            ===B1=== --> "Parallel Action 2"
            --> ===B2===
            A1 --> if "Ready?" then
              --> "Done"
            else
              --> "Retry"
            endif
            note right: explanation
            ===B2=== --> (*)
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<ActivityIR>(one.ir)
        val chunkedIr = assertIs<ActivityIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val fills = one.drawCommands.filterIsInstance<DrawCommand.FillRect>().map { it.color.argb }
        assertTrue(fills.contains(0xFFCD853F.toInt()), "expected activity background color Peru")
        assertTrue(fills.contains(0xFF8B4513.toInt()), "expected sync bar color SaddleBrown")
        val strokes = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>().map { it.color.argb }
        assertTrue(strokes.contains(0xFFCD853F.toInt()), "expected activity border color Peru")
        assertTrue(strokes.contains(0xFFC0C0C0.toInt()) || one.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == 0xFFC0C0C0.toInt() }, "expected end color Silver to affect stop")
        val edgeColors = one.drawCommands.filterIsInstance<DrawCommand.StrokePath>().map { it.color.argb }
        assertTrue(edgeColors.contains(0xFF000080.toInt()), "expected arrow color Navy")
        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(texts.contains("Parallel Action 2"))
        assertTrue(texts.contains("B1"))
        assertTrue(texts.contains("B2"))
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

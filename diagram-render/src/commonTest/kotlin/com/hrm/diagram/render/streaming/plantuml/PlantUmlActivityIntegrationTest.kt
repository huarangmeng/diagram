package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.ActivityIR
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

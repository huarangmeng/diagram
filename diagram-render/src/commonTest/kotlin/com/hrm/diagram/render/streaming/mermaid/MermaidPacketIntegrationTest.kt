package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidPacketIntegrationTest {
    @Test
    fun packet_beta_renders_and_is_streaming_consistent() {
        val src =
            """
            packet-beta
            title TCP Header
            0-15: "Source Port"
            16-31: "Destination Port"
            32-63: "Sequence Number"
            106: "URG"
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 6)
        val oneIr = assertIs<StructIR>(one.ir)
        val chunkedIr = assertIs<StructIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val root = assertIs<StructNode.ObjectNode>(oneIr.root)
        assertEquals(4, root.entries.size)
        assertNotNull(one.laidOut)
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.FillRect>().isNotEmpty())
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("Source Port") })
        assertTrue(one.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text.contains("URG") })
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(language = SourceLanguage.MERMAID).let { s ->
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

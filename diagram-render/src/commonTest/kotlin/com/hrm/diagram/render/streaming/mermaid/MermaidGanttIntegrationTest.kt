package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TimeSeriesIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidGanttIntegrationTest {

    @Test
    fun gantt_official_basic_sample_renders_and_is_streaming_consistent() {
        val src =
            """
            gantt
                title A Gantt Diagram
                dateFormat YYYY-MM-DD
                section Section
                    A task :a1, 2014-01-01, 30d
                    Another task :after a1, 20d
                section Another
                    Task in Another :2014-01-12, 12d
                    another task :24d
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)

        val oneIr = assertIs<TimeSeriesIR>(one.ir)
        val chunkedIr = assertIs<TimeSeriesIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)

        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect })
        assertTrue(one.drawCommands.any { it is DrawCommand.DrawText })
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
    }

    @Test
    fun gantt_axis_ticks_compact_mode_and_click_render() {
        val src =
            """
            ---
            displayMode: compact
            ---
            gantt
                title Compact
                dateFormat YYYY-MM-DD
                axisFormat %m/%d
                tickInterval 1day
                section Clickable
                    Visit site :cl1, 2014-01-07, 3d
                    Follow up   :cl2, 2014-01-10, 2d
                click cl1 href https://mermaid.js.org/
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        val ir = assertIs<TimeSeriesIR>(snapshot.ir)
        val laidOut = snapshot.laidOut ?: error("missing layout")
        val first = laidOut.nodePositions[com.hrm.diagram.core.ir.NodeId("gantt:item:cl1")] ?: error("missing cl1")
        val second = laidOut.nodePositions[com.hrm.diagram.core.ir.NodeId("gantt:item:cl2")] ?: error("missing cl2")

        assertEquals("compact", ir.styleHints.extras["gantt.displayMode"])
        assertTrue(snapshot.drawCommands.any { it is DrawCommand.Hyperlink })
        assertTrue(snapshot.drawCommands.any { it is DrawCommand.DrawText && it.text.contains("/") })
        assertTrue(first.top == second.top, "compact mode should place overlapping tasks on the same row: $first vs $second")
    }

    @Test
    fun gantt_vert_marker_renders_without_consuming_a_task_row() {
        val src =
            """
            gantt
                dateFormat HH:mm
                axisFormat %H:%M
                section Markers
                    Initial vert :vert, v1, 17:30, 2m
                    Task A : 3m
                    Task B : 5m
            """.trimIndent() + "\n"

        val snapshot = run(src, chunkSize = src.length)
        val laidOut = snapshot.laidOut ?: error("missing layout")
        val taskA = laidOut.nodePositions[com.hrm.diagram.core.ir.NodeId("gantt:item:task_a_1")] ?: error("missing task A")
        val taskB = laidOut.nodePositions[com.hrm.diagram.core.ir.NodeId("gantt:item:task_b_2")] ?: error("missing task B")
        val vert = laidOut.nodePositions[com.hrm.diagram.core.ir.NodeId("gantt:vert:v1")] ?: error("missing vert marker")

        assertTrue(snapshot.drawCommands.any { it is DrawCommand.StrokePath && it.stroke.dash == listOf(6f, 4f) })
        assertTrue(snapshot.drawCommands.any { it is DrawCommand.DrawText && it.text == "Initial vert" })
        assertTrue(taskB.top > taskA.top, "normal tasks should still occupy rows: $taskA vs $taskB")
        assertTrue(vert.top <= taskA.top && vert.bottom >= taskB.bottom, "vert marker should span the chart body: $vert tasks=[$taskA,$taskB]")
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

    private fun drawSignature(cmds: List<DrawCommand>): List<DrawSig> = cmds.map { it.toSig() }

    private sealed interface DrawSig {
        val z: Int
        data class FillRectSig(val colorArgb: Int, val corner: Float, override val z: Int) : DrawSig
        data class StrokeRectSig(val colorArgb: Int, val stroke: StrokeSig, val corner: Float, override val z: Int) : DrawSig
        data class FillPathSig(val colorArgb: Int, override val z: Int) : DrawSig
        data class StrokePathSig(val colorArgb: Int, val stroke: StrokeSig, override val z: Int) : DrawSig
        data class HyperlinkSig(val href: String, override val z: Int) : DrawSig
        data class DrawTextSig(
            val text: String,
            val font: FontSig,
            val colorArgb: Int,
            val maxWidth: Float?,
            val anchorX: TextAnchorX,
            val anchorY: TextAnchorY,
            override val z: Int,
        ) : DrawSig
    }

    private data class StrokeSig(val width: Float, val dash: List<Float>?)
    private data class FontSig(val family: String, val sizeSp: Float, val weight: Int, val italic: Boolean)

    private fun Stroke.toSig(): StrokeSig = StrokeSig(width = width, dash = dash)
    private fun FontSpec.toSig(): FontSig = FontSig(family = family, sizeSp = sizeSp, weight = weight, italic = italic)

    private fun DrawCommand.toSig(): DrawSig =
        when (this) {
            is DrawCommand.FillRect -> DrawSig.FillRectSig(colorArgb = color.argb, corner = corner, z = z)
            is DrawCommand.StrokeRect -> DrawSig.StrokeRectSig(colorArgb = color.argb, stroke = stroke.toSig(), corner = corner, z = z)
            is DrawCommand.FillPath -> DrawSig.FillPathSig(colorArgb = color.argb, z = z)
            is DrawCommand.StrokePath -> DrawSig.StrokePathSig(colorArgb = color.argb, stroke = stroke.toSig(), z = z)
            is DrawCommand.Hyperlink -> DrawSig.HyperlinkSig(href = href, z = z)
            is DrawCommand.DrawText ->
                DrawSig.DrawTextSig(
                    text = text,
                    font = font.toSig(),
                    colorArgb = color.argb,
                    maxWidth = maxWidth,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    z = z,
                )
            else -> DrawSig.DrawTextSig(
                text = this::class.simpleName ?: "Unknown",
                font = FontSig("", 0f, 0, false),
                colorArgb = 0,
                maxWidth = null,
                anchorX = TextAnchorX.Start,
                anchorY = TextAnchorY.Top,
                z = z,
            )
        }
}

package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.draw.TextAnchorX
import com.hrm.diagram.core.draw.TextAnchorY
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidXYChartIntegrationTest {

    @Test
    fun xychart_official_sample_subset_renders_and_is_streaming_consistent() {
        val src =
            """
            xychart
                title "Sales Revenue"
                x-axis [jan, feb, mar]
                y-axis "Revenue (in $)" 4000 --> 11000
                bar [5000, 6000, 7500]
                line [5000, 6000, 7500]
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 4)

        val oneIr = assertIs<XYChartIR>(one.ir)
        val chunkedIr = assertIs<XYChartIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.any { it is DrawCommand.FillRect })
        assertTrue(one.drawCommands.any { it is DrawCommand.StrokePath })
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
    }

    @Test
    fun xychart_horizontal_scatter_renders_and_is_streaming_consistent() {
        val src =
            """
            xychart-beta horizontal
              x-axis "Index" 0 --> 10
              y-axis "Value" -2 --> 2
              scatter [1, 0, -1]
            """.trimIndent() + "\n"

        val one = run(src, chunkSize = src.length)
        val chunked = run(src, chunkSize = 3)
        assertEquals(drawSignature(one.drawCommands), drawSignature(chunked.drawCommands))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun xychart_beta_y_axis_uses_integer_ticks_and_rotated_title() {
        val src =
            """
            xychart-beta
              title "Demo"
              x-axis [1, 2, 3, 4]
              y-axis "value" 0 --> 10
              line [1, 4, 6, 9]
            """.trimIndent() + "\n"

        val snap = run(src, chunkSize = src.length)
        val texts = drawTexts(snap.drawCommands).map { it.text }
        assertTrue(listOf("0", "2", "4", "6", "8", "10").all { it in texts }, "expected integer y-axis ticks in $texts")
        assertTrue(listOf("2.0", "4.0", "6.0", "8.0").none { it in texts }, "integer y-axis ticks must not contain floating suffixes: $texts")
        assertTrue(
            snap.drawCommands.filterIsInstance<DrawCommand.Group>().any { group ->
                group.transform.rotateDeg == -90f &&
                    drawTexts(group.children).any { it.text == "value" }
            },
            "expected y-axis title to be rendered as a rotated group",
        )
        assertTrue(snap.diagnostics.isEmpty(), "diagnostics: ${snap.diagnostics}")
    }

    @Test
    fun xychart_area_and_data_labels_from_frontmatter_render() {
        val src =
            """
            ---
            config:
              xyChart:
                showDataLabel: true
            ---
            xychart
              x-axis [jan, feb, mar]
              y-axis "Revenue" 0 --> 10
              area [2, 4, 6]
              bar [1, 3, 5]
            """.trimIndent() + "\n"
        val snap = run(src, chunkSize = src.length)
        assertTrue(snap.drawCommands.any { it is DrawCommand.FillPath }, "expected area fill path")
        val labels = snap.drawCommands.filterIsInstance<DrawCommand.DrawText>().map { it.text }
        assertTrue(labels.any { it == "1" || it == "1.0" }, "expected data label text in $labels")
        assertTrue(snap.diagnostics.isEmpty(), "diagnostics: ${snap.diagnostics}")
    }

    @Test
    fun xychart_theme_variables_override_plot_and_title_colors() {
        val src =
            """
            ---
            config:
              themeVariables:
                xyChart:
                  titleColor: "#ff0000"
                  plotColorPalette: "#0000ff"
            ---
            xychart
              title "The Title"
              x-axis [jan]
              bar [2]
            """.trimIndent() + "\n"
        val snap = run(src, chunkSize = src.length)
        assertTrue(snap.drawCommands.filterIsInstance<DrawCommand.DrawText>().any { it.text == "The Title" && it.color.argb == Color(0xFFFF0000.toInt()).argb })
        assertTrue(snap.drawCommands.filterIsInstance<DrawCommand.FillRect>().any { it.color.argb == Color(0xFF0000FF.toInt()).argb })
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

    private fun drawTexts(cmds: List<DrawCommand>): List<DrawCommand.DrawText> =
        cmds.flatMap { cmd ->
            when (cmd) {
                is DrawCommand.DrawText -> listOf(cmd)
                is DrawCommand.Group -> drawTexts(cmd.children)
                is DrawCommand.Clip -> drawTexts(cmd.children)
                else -> emptyList()
            }
        }

    private sealed interface DrawSig {
        val z: Int
        data class FillRectSig(val colorArgb: Int, val corner: Float, override val z: Int) : DrawSig
        data class StrokeRectSig(val colorArgb: Int, val stroke: StrokeSig, val corner: Float, override val z: Int) : DrawSig
        data class FillPathSig(val colorArgb: Int, override val z: Int) : DrawSig
        data class StrokePathSig(val colorArgb: Int, val stroke: StrokeSig, override val z: Int) : DrawSig
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

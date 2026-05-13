package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlMindmapIntegrationTest {
    @Test
    fun mindmap_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startmindmap
            + Root
            ++ Right
            *** Right leaf
            -- Left
            *** Left leaf
            **:Example
            line 2
            ;
            @endmindmap
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<TreeIR>(one.ir)
        val chunkedIr = assertIs<TreeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("Example\nline 2", (oneIr.root.children.last().label as RichLabel.Plain).text)
    }

    @Test
    fun arithmetic_side_markers_affect_layout() {
        val snapshot = run(
            """
            @startmindmap
            + Root
            ++ Right
            -- Left
            @endmindmap
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<TreeIR>(snapshot.ir)
        val laidOut = assertNotNull(snapshot.laidOut)
        val rootRect = laidOut.nodePositions.getValue(ir.root.id)
        val rightRect = laidOut.nodePositions.getValue(ir.root.children.first { (it.label as RichLabel.Plain).text == "Right" }.id)
        val leftRect = laidOut.nodePositions.getValue(ir.root.children.first { (it.label as RichLabel.Plain).text == "Left" }.id)
        assertTrue(rightRect.left >= rootRect.right, "right branch should be placed to the right of root")
        assertTrue(leftRect.right <= rootRect.left, "left branch should be placed to the left of root")
    }

    @Test
    fun stereotype_is_rendered_as_separate_title_line() {
        val snapshot = run(
            """
            @startmindmap
            * <<vision>> Root
            ** <<risk>> Branch
            @endmindmap
            """.trimIndent() + "\n",
            4,
        )
        val texts = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        assertTrue(texts.any { it.text == "\u00ABvision\u00BB" && it.font.italic }, "root stereotype should render as italic title")
        assertTrue(texts.any { it.text == "Root" }, "root body label should render separately")
        assertTrue(texts.any { it.text == "\u00ABrisk\u00BB" && it.font.italic }, "child stereotype should render as italic title")
        assertTrue(texts.any { it.text == "Branch" }, "child body label should render separately")
    }

    @Test
    fun inline_color_affects_fill_and_text_rendering() {
        val snapshot = run(
            """
            @startmindmap
            * [#112233] <<vision>> Root
            ** [orange] Branch
            @endmindmap
            """.trimIndent() + "\n",
            5,
        )
        val fills = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>()
        val texts = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == 0xFF112233.toInt() }, "root inline color should reach FillRect")
        assertTrue(texts.any { it.text == "\u00ABvision\u00BB" && it.color.argb == 0xFFFFFFFF.toInt() }, "dark fill should switch stereotype text to white")
        assertTrue(texts.any { it.text == "Root" && it.color.argb == 0xFFFFFFFF.toInt() }, "dark fill should switch body text to white")
        assertTrue(fills.any { it.color.argb == 0xFFFFA500.toInt() }, "child inline color should reach FillRect")
    }

    @Test
    fun icon_and_emoji_render_as_leading_visuals() {
        val snapshot = run(
            """
            @startmindmap
            * <&flag> Root
            ** <:smile:> Branch
            ** <#green:sunny:> Weather
            @endmindmap
            """.trimIndent() + "\n",
            4,
        )
        val icons = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawIcon>()
        val texts = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        assertTrue(icons.any { it.name == "flag" }, "openiconic leading visual should emit DrawIcon")
        assertTrue(texts.any { it.text == "FL" }, "icon fallback label should be rendered")
        assertTrue(texts.any { it.text == "Root" }, "root body label should still render")
        assertTrue(texts.any { it.text == "SM" }, "emoji fallback label should be rendered")
        assertTrue(texts.any { it.text == "Weather" }, "emoji body label should still render")
        assertTrue(texts.any { it.text == "SU" && it.color.argb == 0xFF008000.toInt() }, "colored emoji should use parsed emoji color")
    }

    @Test
    fun style_color_affects_fill_and_branch_descendants() {
        val snapshot = run(
            """
            @startmindmap
            <style>
            mindmapDiagram {
              .green {
                BackgroundColor lightgreen
              }
              .branch * {
                BackgroundColor #FFBBCC
              }
            }
            </style>
            * Root
            ** Green <<green>>
            ** Branch <<branch>>
            *** Child
            @endmindmap
            """.trimIndent() + "\n",
            5,
        )
        val texts = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        val fills = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>()
        assertTrue(fills.any { it.color.argb == 0xFF90EE90.toInt() }, "style class should tint matching node")
        assertTrue(fills.any { it.color.argb == 0xFFFFBBCC.toInt() }, "branch style should tint descendants")
        assertTrue(texts.none { it.text == "\u00ABgreen\u00BB" || it.text == "\u00ABbranch\u00BB" }, "style class should not render as visible stereotype")
    }

    @Test
    fun line_font_and_round_corner_affect_border_text_and_edges() {
        val snapshot = run(
            """
            @startmindmap
            <style>
            mindmapDiagram {
              .node {
                BackgroundColor lightgreen
                LineColor navy
                FontColor red
                RoundCorner 18
              }
              .branch * {
                LineColor #123456
                FontColor orange
                RoundCorner 24
              }
            }
            </style>
            * Root
            ** Node <<node>>
            ** Branch <<branch>>
            *** Child
            @endmindmap
            """.trimIndent() + "\n",
            5,
        )
        val strokeRects = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokeRect>()
        val strokePaths = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokePath>()
        val texts = snapshot.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        assertTrue(strokeRects.any { it.color.argb == 0xFF000080.toInt() && it.corner == 18f }, "node style should control border color and round corner")
        assertTrue(strokeRects.any { it.color.argb == 0xFF123456.toInt() && it.corner == 24f }, "branch style should control descendant border color and round corner")
        assertTrue(strokePaths.any { it.color.argb == 0xFF123456.toInt() }, "branch style line color should tint connectors")
        assertTrue(texts.any { it.text == "Node" && it.color.argb == 0xFFFF0000.toInt() }, "node style font color should tint text")
        assertTrue(texts.any { it.text == "Child" && it.color.argb == 0xFFFFA500.toInt() }, "branch style font color should tint descendants")
    }

    @Test
    fun boxless_font_style_and_shadowing_render_with_streaming_consistency() {
        val src =
            """
            @startmindmap
            <style>
            mindmapDiagram {
              .node {
                FontName serif
                FontSize 18
                FontStyle bold italic
                LineThickness 3
                Shadowing true
                MaximumWidth 120
              }
              .branch * {
                FontName monospace
                FontSize 15
                FontStyle italic
                LineThickness 4
                Shadowing false
                MaximumWidth 96
              }
            }
            </style>
            *_ **Root**
            ** Node <<node>>
            ** Branch <<branch>>
            ***_ //Child//
            @endmindmap
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<TreeIR>(one.ir)
        val chunkedIr = assertIs<TreeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val fills = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>()
        val strokes = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokeRect>()
        val paths = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokePath>()
        val texts = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == 0x26000000 }, "shadowing should emit deterministic shadow fill")
        assertTrue(strokes.none { it.rect == one.laidOut!!.nodePositions.getValue(oneIr.root.id) }, "boxless root should skip chrome")
        assertTrue(texts.any { it.text == "Root" })
        assertTrue(strokes.any { it.stroke.width == 3f })
        assertTrue(paths.any { it.stroke.width == 4f })
        assertTrue(texts.any { it.text == "Node" && it.font.family == "serif" && it.font.sizeSp == 18f && it.font.weight == 700 && it.font.italic })
        assertTrue(texts.any { it.text == "Child" && it.font.family == "monospace" && it.font.sizeSp == 15f && it.font.italic })
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

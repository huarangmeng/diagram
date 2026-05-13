package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.parser.plantuml.PlantUmlWbsParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlWbsIntegrationTest {
    @Test
    fun wbs_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startwbs
            + Project
            ++ Phase 1
            +++ Task 1.1
            ++- Checklist
            +++- Responsibility
            **>:Review
            OK;
            @endwbs
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = assertIs<TreeIR>(one.ir)
        val chunkedIr = assertIs<TreeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        assertEquals("Review\nOK", ((oneIr.root.children.last().label) as RichLabel.Plain).text)
    }

    @Test
    fun side_markers_and_boxless_affect_rendering() {
        val snapshot = run(
            """
            @startwbs
            * World
            **_ America
            ***< Canada
            **> Europe
            @endwbs
            """.trimIndent() + "\n",
            3,
        )
        val ir = assertIs<TreeIR>(snapshot.ir)
        val laidOut = assertNotNull(snapshot.laidOut)
        val world = laidOut.nodePositions.getValue(ir.root.id)
        val america = ir.root.children.first { (it.label as RichLabel.Plain).text == "America" }
        val canada = america.children.single()
        val europe = ir.root.children.first { (it.label as RichLabel.Plain).text == "Europe" }
        val canadaRect = laidOut.nodePositions.getValue(canada.id)
        val europeRect = laidOut.nodePositions.getValue(europe.id)
        assertTrue(canadaRect.right <= world.left || canadaRect.right <= laidOut.nodePositions.getValue(america.id).left)
        assertTrue(europeRect.left >= world.right)

        val boxlessIds = ir.styleHints.extras[PlantUmlWbsParser.BOXLESS_KEY].orEmpty()
        assertTrue(boxlessIds.contains(america.id.value))
        val strokeRects = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        assertTrue(strokeRects.size < countNodes(ir.root), "boxless node should reduce rectangle strokes")
    }

    @Test
    fun inline_color_affects_fill_and_text_rendering() {
        val snapshot = run(
            """
            @startwbs
            * [#112233] Root
            **_ [orange] Accent
            @endwbs
            """.trimIndent() + "\n",
            5,
        )
        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == 0xFF112233.toInt() }, "root inline color should reach FillRect")
        assertTrue(texts.any { it.text == "Root" && it.color.argb == 0xFFFFFFFF.toInt() }, "dark fill should switch text to white")
        assertTrue(texts.any { it.text == "Accent" && it.color.argb != 0xFF263238.toInt() }, "boxless inline color should tint text")
    }

    @Test
    fun stereotype_is_rendered_as_separate_title_line() {
        val snapshot = run(
            """
            @startwbs
            * <<vision>> Root
            **_ <<risk>> Accent
            @endwbs
            """.trimIndent() + "\n",
            4,
        )
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(texts.any { it.text == "\u00ABvision\u00BB" && it.font.italic }, "root stereotype should render as italic title")
        assertTrue(texts.any { it.text == "Root" }, "root body label should render separately")
        assertTrue(texts.any { it.text == "\u00ABrisk\u00BB" && it.font.italic }, "child stereotype should render as italic title")
        assertTrue(texts.any { it.text == "Accent" }, "child body label should render separately")
    }

    @Test
    fun icon_and_emoji_render_as_leading_visuals() {
        val snapshot = run(
            """
            @startwbs
            * <&flag> Root
            **_ <:smile:> Accent
            ** <#green:sunny:> Weather
            @endwbs
            """.trimIndent() + "\n",
            4,
        )
        val icons = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawIcon>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(icons.any { it.name == "flag" }, "openiconic leading visual should emit DrawIcon")
        assertTrue(texts.any { it.text == "FL" }, "icon fallback label should be rendered")
        assertTrue(texts.any { it.text == "Root" }, "root body label should still render")
        assertTrue(texts.any { it.text == "SM" }, "emoji fallback label should be rendered")
        assertTrue(texts.any { it.text == "Accent" }, "boxless body label should still render")
        assertTrue(texts.any { it.text == "SU" && it.color.argb == 0xFF008000.toInt() }, "colored emoji should use parsed emoji color")
    }

    @Test
    fun style_color_affects_fill_boxless_text_and_multiline_suffix_class() {
        val snapshot = run(
            """
            @startwbs
            <style>
            wbsDiagram {
              .pink {
                BackgroundColor pink
              }
              .branch * {
                BackgroundColor SkyBlue
              }
            }
            </style>
            * Root
            ** Branch <<branch>>
            *** Child
            **_:This is on multiple
            lines; <<pink>>
            @endwbs
            """.trimIndent() + "\n",
            5,
        )
        val fills = snapshot.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(fills.any { it.color.argb == 0xFF87CEEB.toInt() }, "branch style should tint descendants")
        assertTrue(texts.any { it.text == "This is on multiple\nlines" && it.color.argb != 0xFF263238.toInt() }, "boxless style color should tint text")
        assertTrue(texts.none { it.text == "\u00ABpink\u00BB" || it.text == "\u00ABbranch\u00BB" }, "style class should not render as visible stereotype")
    }

    @Test
    fun line_font_and_round_corner_affect_border_text_boxless_and_edges() {
        val snapshot = run(
            """
            @startwbs
            <style>
            wbsDiagram {
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
            ***_ Child
            @endwbs
            """.trimIndent() + "\n",
            5,
        )
        val strokeRects = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        val strokePaths = snapshot.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        val texts = snapshot.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        assertTrue(strokeRects.any { it.color.argb == 0xFF000080.toInt() && it.corner == 18f }, "node style should control border color and round corner")
        assertTrue(strokePaths.any { it.color.argb == 0xFF123456.toInt() }, "branch style line color should tint connectors")
        assertTrue(texts.any { it.text == "Node" && it.color.argb == 0xFFFF0000.toInt() }, "node style font color should tint text")
        assertTrue(texts.any { it.text == "Child" && it.color.argb == 0xFFFFA500.toInt() }, "boxless branch style font color should tint text")
        assertTrue(strokeRects.none { it.corner == 24f }, "boxless descendant should not render stroke rect even with round corner style")
    }

    @Test
    fun startuml_wbs_cue_and_advanced_style_fields_render_consistently() {
        val src =
            """
            @startuml
            <style>
            wbsDiagram {
              .node {
                FontName "JetBrains Mono"
                FontSize 18
                FontStyle bold italic
                LineThickness 3
                Shadowing true
                MaximumWidth 120
              }
              .branch * {
                FontSize 15
                LineThickness 4
                MaximumWidth 96
              }
            }
            </style>
            * Root
            ** Node <<node>>
            ** Branch <<branch>>
            *** Child with a long label to wrap inside width
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 5)
        val oneIr = assertIs<TreeIR>(one.ir)
        val chunkedIr = assertIs<TreeIR>(chunked.ir)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
        val texts = one.drawCommands.filterIsInstance<DrawCommand.DrawText>()
        val strokeRects = one.drawCommands.filterIsInstance<DrawCommand.StrokeRect>()
        val strokePaths = one.drawCommands.filterIsInstance<DrawCommand.StrokePath>()
        val fills = one.drawCommands.filterIsInstance<DrawCommand.FillRect>()
        assertTrue(texts.any { it.text == "Node" && it.font.family == "JetBrains Mono" && it.font.sizeSp == 18f && it.font.weight == 700 && it.font.italic })
        assertTrue(strokeRects.any { it.stroke.width == 3f })
        assertTrue(strokePaths.any { it.stroke.width == 4f })
        assertTrue(fills.any { it.color.argb == 0x26000000 }, "shadowing should emit deterministic shadow fill")
        val child = oneIr.root.children[1].children.single()
        val childRect = assertNotNull(one.laidOut).nodePositions.getValue(child.id)
        assertTrue(childRect.size.width <= 124f, "MaximumWidth should constrain layout width, actual=${childRect.size.width}")
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

    private fun countNodes(node: com.hrm.diagram.core.ir.TreeNode): Int = 1 + node.children.sumOf(::countNodes)
}

package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TreeIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlMindmapParserTest {
    private fun parse(src: String, blockClosed: Boolean = true, chunkSize: Int? = null): PlantUmlMindmapParser {
        val parser = PlantUmlMindmapParser()
        if (chunkSize == null) {
            src.lines().forEach { parser.acceptLine(it) }
        } else {
            var pending = ""
            var index = 0
            while (index < src.length) {
                val end = (index + chunkSize).coerceAtMost(src.length)
                val merged = pending + src.substring(index, end)
                var start = 0
                for (i in merged.indices) {
                    if (merged[i] == '\n') {
                        parser.acceptLine(merged.substring(start, i))
                        start = i + 1
                    }
                }
                pending = if (start < merged.length) merged.substring(start) else ""
                index = end
            }
            if (pending.isNotEmpty()) parser.acceptLine(pending)
        }
        parser.finish(blockClosed)
        return parser
    }

    @Test
    fun parses_basic_star_hierarchy() {
        val ir = assertIs<TreeIR>(
            parse(
                """
                * Root
                ** A
                *** A1
                ** B
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(SourceLanguage.PLANTUML, ir.sourceLanguage)
        assertEquals("Root", (ir.root.label as RichLabel.Plain).text)
        assertEquals(listOf("A", "B"), ir.root.children.map { (it.label as RichLabel.Plain).text })
        assertEquals("A1", (ir.root.children.first().children.single().label as RichLabel.Plain).text)
        val sides = decodeSides(ir)
        assertEquals("root", sides[ir.root.id])
        assertEquals("auto", sides[ir.root.children[0].id])
        assertEquals("auto", sides[ir.root.children[0].children.single().id])
        assertEquals("auto", sides[ir.root.children[1].id])
    }

    @Test
    fun parses_arithmetic_side_markers_and_inherits_side() {
        val parser = parse(
            """
            + Root
            ++ Right
            *** Right leaf
            -- Left
            *** Left leaf
            """.trimIndent() + "\n",
        )
        val ir = parser.snapshot()
        val sides = decodeSides(ir)
        assertEquals("root", sides[ir.root.id])
        assertEquals("right", sides[ir.root.children[0].id])
        assertEquals("right", sides[ir.root.children[0].children.single().id])
        assertEquals("left", sides[ir.root.children[1].id])
        assertEquals("left", sides[ir.root.children[1].children.single().id])
    }

    @Test
    fun parses_multiline_nodes() {
        val ir = parse(
            """
            * Root
            **:Example 1
            line 2
            ;
            """.trimIndent() + "\n",
            chunkSize = 3,
        ).snapshot()
        val label = (ir.root.children.single().label as RichLabel.Plain).text
        assertEquals("Example 1\nline 2", label)
    }

    @Test
    fun stereotype_is_stored_and_reflected_in_label() {
        val ir = parse(
            """
            * <<vision>> Root
            ** <<risk>> Branch
            """.trimIndent() + "\n",
        ).snapshot()
        val stereotypes = decodeStereotypes(ir)
        assertEquals("vision", stereotypes[ir.root.id])
        assertEquals("risk", stereotypes[ir.root.children.single().id])
        assertEquals("\u00ABvision\u00BB\nRoot", (ir.root.label as RichLabel.Plain).text)
    }

    @Test
    fun inline_color_is_stored_in_style_hints() {
        val ir = parse(
            """
            * [#lightblue] Root
            ** [orange] Branch
            """.trimIndent() + "\n",
        ).snapshot()
        val colors = decodeInlineColors(ir)
        assertEquals("#lightblue", colors[ir.root.id])
        assertEquals("orange", colors[ir.root.children.single().id])
    }

    @Test
    fun icon_and_emoji_are_stored_and_reflected_in_label() {
        val ir = parse(
            """
            * <&flag> Root
            ** <:smile:> Branch
            ** <#green:sunny:> Colored Emoji
            """.trimIndent() + "\n",
        ).snapshot()
        val visuals = decodeLeadingVisuals(ir)
        assertEquals("icon,,flag", visuals[ir.root.id])
        assertEquals("emoji,,smile", visuals[ir.root.children[0].id])
        assertEquals("emoji,green,sunny", visuals[ir.root.children[1].id])
        assertEquals("FL Root", (ir.root.label as RichLabel.Plain).text)
        assertEquals("SM Branch", (ir.root.children[0].label as RichLabel.Plain).text)
    }

    @Test
    fun style_color_is_resolved_from_style_block_and_branch_selector() {
        val ir = parse(
            """
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
            """.trimIndent() + "\n",
        ).snapshot()
        val colors = decodeStyleColors(ir)
        assertEquals("lightgreen", colors[ir.root.children[0].id])
        assertEquals("#FFBBCC", colors[ir.root.children[1].children.single().id])
        assertTrue(ir.styleHints.extras[PlantUmlMindmapParser.STEREOTYPE_KEY].isNullOrEmpty(), "style class should not be rendered as stereotype")
        assertEquals("Green", (ir.root.children[0].label as RichLabel.Plain).text)
        assertEquals("Branch", (ir.root.children[1].label as RichLabel.Plain).text)
    }

    @Test
    fun line_font_and_round_corner_are_resolved_from_style_block() {
        val ir = parse(
            """
            <style>
            mindmapDiagram {
              .node {
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
            """.trimIndent() + "\n",
        ).snapshot()
        val lineColors = decodeStyleLineColors(ir)
        val fontColors = decodeStyleFontColors(ir)
        val roundCorners = decodeStyleRoundCorners(ir)
        assertEquals("navy", lineColors[ir.root.children[0].id])
        assertEquals("red", fontColors[ir.root.children[0].id])
        assertEquals("18", roundCorners[ir.root.children[0].id])
        assertEquals("#123456", lineColors[ir.root.children[1].children.single().id])
        assertEquals("orange", fontColors[ir.root.children[1].children.single().id])
        assertEquals("24", roundCorners[ir.root.children[1].children.single().id])
    }

    @Test
    fun boxless_and_lightweight_creole_markup_are_parsed() {
        val ir = parse(
            """
            *_ **Root**
            **_ //Branch//
            *** __Leaf__
            """.trimIndent() + "\n",
        ).snapshot()
        val boxless = decodeBoxless(ir)
        assertTrue(ir.root.id in boxless)
        assertTrue(ir.root.children.single().id in boxless)
        assertEquals("Root", (ir.root.label as RichLabel.Plain).text)
        assertEquals("Branch", (ir.root.children.single().label as RichLabel.Plain).text)
        assertEquals("Leaf", (ir.root.children.single().children.single().label as RichLabel.Plain).text)
    }

    @Test
    fun font_name_size_style_and_shadowing_are_resolved_from_style_block() {
        val ir = parse(
            """
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
            * Root
            ** Node <<node>>
            ** Branch <<branch>>
            *** Child
            """.trimIndent() + "\n",
        ).snapshot()
        val fontNames = decodeMap(ir, PlantUmlMindmapParser.STYLE_FONT_NAME_KEY)
        val fontSizes = decodeMap(ir, PlantUmlMindmapParser.STYLE_FONT_SIZE_KEY)
        val fontStyles = decodeMap(ir, PlantUmlMindmapParser.STYLE_FONT_STYLE_KEY)
        val lineThickness = decodeMap(ir, PlantUmlMindmapParser.STYLE_LINE_THICKNESS_KEY)
        val shadowing = decodeMap(ir, PlantUmlMindmapParser.STYLE_SHADOWING_KEY)
        val maxWidths = decodeMap(ir, PlantUmlMindmapParser.STYLE_MAXIMUM_WIDTH_KEY)
        val node = ir.root.children[0]
        val child = ir.root.children[1].children.single()
        assertEquals("serif", fontNames[node.id])
        assertEquals("18", fontSizes[node.id])
        assertEquals("bold italic", fontStyles[node.id])
        assertEquals("3", lineThickness[node.id])
        assertEquals("true", shadowing[node.id])
        assertEquals("120", maxWidths[node.id])
        assertEquals("monospace", fontNames[child.id])
        assertEquals("15", fontSizes[child.id])
        assertEquals("italic", fontStyles[child.id])
        assertEquals("4", lineThickness[child.id])
        assertEquals("false", shadowing[child.id])
        assertEquals("96", maxWidths[child.id])
    }

    @Test
    fun multiple_roots_are_reported() {
        val parser = parse(
            """
            * Root 1
            * Root 2
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E011" })
    }

    @Test
    fun missing_endmindmap_is_reported() {
        val parser = parse(
            """
            * Root
            ** Child
            """.trimIndent() + "\n",
            blockClosed = false,
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E011" && it.message.contains("@endmindmap") })
    }

    private fun decodeSides(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.SIDE_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeBoxless(ir: TreeIR): Set<NodeId> =
        ir.styleHints.extras[PlantUmlMindmapParser.BOXLESS_KEY].orEmpty()
            .split("||")
            .filter { it.isNotEmpty() }
            .map { NodeId(it) }
            .toSet()

    private fun decodeStereotypes(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.STEREOTYPE_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeInlineColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.INLINE_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeLeadingVisuals(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.LEADING_VISUAL_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleLineColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_LINE_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleFontColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_FONT_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleRoundCorners(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlMindmapParser.STYLE_ROUND_CORNER_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeMap(ir: TreeIR, key: String): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[key].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }
}

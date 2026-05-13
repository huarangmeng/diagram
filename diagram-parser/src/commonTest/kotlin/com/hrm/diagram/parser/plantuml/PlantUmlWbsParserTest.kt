package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.TreeIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlWbsParserTest {
    private fun parse(src: String, blockClosed: Boolean = true, chunkSize: Int? = null): PlantUmlWbsParser {
        val parser = PlantUmlWbsParser()
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
                * Project
                ** Phase 1
                *** Task 1.1
                ** Phase 2
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(SourceLanguage.PLANTUML, ir.sourceLanguage)
        assertEquals("Project", (ir.root.label as RichLabel.Plain).text)
        assertEquals(listOf("Phase 1", "Phase 2"), ir.root.children.map { (it.label as RichLabel.Plain).text })
        assertEquals("Task 1.1", (ir.root.children.first().children.single().label as RichLabel.Plain).text)
    }

    @Test
    fun parses_arithmetic_notation_and_side_inheritance() {
        val ir = parse(
            """
            + New Job
            ++ Decide on Job Requirements
            +++ Identity gaps
            ++- Checklist
            +++- Responsibilities
            ---- Skills
            """.trimIndent() + "\n",
        ).snapshot()
        val sides = decodeSides(ir)
        val decide = ir.root.children.single()
        val identity = decide.children.first { (it.label as RichLabel.Plain).text == "Identity gaps" }
        val checklist = decide.children.first { (it.label as RichLabel.Plain).text == "Checklist" }
        assertEquals("root", sides[ir.root.id])
        assertEquals("right", sides[decide.id])
        assertEquals("right", sides[identity.id])
        assertEquals("left", sides[checklist.id])
        assertEquals("left", sides[checklist.children.first { (it.label as RichLabel.Plain).text == "Responsibilities" }.id])
        assertEquals("left", sides[checklist.children.first { (it.label as RichLabel.Plain).text == "Skills" }.id])
    }

    @Test
    fun direction_override_and_boxless_are_stored() {
        val ir = parse(
            """
            * World
            **_ America
            ***< Canada
            **> Europe
            """.trimIndent() + "\n",
        ).snapshot()
        val america = ir.root.children.first { (it.label as RichLabel.Plain).text == "America" }
        val canada = america.children.single()
        val europe = ir.root.children.first { (it.label as RichLabel.Plain).text == "Europe" }
        assertEquals("left", decodeSides(ir)[canada.id])
        assertEquals("right", decodeSides(ir)[europe.id])
        assertTrue(decodeBoxless(ir).contains(america.id))
    }

    @Test
    fun parses_multiline_nodes() {
        val ir = parse(
            """
            * Root
            **<:Line 1
            Line 2;
            """.trimIndent() + "\n",
            chunkSize = 3,
        ).snapshot()
        val child = ir.root.children.single()
        assertEquals("Line 1\nLine 2", (child.label as RichLabel.Plain).text)
        assertEquals("left", decodeSides(ir)[child.id])
    }

    @Test
    fun inline_color_is_stored_in_style_hints() {
        val ir = parse(
            """
            * [#lightblue] World
            ** [orange] America
            """.trimIndent() + "\n",
        ).snapshot()
        val colors = decodeInlineColors(ir)
        assertEquals("#lightblue", colors[ir.root.id])
        assertEquals("orange", colors[ir.root.children.single().id])
    }

    @Test
    fun stereotype_is_stored_and_reflected_in_label() {
        val ir = parse(
            """
            * <<vision>> World
            ** <<risk>> America
            """.trimIndent() + "\n",
        ).snapshot()
        val stereotypes = decodeStereotypes(ir)
        assertEquals("vision", stereotypes[ir.root.id])
        assertEquals("risk", stereotypes[ir.root.children.single().id])
        assertEquals("\u00ABvision\u00BB\nWorld", (ir.root.label as RichLabel.Plain).text)
    }

    @Test
    fun icon_and_emoji_are_stored_and_reflected_in_label() {
        val ir = parse(
            """
            * <&flag> World
            ** <:smile:> America
            ** <#green:sunny:> Europe
            """.trimIndent() + "\n",
        ).snapshot()
        val visuals = decodeLeadingVisuals(ir)
        assertEquals("icon,,flag", visuals[ir.root.id])
        assertEquals("emoji,,smile", visuals[ir.root.children[0].id])
        assertEquals("emoji,green,sunny", visuals[ir.root.children[1].id])
        assertEquals("FL World", (ir.root.label as RichLabel.Plain).text)
        assertEquals("SM America", (ir.root.children[0].label as RichLabel.Plain).text)
    }

    @Test
    fun style_color_is_resolved_from_style_block_and_multiline_suffix() {
        val ir = parse(
            """
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
            ** This is the partner workpackage <<branch>>
            *** Child
            **:This is on multiple
            lines; <<pink>>
            """.trimIndent() + "\n",
        ).snapshot()
        val colors = decodeStyleColors(ir)
        assertEquals("SkyBlue", colors[ir.root.children[0].children.single().id])
        assertEquals("pink", colors[ir.root.children[1].id])
        assertTrue(ir.styleHints.extras[PlantUmlWbsParser.STEREOTYPE_KEY].isNullOrEmpty(), "style class should not be rendered as stereotype")
        assertEquals("This is on multiple\nlines", (ir.root.children[1].label as RichLabel.Plain).text)
    }

    @Test
    fun line_font_and_round_corner_are_resolved_from_style_block() {
        val ir = parse(
            """
            <style>
            wbsDiagram {
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
            ***_ Child
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
    fun advanced_style_fields_are_resolved_and_inherited_from_style_block() {
        val ir = parse(
            """
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
        val node = ir.root.children[0]
        val child = ir.root.children[1].children.single()
        assertEquals("JetBrains Mono", decodeMap(ir, PlantUmlWbsParser.STYLE_FONT_NAME_KEY)[node.id])
        assertEquals("18", decodeMap(ir, PlantUmlWbsParser.STYLE_FONT_SIZE_KEY)[node.id])
        assertEquals("bold italic", decodeMap(ir, PlantUmlWbsParser.STYLE_FONT_STYLE_KEY)[node.id])
        assertEquals("3", decodeMap(ir, PlantUmlWbsParser.STYLE_LINE_THICKNESS_KEY)[node.id])
        assertEquals("true", decodeMap(ir, PlantUmlWbsParser.STYLE_SHADOWING_KEY)[node.id])
        assertEquals("120", decodeMap(ir, PlantUmlWbsParser.STYLE_MAXIMUM_WIDTH_KEY)[node.id])
        assertEquals("15", decodeMap(ir, PlantUmlWbsParser.STYLE_FONT_SIZE_KEY)[child.id])
        assertEquals("4", decodeMap(ir, PlantUmlWbsParser.STYLE_LINE_THICKNESS_KEY)[child.id])
        assertEquals("false", decodeMap(ir, PlantUmlWbsParser.STYLE_SHADOWING_KEY)[child.id])
        assertEquals("96", decodeMap(ir, PlantUmlWbsParser.STYLE_MAXIMUM_WIDTH_KEY)[child.id])
    }

    @Test
    fun missing_endwbs_is_reported() {
        val parser = parse(
            """
            * Root
            ** Child
            """.trimIndent() + "\n",
            blockClosed = false,
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E012" && it.message.contains("@endwbs") })
    }

    private fun decodeSides(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.SIDE_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeBoxless(ir: TreeIR): Set<NodeId> =
        ir.styleHints.extras[PlantUmlWbsParser.BOXLESS_KEY]
            .orEmpty()
            .split("||")
            .filter { it.isNotEmpty() }
            .map(::NodeId)
            .toSet()

    private fun decodeInlineColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.INLINE_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStereotypes(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.STEREOTYPE_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeLeadingVisuals(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.LEADING_VISUAL_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.STYLE_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleLineColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.STYLE_LINE_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleFontColors(ir: TreeIR): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[PlantUmlWbsParser.STYLE_FONT_COLOR_KEY].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }

    private fun decodeStyleRoundCorners(ir: TreeIR): Map<NodeId, String> =
        decodeMap(ir, PlantUmlWbsParser.STYLE_ROUND_CORNER_KEY)

    private fun decodeMap(ir: TreeIR, key: String): Map<NodeId, String> =
        Regex("""([^|]+)\|([^|]+)""").findAll(ir.styleHints.extras[key].orEmpty()).associate {
            NodeId(it.groupValues[1]) to it.groupValues[2]
        }
}

package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlObjectParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlObjectParser {
        val parser = PlantUmlObjectParser()
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
        parser.finish(blockClosed = true)
        return parser
    }

    @Test
    fun object_declarations_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order
                object "Customer #1" as Customer
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(RichLabel.Plain("Order"), ir.nodes.first { it.id == NodeId("Order") }.label)
        assertEquals(RichLabel.Plain("Customer #1"), ir.nodes.first { it.id == NodeId("Customer") }.label)
    }

    @Test
    fun object_member_block_is_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order {
                  id = 1
                  total = 99
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val payload = ir.nodes.single().payload[PlantUmlObjectParser.MEMBERS_KEY]
        assertEquals("id = 1\ntotal = 99", payload)
    }

    @Test
    fun dotted_member_is_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order
                Order : id = 1
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("id = 1", ir.nodes.single().payload[PlantUmlObjectParser.MEMBERS_KEY])
    }

    @Test
    fun relations_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                object Order
                object Customer
                Order --> Customer : owner
                Customer .. Order
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(2, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges[0].arrow)
        assertEquals(EdgeKind.Dashed, ir.edges[1].kind)
    }

    @Test
    fun direction_supported() {
        val ir = parse("top to bottom direction\nobject Order\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun note_package_and_map_json_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                package "Domain" {
                  namespace "Orders" {
                    map Cache {
                      key => value
                    }
                    json Payload {
                      orderId: 1
                    }
                    object Order
                    note right of Order : aggregate
                  }
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertTrue(ir.clusters.isNotEmpty())
        assertTrue(ir.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "map" })
        assertTrue(ir.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "json" })
        assertTrue(ir.nodes.any { it.payload[PlantUmlObjectParser.KIND_KEY] == "note" })
        assertTrue(ir.edges.any { it.arrow == ArrowEnds.None && it.kind == EdgeKind.Dashed })
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            object Order {
              id = 1
            }
            object Customer
            Order --> Customer
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }

    @Test
    fun object_skinparam_entries_are_stored_in_style_hints() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                skinparam object {
                  BackgroundColor LightYellow
                  BorderColor Orange
                  FontColor Navy
                  FontSize 17
                  FontName monospace
                  LineThickness 2.5
                  Shadowing true
                }
                skinparam note {
                  BackgroundColor Ivory
                  BorderColor Peru
                  FontColor Red
                  FontSize 15
                  FontName serif
                  LineThickness 2
                  Shadowing true
                }
                skinparam package {
                  BackgroundColor LightGray
                  BorderColor Silver
                  FontColor Green
                  FontSize 16
                  FontName sans-serif
                  LineThickness 2.25
                  Shadowing true
                }
                skinparam ArrowColor Blue
                package Domain {
                  object Order
                  note right of Order : aggregate
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("LightYellow", ir.styleHints.extras[PlantUmlObjectParser.STYLE_OBJECT_FILL_KEY])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlObjectParser.STYLE_OBJECT_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlObjectParser.STYLE_OBJECT_TEXT_KEY])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlObjectParser.STYLE_NOTE_FILL_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlObjectParser.STYLE_NOTE_STROKE_KEY])
        assertEquals("Red", ir.styleHints.extras[PlantUmlObjectParser.STYLE_NOTE_TEXT_KEY])
        assertEquals("15", ir.styleHints.extras[PlantUmlObjectParser.styleFontSizeKey("note")])
        assertEquals("serif", ir.styleHints.extras[PlantUmlObjectParser.styleFontNameKey("note")])
        assertEquals("2", ir.styleHints.extras[PlantUmlObjectParser.styleLineThicknessKey("note")])
        assertEquals("true", ir.styleHints.extras[PlantUmlObjectParser.styleShadowingKey("note")])
        assertEquals("LightGray", ir.styleHints.extras[PlantUmlObjectParser.STYLE_PACKAGE_FILL_KEY])
        assertEquals("Silver", ir.styleHints.extras[PlantUmlObjectParser.STYLE_PACKAGE_STROKE_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlObjectParser.STYLE_PACKAGE_TEXT_KEY])
        assertEquals("17", ir.styleHints.extras[PlantUmlObjectParser.styleFontSizeKey("object")])
        assertEquals("monospace", ir.styleHints.extras[PlantUmlObjectParser.styleFontNameKey("object")])
        assertEquals("2.5", ir.styleHints.extras[PlantUmlObjectParser.styleLineThicknessKey("object")])
        assertEquals("true", ir.styleHints.extras[PlantUmlObjectParser.styleShadowingKey("object")])
        assertEquals("16", ir.styleHints.extras[PlantUmlObjectParser.styleFontSizeKey("package")])
        assertEquals("sans-serif", ir.styleHints.extras[PlantUmlObjectParser.styleFontNameKey("package")])
        assertEquals("2.25", ir.styleHints.extras[PlantUmlObjectParser.styleLineThicknessKey("package")])
        assertEquals("true", ir.styleHints.extras[PlantUmlObjectParser.styleShadowingKey("package")])
        assertEquals("Blue", ir.styleHints.extras[PlantUmlObjectParser.STYLE_EDGE_COLOR_KEY])
    }
}

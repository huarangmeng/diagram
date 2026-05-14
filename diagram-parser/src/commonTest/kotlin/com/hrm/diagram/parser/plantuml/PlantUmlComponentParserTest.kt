package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlComponentParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlComponentParser {
        val parser = PlantUmlComponentParser()
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
    fun component_interface_and_port_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                component "API" as Api {
                  portin In
                  portout Out
                }
                interface "HTTP" as Http
                () "gRPC" as Grpc
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(NodeShape.Component, ir.nodes.first { it.id == NodeId("Api") }.shape)
        assertEquals(NodeShape.Circle, ir.nodes.first { it.id == NodeId("Http") }.shape)
        assertEquals(NodeShape.Circle, ir.nodes.first { it.id == NodeId("Grpc") }.shape)
        assertEquals("in", ir.nodes.first { it.id == NodeId("In") }.payload[PlantUmlComponentParser.PORT_DIR_KEY])
        assertEquals("out", ir.nodes.first { it.id == NodeId("Out") }.payload[PlantUmlComponentParser.PORT_DIR_KEY])
        assertEquals("Api", ir.nodes.first { it.id == NodeId("In") }.payload[PlantUmlComponentParser.PORT_HOST_KEY])
        assertEquals("Api", ir.nodes.first { it.id == NodeId("Out") }.payload[PlantUmlComponentParser.PORT_HOST_KEY])
    }

    @Test
    fun database_queue_frame_rectangle_and_note_are_parsed() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                frame Runtime {
                  rectangle Services {
                    component Api
                    database Db
                    queue Jobs
                  }
                }
                note right of Api
                  handles ingress
                  and validation
                end note
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(NodeShape.Cylinder, ir.nodes.first { it.id == NodeId("Db") }.shape)
        assertEquals("queue", ir.nodes.first { it.id == NodeId("Jobs") }.payload[PlantUmlComponentParser.KIND_KEY])
        assertEquals(1, ir.clusters.size)
        assertEquals("frame", (ir.clusters.single().label as RichLabel.Plain).text.substringBefore('\n'))
        val note = ir.nodes.first { it.payload[PlantUmlComponentParser.KIND_KEY] == "note" }
        assertEquals("Api", note.payload[PlantUmlComponentParser.NOTE_TARGET_KEY])
        assertTrue(ir.edges.any { it.from == note.id && it.to == NodeId("Api") })
    }

    @Test
    fun clusters_build_hierarchy() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                package Backend {
                  cloud Aws {
                    component Api
                  }
                  node Runtime {
                    interface Http
                  }
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(1, ir.clusters.size)
        val backend = ir.clusters.single()
        assertEquals("Backend", backend.id.value)
        assertEquals(2, backend.nestedClusters.size)
        assertTrue(backend.nestedClusters.any { it.id.value == "Aws" })
        assertTrue(backend.nestedClusters.any { it.id.value == "Runtime" })
    }

    @Test
    fun bracket_form_and_implicit_nodes_supported() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                [Gateway] as Gw
                Gw --> Downstream
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(RichLabel.Plain("Gateway"), ir.nodes.first { it.id == NodeId("Gw") }.label)
        assertTrue(ir.nodes.any { it.id == NodeId("Downstream") })
    }

    @Test
    fun bracket_endpoint_relations_create_implicit_component_nodes() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                [Web] --> [API]
                [API] --> [DB] : queries
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertTrue(ir.nodes.any { it.id == NodeId("Web") && it.shape == NodeShape.Component })
        assertTrue(ir.nodes.any { it.id == NodeId("API") && it.shape == NodeShape.Component })
        assertTrue(ir.nodes.any { it.id == NodeId("DB") && it.shape == NodeShape.Component })
        assertEquals(2, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges[0].arrow)
        assertEquals(NodeId("Web"), ir.edges[0].from)
        assertEquals(NodeId("API"), ir.edges[0].to)
        assertEquals(RichLabel.Plain("queries"), ir.edges[1].label)
    }

    @Test
    fun relations_parse_arrow_and_dash_variants() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                component A
                component B
                A --> B : uses
                B ..> A : callback
                A -- B
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(3, ir.edges.size)
        assertEquals(ArrowEnds.ToOnly, ir.edges[0].arrow)
        assertEquals(EdgeKind.Dashed, ir.edges[1].kind)
        assertEquals(ArrowEnds.None, ir.edges[2].arrow)
    }

    @Test
    fun direction_supported() {
        val ir = parse("top to bottom direction\ncomponent A\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            package Backend {
              component Api
              interface Http
            }
            Api --> Http : serves
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }

    @Test
    fun component_skinparam_entries_are_stored_in_style_hints() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                skinparam component {
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
                package Backend {
                  component Api
                  note right of Api : ingress
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("LightYellow", ir.styleHints.extras[PlantUmlComponentParser.STYLE_COMPONENT_FILL_KEY])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlComponentParser.STYLE_COMPONENT_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlComponentParser.STYLE_COMPONENT_TEXT_KEY])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlComponentParser.STYLE_NOTE_FILL_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlComponentParser.STYLE_NOTE_STROKE_KEY])
        assertEquals("Red", ir.styleHints.extras[PlantUmlComponentParser.STYLE_NOTE_TEXT_KEY])
        assertEquals("15", ir.styleHints.extras[PlantUmlComponentParser.styleFontSizeKey("note")])
        assertEquals("serif", ir.styleHints.extras[PlantUmlComponentParser.styleFontNameKey("note")])
        assertEquals("2", ir.styleHints.extras[PlantUmlComponentParser.styleLineThicknessKey("note")])
        assertEquals("true", ir.styleHints.extras[PlantUmlComponentParser.styleShadowingKey("note")])
        assertEquals("LightGray", ir.styleHints.extras[PlantUmlComponentParser.STYLE_PACKAGE_FILL_KEY])
        assertEquals("Silver", ir.styleHints.extras[PlantUmlComponentParser.STYLE_PACKAGE_STROKE_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlComponentParser.STYLE_PACKAGE_TEXT_KEY])
        assertEquals("17", ir.styleHints.extras[PlantUmlComponentParser.styleFontSizeKey("component")])
        assertEquals("monospace", ir.styleHints.extras[PlantUmlComponentParser.styleFontNameKey("component")])
        assertEquals("2.5", ir.styleHints.extras[PlantUmlComponentParser.styleLineThicknessKey("component")])
        assertEquals("true", ir.styleHints.extras[PlantUmlComponentParser.styleShadowingKey("component")])
        assertEquals("16", ir.styleHints.extras[PlantUmlComponentParser.styleFontSizeKey("package")])
        assertEquals("sans-serif", ir.styleHints.extras[PlantUmlComponentParser.styleFontNameKey("package")])
        assertEquals("2.25", ir.styleHints.extras[PlantUmlComponentParser.styleLineThicknessKey("package")])
        assertEquals("true", ir.styleHints.extras[PlantUmlComponentParser.styleShadowingKey("package")])
        assertEquals("Blue", ir.styleHints.extras[PlantUmlComponentParser.STYLE_EDGE_COLOR_KEY])
    }
}

package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlDeploymentParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlDeploymentParser {
        val parser = PlantUmlDeploymentParser()
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
    fun parses_artifact_database_and_cloud_nodes() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                artifact "App" as App
                database MainDb
                cloud Aws
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(NodeShape.Note, ir.nodes.first { it.id == NodeId("App") }.shape)
        assertEquals(NodeShape.Cylinder, ir.nodes.first { it.id == NodeId("MainDb") }.shape)
        assertEquals(NodeShape.Cloud, ir.nodes.first { it.id == NodeId("Aws") }.shape)
    }

    @Test
    fun parses_nested_node_cluster() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                node Server {
                  [App]
                  database MainDb
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(1, ir.clusters.size)
        assertTrue(ir.clusters.single().children.contains(NodeId("App")))
        assertTrue(ir.clusters.single().children.contains(NodeId("MainDb")))
    }

    @Test
    fun parses_relations_and_labels() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                node Server {
                  [App]
                }
                database MainDb
                App --> MainDb : jdbc
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(1, ir.edges.size)
        assertEquals(RichLabel.Plain("jdbc"), ir.edges.single().label)
    }

    @Test
    fun parses_actor_queue_storage_and_note() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                node Server {
                  actor Ops
                  queue Jobs
                  storage Disk
                  [App]
                  note right of App
                    deployed by ops
                  end note
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(NodeShape.Actor, ir.nodes.first { it.id == NodeId("Ops") }.shape)
        assertEquals("queue", ir.nodes.first { it.id == NodeId("Jobs") }.payload[PlantUmlDeploymentParser.KIND_KEY])
        assertEquals("storage", ir.nodes.first { it.id == NodeId("Disk") }.payload[PlantUmlDeploymentParser.KIND_KEY])
        val note = ir.nodes.first { it.payload[PlantUmlDeploymentParser.KIND_KEY] == "note" }
        assertEquals("App", note.payload[PlantUmlDeploymentParser.NOTE_TARGET_KEY])
        assertTrue(ir.edges.any { it.from == note.id && it.to == NodeId("App") })
    }

    @Test
    fun supports_direction() {
        val ir = parse("top to bottom direction\nartifact App\n").snapshot()
        assertEquals(Direction.TB, ir.styleHints.direction)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            node Server {
              [App]
            }
            database MainDb
            App --> MainDb
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }

    @Test
    fun deployment_skinparam_entries_are_stored_in_style_hints() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                skinparam artifact {
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
                skinparam node {
                  BackgroundColor LightGray
                  BorderColor Silver
                  FontColor Green
                  FontSize 16
                  FontName sans-serif
                  LineThickness 2.25
                  Shadowing true
                }
                skinparam ArrowColor Blue
                node Server {
                  [App]
                  note right of App : deploy
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("LightYellow", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_ARTIFACT_FILL_KEY])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_ARTIFACT_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_ARTIFACT_TEXT_KEY])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_NOTE_FILL_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_NOTE_STROKE_KEY])
        assertEquals("Red", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_NOTE_TEXT_KEY])
        assertEquals("15", ir.styleHints.extras[PlantUmlDeploymentParser.styleFontSizeKey("note")])
        assertEquals("serif", ir.styleHints.extras[PlantUmlDeploymentParser.styleFontNameKey("note")])
        assertEquals("2", ir.styleHints.extras[PlantUmlDeploymentParser.styleLineThicknessKey("note")])
        assertEquals("true", ir.styleHints.extras[PlantUmlDeploymentParser.styleShadowingKey("note")])
        assertEquals("LightGray", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_NODE_FILL_KEY])
        assertEquals("Silver", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_NODE_STROKE_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_NODE_TEXT_KEY])
        assertEquals("17", ir.styleHints.extras[PlantUmlDeploymentParser.styleFontSizeKey("artifact")])
        assertEquals("monospace", ir.styleHints.extras[PlantUmlDeploymentParser.styleFontNameKey("artifact")])
        assertEquals("2.5", ir.styleHints.extras[PlantUmlDeploymentParser.styleLineThicknessKey("artifact")])
        assertEquals("true", ir.styleHints.extras[PlantUmlDeploymentParser.styleShadowingKey("artifact")])
        assertEquals("16", ir.styleHints.extras[PlantUmlDeploymentParser.styleFontSizeKey("node")])
        assertEquals("sans-serif", ir.styleHints.extras[PlantUmlDeploymentParser.styleFontNameKey("node")])
        assertEquals("2.25", ir.styleHints.extras[PlantUmlDeploymentParser.styleLineThicknessKey("node")])
        assertEquals("true", ir.styleHints.extras[PlantUmlDeploymentParser.styleShadowingKey("node")])
        assertEquals("Blue", ir.styleHints.extras[PlantUmlDeploymentParser.STYLE_EDGE_COLOR_KEY])
    }
}

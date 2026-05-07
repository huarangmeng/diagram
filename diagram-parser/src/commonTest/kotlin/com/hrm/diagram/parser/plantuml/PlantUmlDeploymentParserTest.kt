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
}

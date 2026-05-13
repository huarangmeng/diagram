package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlNetworkParserTest {
    @Test
    fun parses_networks_addresses_nodes_and_multi_homed_edges() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                nwdiag {
                  network dmz {
                    address = "210.x.x.x/24"
                    web [address = "210.x.x.10", description = "frontend"];
                  }
                  network internal {
                    address = "172.16.x.x/24"
                    web;
                    db [address = "172.16.x.20"];
                  }
                }
                """.trimIndent(),
            ).snapshot(),
        )

        assertEquals(3, ir.nodes.size)
        assertEquals(2, ir.clusters.size)
        assertTrue(ir.nodes.any { it.id.value == "nw_dmz_web" && "frontend" in labelOf(it.label) })
        assertTrue(ir.nodes.any { it.id.value == "nw_internal_db" && "172.16.x.20" in labelOf(it.label) })
        assertEquals(1, ir.edges.size)
        assertEquals(EdgeKind.Dashed, ir.edges.single().kind)
        assertEquals("nw_dmz_web", ir.edges.single().from.value)
        assertEquals("nw_internal_web", ir.edges.single().to.value)
    }

    @Test
    fun missing_closing_block_is_reported() {
        val parser = parse(
            """
            nwdiag {
              network dmz {
                web;
            """.trimIndent(),
            blockClosed = false,
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E014" })
    }

    @Test
    fun parses_group_inside_network_as_nested_cluster() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                nwdiag {
                  network office {
                    address = "10.0.0.0/24"
                    group servers {
                      app;
                      db [address = "10.0.0.20"];
                    }
                    laptop;
                  }
                }
                """.trimIndent(),
            ).snapshot(),
        )

        assertEquals(3, ir.nodes.size)
        assertEquals(1, ir.clusters.size)
        val network = ir.clusters.single()
        assertEquals(listOf("nw_office_laptop"), network.children.map { it.value })
        assertEquals(1, network.nestedClusters.size)
        val group = network.nestedClusters.single()
        assertEquals("nw_office_group_servers", group.id.value)
        assertEquals(listOf("nw_office_app", "nw_office_db"), group.children.map { it.value })
    }

    @Test
    fun parses_inet_shapes_colors_and_explicit_edges() {
        val ir = assertIs<GraphIR>(
            parse(
                """
                nwdiag {
                  inet internet {
                    router [shape = cloud, label = "Internet", color = "#E0F7FA"];
                  }
                  network office {
                    address = "10.0.0.0/24"
                    client [shape = actor, description = "user"];
                    app [shape = component, color = "orange"];
                    db [shape = database, address = "10.0.0.20"];
                    mq [shape = queue];
                    app -> db : sql;
                    client <-> app : https;
                  }
                }
                """.trimIndent(),
            ).snapshot(),
        )

        assertEquals(5, ir.nodes.size)
        assertEquals(2, ir.clusters.size)
        assertTrue(ir.clusters.any { labelOf(it.label!!).startsWith("inet\ninternet") })
        assertEquals(NodeShape.Cloud, ir.nodes.single { it.id.value == "nw_internet_router" }.shape)
        assertEquals(NodeShape.Actor, ir.nodes.single { it.id.value == "nw_office_client" }.shape)
        assertEquals(NodeShape.Component, ir.nodes.single { it.id.value == "nw_office_app" }.shape)
        assertEquals(NodeShape.Cylinder, ir.nodes.single { it.id.value == "nw_office_db" }.shape)
        assertEquals(NodeShape.Hexagon, ir.nodes.single { it.id.value == "nw_office_mq" }.shape)
        assertTrue(ir.edges.any { labelOf(it.label!!) == "sql" })
        assertTrue(ir.edges.any { labelOf(it.label!!) == "https" && it.arrow == com.hrm.diagram.core.ir.ArrowEnds.Both })
    }

    private fun parse(source: String, blockClosed: Boolean = true): PlantUmlNetworkParser {
        val parser = PlantUmlNetworkParser()
        source.lines().forEach { parser.acceptLine(it) }
        parser.finish(blockClosed)
        return parser
    }

    private fun labelOf(label: com.hrm.diagram.core.ir.RichLabel): String =
        when (label) {
            is com.hrm.diagram.core.ir.RichLabel.Plain -> label.text
            is com.hrm.diagram.core.ir.RichLabel.Markdown -> label.source
            is com.hrm.diagram.core.ir.RichLabel.Html -> label.html
        }
}

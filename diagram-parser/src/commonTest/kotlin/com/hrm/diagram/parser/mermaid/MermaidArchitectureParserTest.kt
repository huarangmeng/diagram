package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidArchitectureParserTest {
    private fun feedAll(src: String): MermaidArchitectureParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) {
                    lines += cur
                    cur = ArrayList()
                }
            } else {
                cur += t
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return MermaidArchitectureParser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_groups_services_junctions_and_group_boundary_edges() {
        val parser = feedAll(
            """
            architecture-beta
              group platform(cloud)[Platform]
              group data(database)[Data] in platform
              service api(server)[API] in platform
              service db(database)[Primary DB] in data
              junction fanout in platform
              api:R --> L:fanout
              fanout:B --> T:db{group}
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(3, ir.nodes.size)
        assertEquals(2, ir.edges.size)
        assertEquals(1, ir.clusters.size)
        val platform = ir.clusters.single()
        assertEquals("platform", platform.id.value)
        assertEquals(listOf("api", "fanout"), platform.children.map { it.value })
        assertEquals(1, platform.nestedClusters.size)
        assertEquals("data", platform.nestedClusters.single().id.value)
        assertEquals(listOf("db"), platform.nestedClusters.single().children.map { it.value })

        val api = ir.nodes.first { it.id.value == "api" }
        assertEquals("service", api.payload[MermaidArchitectureParser.KIND_KEY])
        assertEquals("server", api.payload[MermaidArchitectureParser.ICON_KEY])
        assertEquals("platform", api.payload[MermaidArchitectureParser.PARENT_KEY])
        assertEquals("API", (api.label as? RichLabel.Plain)?.text)

        val fanout = ir.nodes.first { it.id.value == "fanout" }
        assertEquals("junction", fanout.payload[MermaidArchitectureParser.KIND_KEY])

        val fanoutToGroup = ir.edges.last()
        assertEquals("fanout", fanoutToGroup.from.value)
        assertEquals("db", fanoutToGroup.to.value)
        assertEquals("NODE@B", fanoutToGroup.fromPort?.value)
        assertEquals("GROUP@data@T", fanoutToGroup.toPort?.value)
        assertEquals(ArrowEnds.ToOnly, fanoutToGroup.arrow)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun reports_unknown_parent_group() {
        val parser = feedAll(
            """
            architecture-beta
              service api(server)[API] in missing
            """.trimIndent() + "\n",
        )

        val diagnostics = parser.diagnosticsSnapshot()
        assertEquals(1, diagnostics.size)
        assertEquals("MERMAID-E212", diagnostics.single().code)
        assertTrue(diagnostics.single().message.contains("Unknown parent group"))
    }

    @Test
    fun supports_services_without_builtin_icon_and_iconify_names() {
        val parser = feedAll(
            """
            architecture-beta
              group public_api(logos:aws-lambda)[Public API]
              service server[Server] in public_api
              service db(logos:aws-rds)[Database] in public_api
              server:R --> L:db
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        val server = ir.nodes.first { it.id.value == "server" }
        val db = ir.nodes.first { it.id.value == "db" }
        assertEquals("", server.payload[MermaidArchitectureParser.ICON_KEY])
        assertEquals("logos:aws-rds", db.payload[MermaidArchitectureParser.ICON_KEY])
        assertEquals("logos:aws-lambda", (ir.clusters.single().label as? RichLabel.Plain)?.text?.substringAfter("__icon:")?.substringBefore('\n'))
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }
}

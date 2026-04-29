package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidC4ParserTest {
    private fun feedAll(src: String): MermaidC4Parser {
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
        return MermaidC4Parser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_container_boundary_relations_and_style_updates() {
        val parser = feedAll(
            """
            C4Container
              title Container diagram
              Person(user, "User", "bank customer")
              System_Ext(mail, "Mail", "internal mail")
              Container_Boundary(app, "Internet Banking") {
                Container(web, "Web App", "Spring MVC", "Delivers UI")
                ContainerDb(db, "Database", "PostgreSQL", "Stores data")
              }
              Rel(user, web, "Uses", "HTTPS")
              Rel_Back(db, web, "Reads from", "JDBC")
              UpdateElementStyle(web, ${'$'}bgColor="#f96", ${'$'}fontColor="#123456", ${'$'}borderColor="#333333")
              UpdateRelStyle(user, web, ${'$'}textColor="blue", ${'$'}lineColor="#111111", ${'$'}offsetX="20", ${'$'}offsetY="-10")
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals("Container diagram", ir.title)
        assertEquals(4, ir.nodes.size)
        assertEquals(2, ir.edges.size)
        assertEquals(1, ir.clusters.size)
        assertEquals("app", ir.clusters.single().id.value)
        assertEquals(listOf("web", "db"), ir.clusters.single().children.map { it.value })

        val web = ir.nodes.first { it.id.value == "web" }
        assertEquals(0xFFFF9966.toInt(), web.style.fill?.argb)
        assertEquals(0xFF333333.toInt(), web.style.stroke?.argb)
        assertEquals(0xFF123456.toInt(), web.style.textColor?.argb)
        assertTrue((web.label as? RichLabel.Plain)?.text.orEmpty().contains("[Spring MVC]"))

        val firstEdge = ir.edges.first()
        assertEquals("user", firstEdge.from.value)
        assertEquals("web", firstEdge.to.value)
        assertEquals(0xFF111111.toInt(), firstEdge.style.color?.argb)

        val presentation = parser.edgePresentationSnapshot()[0]
        assertNotNull(presentation)
        assertEquals(0xFF0000FF.toInt(), presentation.textColor?.argb)
        assertEquals(20f, presentation.offsetX)
        assertEquals(-10f, presentation.offsetY)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_dynamic_header_and_rel_index() {
        val parser = feedAll(
            """
            C4Dynamic
              Container(a, "App", "Kotlin", "frontend")
              Component(b, "Auth", "Spring", "auth service")
              RelIndex(1, a, b, "Calls", "JSON/HTTPS")
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals("C4Dynamic", ir.styleHints.extras["c4.diagramKind"])
        assertEquals(2, ir.nodes.size)
        assertEquals(1, ir.edges.size)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun parses_links_tags_and_legend_entries() {
        val parser = feedAll(
            """
            C4Container
              AddElementTag("v1.0", ${'$'}bgColor="#FFF3E0", ${'$'}borderColor="#FB8C00", ${'$'}fontColor="#E65100", ${'$'}legendText="Version 1.0")
              AddRelTag("async", ${'$'}textColor="#6A1B9A", ${'$'}lineColor="#8E24AA", ${'$'}legendText="Async Link")
              Person(user, "User", "bank customer", ${'$'}tags="v1.0", ${'$'}link="https://example.com/user")
              Container(api, "API", "Ktor", "Backend", ${'$'}tags="v1.0")
              Rel(user, api, "Uses", "HTTPS", ${'$'}tags="async", ${'$'}link="https://example.com/rel")
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        val user = ir.nodes.first { it.id.value == "user" }
        assertEquals(0xFFFFF3E0.toInt(), user.style.fill?.argb)
        assertEquals(0xFFFB8C00.toInt(), user.style.stroke?.argb)
        assertEquals(0xFFE65100.toInt(), user.style.textColor?.argb)
        assertEquals("https://example.com/user", parser.nodeLinkSnapshot()[user.id])
        assertEquals("https://example.com/rel", parser.edgeLinkSnapshot()[0])
        val legend = parser.legendSnapshot()
        assertTrue(legend.any { it.kind == "element" && it.text == "Version 1.0" })
        assertTrue(legend.any { it.kind == "relationship" && it.text == "Async Link" })
        assertEquals(0xFF8E24AA.toInt(), ir.edges.single().style.color?.argb)
        assertEquals(0xFF6A1B9A.toInt(), parser.edgePresentationSnapshot()[0]?.textColor?.argb)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun supports_c4_shape_and_line_style_helpers() {
        val parser = feedAll(
            """
            C4Container
              AddElementTag("rounded", ${'$'}shape="RoundedBoxShape()", ${'$'}legendText="Rounded")
              AddElementTag("octa", ${'$'}shape="EightSidedShape()")
              AddRelTag("dashed", ${'$'}lineStyle="DashedLine()")
              AddRelTag("bold", ${'$'}lineStyle="BoldLine()")
              Person(user, "User", "bank customer", ${'$'}tags="rounded")
              Container(api, "API", "Ktor", "Backend", ${'$'}tags="octa")
              Rel(user, api, "Uses", "HTTPS", ${'$'}tags="dashed")
              UpdateRelStyle(user, api, ${'$'}lineStyle="BoldLine()")
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        val user = ir.nodes.first { it.id.value == "user" }
        val api = ir.nodes.first { it.id.value == "api" }
        assertEquals(NodeShape.RoundedBox, user.shape)
        assertEquals(NodeShape.Custom("octagon"), api.shape)
        assertEquals(3f, ir.edges.single().style.width)
        assertTrue(ir.edges.single().style.dash.isNullOrEmpty() || ir.edges.single().style.dash == listOf(8f, 6f))
        assertTrue(parser.legendSnapshot().any { it.text == "Rounded" })
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }
}

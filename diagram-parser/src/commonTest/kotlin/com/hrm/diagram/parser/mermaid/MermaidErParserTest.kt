package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidErParserTest {

    private fun feedAll(src: String): MermaidErParser {
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        // Group tokens into logical lines (drop NEWLINE tokens).
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur

        val p = MermaidErParser()
        for (l in lines) p.acceptLine(l)
        return p
    }

    @Test
    fun header_required() {
        val p = MermaidErParser()
        p.acceptLine(listOf(Token(MermaidTokenKind.IDENT, 0, 1, "X")))
        assertTrue(p.diagnosticsSnapshot().isNotEmpty())
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("erDiagram") })
    }

    @Test
    fun entity_block_attributes_and_relationship() {
        val p = feedAll(
            """
            erDiagram
            CAR {
              string make PK
              string model
            }
            CAR ||--o{ PERSON : allows
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val nodeIds = ir.nodes.map { it.id.value }.toSet()
        assertTrue("CAR" in nodeIds)
        assertTrue("PERSON" in nodeIds)
        assertTrue("CAR::make" in nodeIds)
        assertTrue("CAR::model" in nodeIds)

        // 2 attribute edges + 1 relationship edge.
        assertEquals(3, ir.edges.size)
        val rel = ir.edges.last()
        assertEquals(NodeId("CAR"), rel.from)
        assertEquals(NodeId("PERSON"), rel.to)
        assertEquals("||--o{ allows", (rel.label as RichLabel.Plain).text)
    }

    @Test
    fun attribute_flags_and_styles_are_preserved_in_ir() {
        val p = feedAll(
            """
            erDiagram
            USER {
              uuid id PK
              uuid account_id FK
              string email UK
              string nickname
            }
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        val byId = ir.nodes.associateBy { it.id.value }

        val entity = assertNotNull(byId["USER"])
        assertEquals(MermaidErParser.ER_ENTITY_KIND, entity.payload[MermaidErParser.ER_KIND_KEY])

        val pk = assertNotNull(byId["USER::id"])
        assertEquals("uuid", pk.payload[MermaidErParser.ER_ATTRIBUTE_TYPE_KEY])
        assertEquals("PK", pk.payload[MermaidErParser.ER_ATTRIBUTE_FLAGS_KEY])
        assertTrue(pk.style.fill != null)
        assertTrue(pk.style.stroke != null)

        val fk = assertNotNull(byId["USER::account_id"])
        assertEquals("FK", fk.payload[MermaidErParser.ER_ATTRIBUTE_FLAGS_KEY])

        val uk = assertNotNull(byId["USER::email"])
        assertEquals("UK", uk.payload[MermaidErParser.ER_ATTRIBUTE_FLAGS_KEY])

        val plain = assertNotNull(byId["USER::nickname"])
        assertTrue(MermaidErParser.ER_ATTRIBUTE_FLAGS_KEY !in plain.payload)
    }

    @Test
    fun attribute_edges_are_weakened_and_relationship_edges_keep_cardinality_label() {
        val p = feedAll(
            """
            erDiagram
            ORDER {
              int id PK
            }
            ORDER ||--o{ LINE_ITEM : contains
            """.trimIndent() + "\n",
        )
        val ir = p.snapshot()
        assertEquals(2, ir.edges.size)

        val attrEdge = ir.edges.first()
        assertEquals(NodeId("ORDER"), attrEdge.from)
        assertEquals(NodeId("ORDER::id"), attrEdge.to)
        assertEquals(listOf(5f, 5f), attrEdge.style.dash)
        assertEquals(1f, attrEdge.style.width)
        assertTrue(attrEdge.label == null)

        val relEdge = ir.edges.last()
        assertEquals(NodeId("ORDER"), relEdge.from)
        assertEquals(NodeId("LINE_ITEM"), relEdge.to)
        assertEquals("||--o{ contains", (relEdge.label as RichLabel.Plain).text)
        assertEquals(1.5f, relEdge.style.width)
        assertEquals(0xFFF5F5F5.toInt(), relEdge.style.labelBg?.argb)
        assertTrue(relEdge.style.dash == null)
    }
}

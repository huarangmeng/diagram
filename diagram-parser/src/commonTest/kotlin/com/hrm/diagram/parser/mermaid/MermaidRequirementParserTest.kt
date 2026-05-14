package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidRequirementParserTest {
    private fun feedAll(src: String): MermaidRequirementParser {
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
                cur.add(t)
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return MermaidRequirementParser().also { parser ->
            for (line in lines) parser.acceptLine(line)
        }
    }

    @Test
    fun parses_requirement_element_and_relations() {
        val parser = feedAll(
            """
            requirementDiagram
              direction LR
              requirement test_req {
                id: 1
                text: the test text.
                risk: high
                verifymethod: test
              }
              element test_entity {
                type: simulation
                docRef: reqs/test_entity
              }
              test_entity - satisfies -> test_req
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        assertEquals(2, ir.nodes.size)
        assertEquals(1, ir.edges.size)
        assertEquals(com.hrm.diagram.core.ir.Direction.LR, ir.styleHints.direction)
        val req = ir.nodes.first { it.id.value == "test_req" }
        val reqLabel = labelTextOf(req.label)
        assertTrue(reqLabel.contains("<<Requirement>>"))
        assertTrue(reqLabel.contains("Risk: High"))
        assertEquals("1", req.payload[MermaidRequirementParser.REQUIREMENT_ID_KEY])
        assertEquals("the test text.", req.payload[MermaidRequirementParser.REQUIREMENT_TEXT_KEY])
        val relLabel = (ir.edges.single().label as? RichLabel.Plain)?.text.orEmpty()
        assertEquals("<<satisfies>>", relLabel)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun supports_special_requirement_kinds_and_reverse_relation() {
        val parser = feedAll(
            """
            requirementDiagram
              functionalRequirement req2 {
                id: 1.1
                text: second text
                risk: low
                verifymethod: inspection
              }
              element docNode {
                type: word doc
              }
              req2 <- copies - docNode
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        val req = ir.nodes.first { it.id.value == "req2" }
        val label = labelTextOf(req.label)
        assertTrue(label.contains("<<Functional Requirement>>"))
        assertEquals("docNode", ir.edges.single().from.value)
        assertEquals("req2", ir.edges.single().to.value)
    }

    @Test
    fun preserves_markdown_in_requirement_names_and_text() {
        val parser = feedAll(
            """
            requirementDiagram
              requirement "__test_req__" {
                id: 1
                text: "*italicized text* **bold text** and [link](https://example.test)"
                risk: high
                verifymethod: test
              }
            """.trimIndent() + "\n",
        )

        val ir = assertIs<GraphIR>(parser.snapshot())
        val req = ir.nodes.single()
        val label = assertIs<RichLabel.Markdown>(req.label).source
        assertTrue(label.contains("__test_req__"))
        assertTrue(label.contains("*italicized text* **bold text** and [link](https://example.test)"))
    }

    private fun labelTextOf(label: RichLabel): String = when (label) {
        is RichLabel.Plain -> label.text
        is RichLabel.Markdown -> label.source
        else -> ""
    }
}

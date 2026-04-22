package com.hrm.diagram.core.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IrTest {
    @Test
    fun graphIrEqualityIsStructural() {
        val a = GraphIR(
            nodes = listOf(Node(NodeId("a")), Node(NodeId("b"))),
            edges = listOf(Edge(NodeId("a"), NodeId("b"))),
            sourceLanguage = SourceLanguage.MERMAID,
        )
        val b = GraphIR(
            nodes = listOf(Node(NodeId("a")), Node(NodeId("b"))),
            edges = listOf(Edge(NodeId("a"), NodeId("b"))),
            sourceLanguage = SourceLanguage.MERMAID,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun pieIrComputesTotal() {
        val pie = PieIR(
            slices = listOf(
                PieSlice(RichLabel.of("A"), 30.0),
                PieSlice(RichLabel.of("B"), 70.0),
            ),
            sourceLanguage = SourceLanguage.MERMAID,
        )
        assertEquals(100.0, pie.total)
    }

    @Test
    fun nodeIdRejectsEmpty() {
        assertFailsWith<IllegalArgumentException> { NodeId("") }
    }

    @Test
    fun parseResultErrorAccounting() {
        val ok = ParseResult(model = null, diagnostics = listOf(
            Diagnostic(Severity.WARNING, "x", "MERMAID-W001"),
        ))
        assertTrue(!ok.hasErrors)
        val bad = ParseResult(model = null, diagnostics = listOf(
            Diagnostic(Severity.ERROR, "boom", "MERMAID-E001", Span(1,1,1,2)),
        ))
        assertTrue(bad.hasErrors)
        assertNotNull(bad.diagnostics.first().span)
    }

    @Test
    fun spanRejectsBackwards() {
        assertFailsWith<IllegalArgumentException> { Span(2, 1, 1, 1) }
    }
}

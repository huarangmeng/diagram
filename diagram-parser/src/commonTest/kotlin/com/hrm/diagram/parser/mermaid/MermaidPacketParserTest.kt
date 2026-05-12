package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidPacketParserTest {
    @Test
    fun parses_packet_ranges_and_single_bits() {
        val parser = MermaidPacketParser()
        listOf(
            "packet-beta",
            "title TCP Header",
            """0-15: "Source Port"""",
            """16-31: "Destination Port"""",
            """106: "URG"""",
        ).forEach { parser.acceptLine(line(it)) }

        val ir = assertIs<StructIR>(parser.snapshot())
        val root = assertIs<StructNode.ObjectNode>(ir.root)
        assertEquals("TCP Header", root.key)
        assertEquals(3, root.entries.size)
        assertEquals("0-15", root.entries[0].key)
        assertEquals("Source Port", assertIs<StructNode.Scalar>(root.entries[0]).value)
        assertEquals("106", root.entries[2].key)
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), "diagnostics=${parser.diagnosticsSnapshot()}")
    }

    @Test
    fun reports_missing_header() {
        val parser = MermaidPacketParser()
        parser.acceptLine(line("""0-15: "Source Port""""))
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "MERMAID-E215" })
    }

    private fun line(text: String): List<Token> =
        listOf(Token(MermaidTokenKind.IDENT, 0, text.length, text))
}

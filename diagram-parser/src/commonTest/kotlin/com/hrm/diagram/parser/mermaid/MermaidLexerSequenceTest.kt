package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidLexerSequenceTest {

    private fun lexAll(src: String, chunkSize: Int? = null): List<Token> {
        val lexer = MermaidLexer()
        var state = lexer.initialState()
        if (chunkSize == null) {
            return lexer.feed(state, src, 0, eos = true).tokens
        }
        val out = ArrayList<Token>()
        var i = 0
        while (i < src.length) {
            val end = (i + chunkSize).coerceAtMost(src.length)
            val isLast = end == src.length
            val step = lexer.feed(state, src.substring(i, end), i, eos = isLast)
            state = step.newState
            out += step.tokens
            i = end
        }
        return out
    }

    private fun kinds(src: String): List<Int> = lexAll(src).map { it.kind }

    @Test fun seq_mode_flip_on_sequenceDiagram() {
        val toks = lexAll("sequenceDiagram\n")
        assertEquals(MermaidTokenKind.SEQUENCE_HEADER, toks[0].kind)
    }

    @Test fun seq_arrow_sync() {
        val toks = lexAll("sequenceDiagram\nA ->> B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(MermaidTokenKind.MSG_ARROW_SYNC, toks[2].kind)
    }

    @Test fun seq_arrow_async() {
        val toks = lexAll("sequenceDiagram\nA -> B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(MermaidTokenKind.MSG_ARROW_ASYNC, toks[2].kind)
    }

    @Test fun seq_arrow_reply_sync() {
        val toks = lexAll("sequenceDiagram\nA -->> B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(MermaidTokenKind.MSG_ARROW_REPLY_SYNC, toks[2].kind)
    }

    @Test fun seq_arrow_reply_dash() {
        val toks = lexAll("sequenceDiagram\nA --> B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(MermaidTokenKind.MSG_ARROW_REPLY_DASH, toks[2].kind)
    }

    @Test fun seq_arrow_lost() {
        val toks = lexAll("sequenceDiagram\nA -x B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(MermaidTokenKind.MSG_ARROW_LOST, toks[2].kind)
    }

    @Test fun seq_arrow_lost_dash() {
        val toks = lexAll("sequenceDiagram\nA --x B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(MermaidTokenKind.MSG_ARROW_LOST_DASH, toks[2].kind)
    }

    @Test fun seq_colon_label() {
        val toks = lexAll("sequenceDiagram\nA ->> B: hello world\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        // SEQUENCE_HEADER, IDENT A, ARROW, IDENT B, COLON, LABEL
        assertEquals(MermaidTokenKind.COLON, toks[4].kind)
        assertEquals(MermaidTokenKind.LABEL, toks[5].kind)
        assertEquals("hello world", toks[5].text.toString())
    }

    @Test fun seq_keywords() {
        val toks = lexAll("sequenceDiagram\nloop end alt\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(
            listOf(
                MermaidTokenKind.SEQUENCE_HEADER,
                MermaidTokenKind.LOOP_KW,
                MermaidTokenKind.END_KW,
                MermaidTokenKind.ALT_KW,
            ),
            toks.map { it.kind },
        )
    }

    @Test fun seq_chunk_split_inside_arrow() {
        val src = "sequenceDiagram\nA -->> B\n"
        val one = lexAll(src).map { it.kind to it.text.toString() }
        val streamed = lexAll(src, chunkSize = 1).map { it.kind to it.text.toString() }
        assertEquals(one, streamed)
    }

    @Test fun seq_plus_minus_shorthand() {
        val toks = lexAll("sequenceDiagram\nA ->> +B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertTrue(toks.any { it.kind == MermaidTokenKind.PLUS })
    }

    @Test fun flowchart_unaffected_by_sequence_changes() {
        val toks = lexAll("flowchart TD\nA --> B\n").map { it.kind }
        assertTrue(MermaidTokenKind.ARROW_SOLID in toks)
    }
}

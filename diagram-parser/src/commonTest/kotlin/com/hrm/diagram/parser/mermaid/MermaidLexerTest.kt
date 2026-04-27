package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidLexerTest {

    private fun lexAll(src: String, chunkSize: Int? = null): List<Token> {
        val lexer = MermaidLexer()
        var state = lexer.initialState()
        val out = ArrayList<Token>()
        if (chunkSize == null) {
            val step = lexer.feed(state, src, 0, eos = true)
            return step.tokens
        }
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

    @Test
    fun lex_simple_header_and_arrow() {
        val toks = lexAll("flowchart TD\nA --> B\n")
        val kinds = toks.map { it.kind }
        assertEquals(
            listOf(
                MermaidTokenKind.KEYWORD_HEADER,
                MermaidTokenKind.DIRECTION,
                MermaidTokenKind.NEWLINE,
                MermaidTokenKind.IDENT,
                MermaidTokenKind.ARROW_SOLID,
                MermaidTokenKind.IDENT,
                MermaidTokenKind.NEWLINE,
            ),
            kinds,
        )
    }

    @Test
    fun lex_label_brackets() {
        val toks = lexAll("flowchart LR\nA[Hello world] --> B[Bye]\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        val labels = toks.filter { it.kind == MermaidTokenKind.LABEL }.map { it.text.toString() }
        assertEquals(listOf("Hello world", "Bye"), labels)
    }

    @Test
    fun chunked_feed_matches_one_shot() {
        val src = "flowchart TD\nA[Start] --> Beta\nBeta --> Gamma\n"
        val oneShot = lexAll(src).map { it.kind to it.text.toString() }
        for (chunkSize in listOf(1, 2, 3, 5, 7, 16)) {
            val streamed = lexAll(src, chunkSize).map { it.kind to it.text.toString() }
            assertEquals(oneShot, streamed, "chunkSize=$chunkSize must agree with one-shot")
        }
    }

    @Test
    fun arrow_split_across_chunks_does_not_corrupt() {
        val src = "flowchart TD\nA --> B\n"
        val streamed = lexAll(src, chunkSize = 2)
        assertEquals(1, streamed.count { it.kind == MermaidTokenKind.ARROW_SOLID })
    }

    @Test
    fun comment_to_eol_is_one_token() {
        val toks = lexAll("flowchart TD\n%% this is a comment\nA --> B\n")
        val comments = toks.filter { it.kind == MermaidTokenKind.COMMENT }
        assertEquals(1, comments.size)
        assertTrue(comments[0].text.toString().startsWith("%%"))
    }

    @Test
    fun unterminated_label_at_eos_emits_error() {
        val toks = lexAll("flowchart TD\nA[unterminated")
        assertTrue(toks.any { it.kind == MermaidTokenKind.ERROR })
    }

    @Test
    fun unknown_char_is_error_and_recoverable() {
        val toks = lexAll("flowchart TD\nA @ B\n")
        val errors = toks.filter { it.kind == MermaidTokenKind.ERROR }
        assertEquals(1, errors.size)
        assertEquals("@", errors[0].text.toString())
        // Subsequent tokens still flow.
        assertTrue(toks.any { it.kind == MermaidTokenKind.IDENT && it.text.toString() == "B" })
    }

    // ----- Phase 1 v2: shape aliases + edge labels -----

    @Test
    fun shape_aliases_are_distinct_token_kinds() {
        val toks = lexAll("flowchart TD\nA(r) B((c)) C{d} D[b]\n")
        val kinds = toks.filter {
            it.kind == MermaidTokenKind.LABEL_PAREN ||
                it.kind == MermaidTokenKind.LABEL_DOUBLE_PAREN ||
                it.kind == MermaidTokenKind.LABEL_BRACE ||
                it.kind == MermaidTokenKind.LABEL
        }
        assertEquals(
            listOf(
                MermaidTokenKind.LABEL_PAREN,
                MermaidTokenKind.LABEL_DOUBLE_PAREN,
                MermaidTokenKind.LABEL_BRACE,
                MermaidTokenKind.LABEL,
            ),
            kinds.map { it.kind },
        )
        assertEquals(listOf("r", "c", "d", "b"), kinds.map { it.text.toString() })
    }

    @Test
    fun edge_label_pipe_pair_is_one_token() {
        val toks = lexAll("flowchart TD\nA -->|hello world| B\n")
        val edgeLabels = toks.filter { it.kind == MermaidTokenKind.EDGE_LABEL }
        assertEquals(1, edgeLabels.size)
        assertEquals("hello world", edgeLabels[0].text.toString())
    }

    @Test
    fun er_header_and_relationship_operator() {
        val toks = lexAll("erDiagram\nA ||--o{ B : allows\n")
        val kinds = toks.map { it.kind }
        assertEquals(
            listOf(
                MermaidTokenKind.ER_HEADER,
                MermaidTokenKind.NEWLINE,
                MermaidTokenKind.IDENT,
                MermaidTokenKind.ER_REL,
                MermaidTokenKind.IDENT,
                MermaidTokenKind.COLON,
                MermaidTokenKind.LABEL,
                MermaidTokenKind.NEWLINE,
            ),
            kinds,
        )
        val rel = toks.first { it.kind == MermaidTokenKind.ER_REL }
        assertEquals("||--o{", rel.text.toString())
    }

    @Test
    fun er_relationship_operator_is_chunk_safe() {
        val src = "erDiagram\nA ||--o{ B : allows\n"
        val oneShot = lexAll(src).map { it.kind to it.text.toString() }
        val streamed = lexAll(src, chunkSize = 1).map { it.kind to it.text.toString() }
        assertEquals(oneShot, streamed)
    }

    @Test
    fun double_paren_split_across_chunks_is_safe() {
        // Split right between the two '(' characters.
        val lexer = MermaidLexer()
        val s0 = lexer.initialState()
        val r1 = lexer.feed(s0, "flowchart TD\nA(", 0, eos = false)
        val r2 = lexer.feed(r1.newState, "(c)) --> B\n", "flowchart TD\nA(".length, eos = true)
        val merged = (r1.tokens + r2.tokens).filter { it.kind != MermaidTokenKind.NEWLINE }
        // Compare against a one-shot lex.
        val oneShot = lexAll("flowchart TD\nA((c)) --> B\n").filter { it.kind != MermaidTokenKind.NEWLINE }
        assertEquals(oneShot.map { it.kind to it.text.toString() }, merged.map { it.kind to it.text.toString() })
    }

    @Test
    fun unterminated_pipe_label_emits_error() {
        val toks = lexAll("flowchart TD\nA -->|never closes\n")
        assertTrue(toks.any { it.kind == MermaidTokenKind.ERROR })
    }
}

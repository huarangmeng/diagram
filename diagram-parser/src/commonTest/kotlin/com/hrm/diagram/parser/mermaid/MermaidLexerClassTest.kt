package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidLexerClassTest {

    private fun lexAll(src: String): List<Token> {
        val lexer = MermaidLexer()
        val state = lexer.initialState()
        return lexer.feed(state, src, 0, eos = true).tokens
    }

    private fun nonNewline(src: String): List<Token> =
        lexAll(src).filter { it.kind != MermaidTokenKind.NEWLINE }

    @Test fun class_header_detected_and_mode_flips() {
        val toks = lexAll("classDiagram\n")
        assertEquals(MermaidTokenKind.CLASS_HEADER, toks[0].kind)
    }

    @Test fun class_keyword_inside_class_mode() {
        val toks = nonNewline("classDiagram\nclass Foo\n")
        assertEquals(MermaidTokenKind.CLASS_KW, toks[1].kind)
        assertEquals(MermaidTokenKind.IDENT, toks[2].kind)
    }

    @Test fun arrow_inheritance_left() {
        val toks = nonNewline("classDiagram\nA <|-- B\n")
        assertEquals(MermaidTokenKind.CLASS_ARROW_INHERIT, toks[2].kind)
    }

    @Test fun arrow_inheritance_right() {
        val toks = nonNewline("classDiagram\nA --|> B\n")
        assertEquals(MermaidTokenKind.CLASS_ARROW_INHERIT, toks[2].kind)
    }

    @Test fun arrow_composition() {
        val toks = nonNewline("classDiagram\nA *-- B\n")
        assertEquals(MermaidTokenKind.CLASS_ARROW_COMPOSITION, toks[2].kind)
    }

    @Test fun arrow_aggregation() {
        val toks = nonNewline("classDiagram\nA o-- B\n")
        assertEquals(MermaidTokenKind.CLASS_ARROW_AGGREGATION, toks[2].kind)
    }

    @Test fun arrow_dependency_dotted() {
        val toks = nonNewline("classDiagram\nA ..> B\n")
        assertEquals(MermaidTokenKind.CLASS_ARROW_DEPENDENCY, toks[2].kind)
    }

    @Test fun arrow_realization() {
        val toks = nonNewline("classDiagram\nA ..|> B\n")
        assertEquals(MermaidTokenKind.CLASS_ARROW_REALIZATION, toks[2].kind)
    }

    @Test fun longest_match_dashes() {
        // "--|>" must beat "--" + "|" + ">"
        val toks = nonNewline("classDiagram\nA --|> B\n")
        assertEquals(4, toks[2].text.length)
    }

    @Test fun braces_emit_lbrace_rbrace_in_class_mode() {
        val toks = nonNewline("classDiagram\nclass Foo {\n}\n")
        assertTrue(toks.any { it.kind == MermaidTokenKind.LBRACE })
        assertTrue(toks.any { it.kind == MermaidTokenKind.RBRACE })
    }

    @Test fun generics_use_tilde() {
        val toks = nonNewline("classDiagram\nclass Foo~T~\n")
        assertTrue(toks.any { it.kind == MermaidTokenKind.TILDE })
    }

    @Test fun stereotype_tokens() {
        val toks = nonNewline("classDiagram\nclass Foo\nFoo : <<interface>>\n")
        assertTrue(toks.any { it.kind == MermaidTokenKind.STEREOTYPE_OPEN })
        assertTrue(toks.any { it.kind == MermaidTokenKind.STEREOTYPE_CLOSE })
    }

    @Test fun quoted_strings() {
        val toks = nonNewline("classDiagram\nA \"1\" --> \"many\" B\n")
        val strings = toks.filter { it.kind == MermaidTokenKind.STRING }
        assertEquals(2, strings.size)
        assertEquals("1", strings[0].text.toString())
        assertEquals("many", strings[1].text.toString())
    }

    @Test fun triple_colon_for_css_assignment() {
        val toks = nonNewline("classDiagram\nclass Foo:::warn\n")
        assertTrue(toks.any { it.kind == MermaidTokenKind.TRIPLE_COLON })
    }
}

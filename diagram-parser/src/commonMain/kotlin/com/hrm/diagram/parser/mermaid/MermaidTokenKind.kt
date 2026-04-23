package com.hrm.diagram.parser.mermaid

/**
 * Token kind table for the Mermaid lexer.
 *
 * Phase 1 subset — only what the flowchart parser needs:
 *
 * | kind            | example       |
 * |-----------------|---------------|
 * | NEWLINE         | `\n`          |
 * | KEYWORD_HEADER  | `flowchart` / `graph` |
 * | DIRECTION       | `TD`/`TB`/`LR`/`RL`/`BT` |
 * | IDENT           | `A`, `node1`  |
 * | LABEL           | bracketed body of `[...]` (brackets stripped from `text`) |
 * | ARROW_SOLID     | `-->`         |
 * | COMMENT         | `%% comment to end of line` |
 * | ERROR           | unrecognised char (carries 1-char span) |
 *
 * Token kinds are intentionally a flat `Int` table (per the [Token] contract in
 * `:diagram-core/streaming/Lexer.kt`) so `:diagram-core` stays language-agnostic.
 */
object MermaidTokenKind {
    const val NEWLINE: Int = 1
    const val KEYWORD_HEADER: Int = 2
    const val DIRECTION: Int = 3
    const val IDENT: Int = 4
    const val LABEL: Int = 5
    const val ARROW_SOLID: Int = 6
    const val COMMENT: Int = 7
    const val LABEL_PAREN: Int = 10        // `(text)` → RoundedBox
    const val LABEL_DOUBLE_PAREN: Int = 11 // `((text))` → Circle
    const val LABEL_BRACE: Int = 12        // `{text}` → Diamond
    const val EDGE_LABEL: Int = 13         // `|text|` after an arrow

    // ---- Sequence-diagram tokens (Phase 2) ----
    const val SEQUENCE_HEADER: Int = 20    // "sequenceDiagram"
    const val PARTICIPANT_KW: Int = 21
    const val ACTOR_KW: Int = 22
    const val NOTE_KW: Int = 23
    const val ACTIVATE_KW: Int = 24
    const val DEACTIVATE_KW: Int = 25
    const val LOOP_KW: Int = 26
    const val ALT_KW: Int = 27
    const val ELSE_KW: Int = 28
    const val OPT_KW: Int = 29
    const val PAR_KW: Int = 30
    const val AND_KW: Int = 31
    const val CRITICAL_KW: Int = 32
    const val OPTION_KW: Int = 33
    const val BREAK_KW: Int = 34
    const val END_KW: Int = 35
    const val AUTONUMBER_KW: Int = 36
    const val LEFT_KW: Int = 37
    const val RIGHT_KW: Int = 38
    const val OF_KW: Int = 39
    const val AS_KW: Int = 40
    const val OVER_KW: Int = 41
    const val COLON: Int = 42
    const val COMMA: Int = 43
    const val PLUS: Int = 44
    const val MINUS: Int = 45
    const val MSG_ARROW_SYNC: Int = 50            // ->>
    const val MSG_ARROW_ASYNC: Int = 51           // ->
    const val MSG_ARROW_REPLY_SYNC: Int = 52      // -->>
    const val MSG_ARROW_REPLY_DASH: Int = 53      // -->
    const val MSG_ARROW_LOST: Int = 54            // -x
    const val MSG_ARROW_LOST_DASH: Int = 55       // --x

    const val ERROR: Int = 99

    // ---- Class-diagram tokens (Phase 3) ----
    const val CLASS_HEADER: Int = 60
    const val CLASS_KW: Int = 61
    const val NAMESPACE_KW: Int = 62
    const val CSS_CLASS_KW: Int = 63
    const val DIRECTION_KW: Int = 64
    const val LBRACE: Int = 65
    const val RBRACE: Int = 66
    const val LPAREN: Int = 67
    const val RPAREN: Int = 68
    const val TILDE: Int = 69
    const val TRIPLE_COLON: Int = 70
    const val STEREOTYPE_OPEN: Int = 71
    const val STEREOTYPE_CLOSE: Int = 72
    const val DOLLAR: Int = 73
    const val ASTERISK: Int = 74
    const val CLASS_ARROW_INHERIT: Int = 75
    const val CLASS_ARROW_COMPOSITION: Int = 76
    const val CLASS_ARROW_AGGREGATION: Int = 77
    const val CLASS_ARROW_ASSOCIATION: Int = 78
    const val CLASS_ARROW_DEPENDENCY: Int = 79
    const val CLASS_ARROW_REALIZATION: Int = 80
    const val CLASS_LINK_SOLID: Int = 81
    const val CLASS_LINK_DASHED: Int = 82
    const val STRING: Int = 83
    const val HASH: Int = 84
    const val LT: Int = 85
    const val GT: Int = 86
    const val TOP_KW: Int = 87
    const val BOTTOM_KW: Int = 88

    // ---- State-diagram tokens (Phase 4) ----
    const val STATE_HEADER: Int = 100         // "stateDiagram" / "stateDiagram-v2"
    const val STATE_KW: Int = 101             // "state"
    const val START_END_TOKEN: Int = 102      // "[*]"
    const val STATE_ARROW: Int = 103          // "-->"

    fun nameOf(kind: Int): String = when (kind) {
        NEWLINE -> "NEWLINE"
        KEYWORD_HEADER -> "KEYWORD_HEADER"
        DIRECTION -> "DIRECTION"
        IDENT -> "IDENT"
        LABEL -> "LABEL"
        LABEL_PAREN -> "LABEL_PAREN"
        LABEL_DOUBLE_PAREN -> "LABEL_DOUBLE_PAREN"
        LABEL_BRACE -> "LABEL_BRACE"
        EDGE_LABEL -> "EDGE_LABEL"
        ARROW_SOLID -> "ARROW_SOLID"
        COMMENT -> "COMMENT"
        SEQUENCE_HEADER -> "SEQUENCE_HEADER"
        PARTICIPANT_KW -> "PARTICIPANT_KW"
        ACTOR_KW -> "ACTOR_KW"
        NOTE_KW -> "NOTE_KW"
        ACTIVATE_KW -> "ACTIVATE_KW"
        DEACTIVATE_KW -> "DEACTIVATE_KW"
        LOOP_KW -> "LOOP_KW"
        ALT_KW -> "ALT_KW"
        ELSE_KW -> "ELSE_KW"
        OPT_KW -> "OPT_KW"
        PAR_KW -> "PAR_KW"
        AND_KW -> "AND_KW"
        CRITICAL_KW -> "CRITICAL_KW"
        OPTION_KW -> "OPTION_KW"
        BREAK_KW -> "BREAK_KW"
        END_KW -> "END_KW"
        AUTONUMBER_KW -> "AUTONUMBER_KW"
        LEFT_KW -> "LEFT_KW"
        RIGHT_KW -> "RIGHT_KW"
        OF_KW -> "OF_KW"
        AS_KW -> "AS_KW"
        OVER_KW -> "OVER_KW"
        COLON -> "COLON"
        COMMA -> "COMMA"
        PLUS -> "PLUS"
        MINUS -> "MINUS"
        MSG_ARROW_SYNC -> "MSG_ARROW_SYNC"
        MSG_ARROW_ASYNC -> "MSG_ARROW_ASYNC"
        MSG_ARROW_REPLY_SYNC -> "MSG_ARROW_REPLY_SYNC"
        MSG_ARROW_REPLY_DASH -> "MSG_ARROW_REPLY_DASH"
        MSG_ARROW_LOST -> "MSG_ARROW_LOST"
        MSG_ARROW_LOST_DASH -> "MSG_ARROW_LOST_DASH"
        ERROR -> "ERROR"
        CLASS_HEADER -> "CLASS_HEADER"
        CLASS_KW -> "CLASS_KW"
        NAMESPACE_KW -> "NAMESPACE_KW"
        CSS_CLASS_KW -> "CSS_CLASS_KW"
        DIRECTION_KW -> "DIRECTION_KW"
        LBRACE -> "LBRACE"
        RBRACE -> "RBRACE"
        LPAREN -> "LPAREN"
        RPAREN -> "RPAREN"
        TILDE -> "TILDE"
        TRIPLE_COLON -> "TRIPLE_COLON"
        STEREOTYPE_OPEN -> "STEREOTYPE_OPEN"
        STEREOTYPE_CLOSE -> "STEREOTYPE_CLOSE"
        DOLLAR -> "DOLLAR"
        ASTERISK -> "ASTERISK"
        CLASS_ARROW_INHERIT -> "CLASS_ARROW_INHERIT"
        CLASS_ARROW_COMPOSITION -> "CLASS_ARROW_COMPOSITION"
        CLASS_ARROW_AGGREGATION -> "CLASS_ARROW_AGGREGATION"
        CLASS_ARROW_ASSOCIATION -> "CLASS_ARROW_ASSOCIATION"
        CLASS_ARROW_DEPENDENCY -> "CLASS_ARROW_DEPENDENCY"
        CLASS_ARROW_REALIZATION -> "CLASS_ARROW_REALIZATION"
        CLASS_LINK_SOLID -> "CLASS_LINK_SOLID"
        CLASS_LINK_DASHED -> "CLASS_LINK_DASHED"
        STRING -> "STRING"
        HASH -> "HASH"
        LT -> "LT"
        GT -> "GT"
        TOP_KW -> "TOP_KW"
        BOTTOM_KW -> "BOTTOM_KW"
        STATE_HEADER -> "STATE_HEADER"
        STATE_KW -> "STATE_KW"
        START_END_TOKEN -> "START_END_TOKEN"
        STATE_ARROW -> "STATE_ARROW"
        else -> "UNKNOWN($kind)"
    }
}

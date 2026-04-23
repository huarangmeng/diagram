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
public object MermaidTokenKind {
    public const val NEWLINE: Int = 1
    public const val KEYWORD_HEADER: Int = 2
    public const val DIRECTION: Int = 3
    public const val IDENT: Int = 4
    public const val LABEL: Int = 5
    public const val ARROW_SOLID: Int = 6
    public const val COMMENT: Int = 7
    public const val ERROR: Int = 99

    public fun nameOf(kind: Int): String = when (kind) {
        NEWLINE -> "NEWLINE"
        KEYWORD_HEADER -> "KEYWORD_HEADER"
        DIRECTION -> "DIRECTION"
        IDENT -> "IDENT"
        LABEL -> "LABEL"
        ARROW_SOLID -> "ARROW_SOLID"
        COMMENT -> "COMMENT"
        ERROR -> "ERROR"
        else -> "UNKNOWN($kind)"
    }
}

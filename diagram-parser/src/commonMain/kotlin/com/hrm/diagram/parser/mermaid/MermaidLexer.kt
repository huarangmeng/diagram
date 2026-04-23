package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.streaming.LexDiagnostic
import com.hrm.diagram.core.streaming.LexerState
import com.hrm.diagram.core.streaming.LexerStep
import com.hrm.diagram.core.streaming.ResumableLexer
import com.hrm.diagram.core.streaming.Token

/**
 * Resumable lexer for the Mermaid Phase 1 subset (see [MermaidTokenKind]).
 *
 * Streaming guarantees:
 * - **Chunk-split safety.** A chunk may end mid-`-->`, mid-`[label]`, mid-identifier, mid-`%%`
 *   comment. Whatever cannot be finalised is moved to [MermaidLexerState.pending]; the next
 *   `feed` call re-scans `pending + nextChunk`.
 * - **Idempotent under coalescing.** Feeding `"abc"` then `"def"` MUST emit the same tokens as
 *   feeding `"abcdef"` in one shot. Verified by [com.hrm.diagram.parser.mermaid] tests.
 * - **No look-back past pending.** Tokens never reference offsets earlier than the previous
 *   `safePoint`; the host may discard old source bytes safely.
 * - **EOS flushing.** When `eos = true`, any unfinished bracketed label or arrow becomes an
 *   [MermaidTokenKind.ERROR] token plus a [LexDiagnostic] — never silently dropped.
 *
 * Not yet supported (Phase 2+ TODO): subgraphs, classDef/style, dotted/thick arrows, edge
 * labels (`-->|text|`), node shape aliases (`(...)`, `((...))`, `{...}`, `>...]`, etc.),
 * Markdown labels, double-quoted strings inside `[]`. Unrecognised characters surface as ERROR.
 */
public class MermaidLexer : ResumableLexer<MermaidLexerState> {

    override fun initialState(): MermaidLexerState = MermaidLexerState()

    override fun feed(
        state: MermaidLexerState,
        input: CharSequence,
        offset: Int,
        eos: Boolean,
    ): LexerStep<MermaidLexerState> {
        // The buffer we scan = leftover from last call + this chunk.
        // Its absolute start offset = offset - pending.length.
        val pending = state.pending
        val buf: String = if (pending.isEmpty()) input.toString() else pending + input
        val baseOffset = offset - pending.length

        val tokens = ArrayList<Token>()
        val diags = ArrayList<LexDiagnostic>()
        var pos = 0
        var safePoint = baseOffset

        scan@ while (pos < buf.length) {
            val tokenStart = pos
            val c = buf[pos]
            when {
                c == '\n' -> {
                    tokens += Token(MermaidTokenKind.NEWLINE, baseOffset + pos, baseOffset + pos + 1, "\n")
                    pos++
                    safePoint = baseOffset + pos
                }
                c == '\r' -> { pos++; safePoint = baseOffset + pos }
                c == ' ' || c == '\t' -> { pos++; safePoint = baseOffset + pos }
                c == '%' -> {
                    // Mermaid comment: %% to end of line.
                    if (pos + 1 >= buf.length) {
                        if (eos) { // single trailing '%' → ERROR
                            tokens += errorTok(baseOffset + pos, "%")
                            diags += LexDiagnostic("Unexpected '%' (Mermaid comments are '%%')", baseOffset + pos)
                            pos++; safePoint = baseOffset + pos
                        } else return suspendHere(buf, pos, baseOffset, tokens, diags)
                    } else if (buf[pos + 1] != '%') {
                        tokens += errorTok(baseOffset + pos, "%")
                        diags += LexDiagnostic("Unexpected '%' (Mermaid comments are '%%')", baseOffset + pos)
                        pos++; safePoint = baseOffset + pos
                    } else {
                        // Scan to newline or end-of-buffer.
                        var end = pos + 2
                        while (end < buf.length && buf[end] != '\n') end++
                        if (end >= buf.length && !eos) {
                            // Comment may continue in the next chunk.
                            return suspendHere(buf, pos, baseOffset, tokens, diags)
                        }
                        tokens += Token(MermaidTokenKind.COMMENT, baseOffset + pos, baseOffset + end,
                            buf.substring(pos, end))
                        pos = end
                        safePoint = baseOffset + pos
                    }
                }
                c == '-' -> {
                    // Need exactly "-->". Anything else is an error in this subset.
                    val needed = 3
                    if (buf.length - pos < needed) {
                        if (eos) {
                            tokens += errorTok(baseOffset + pos, buf.substring(pos))
                            diags += LexDiagnostic("Truncated arrow", baseOffset + pos)
                            pos = buf.length; safePoint = baseOffset + pos
                        } else return suspendHere(buf, pos, baseOffset, tokens, diags)
                    } else if (buf[pos + 1] == '-' && buf[pos + 2] == '>') {
                        tokens += Token(MermaidTokenKind.ARROW_SOLID, baseOffset + pos, baseOffset + pos + 3, "-->")
                        pos += 3
                        safePoint = baseOffset + pos
                    } else {
                        tokens += errorTok(baseOffset + pos, "-")
                        diags += LexDiagnostic("Expected '-->' (other arrow styles are not yet supported)", baseOffset + pos)
                        pos++; safePoint = baseOffset + pos
                    }
                }
                c == '[' -> {
                    // Bracketed label. Find matching ']' on the same line. v1: no nesting, no escapes.
                    var end = pos + 1
                    while (end < buf.length && buf[end] != ']' && buf[end] != '\n') end++
                    if (end < buf.length && buf[end] == ']') {
                        val text = buf.substring(pos + 1, end)
                        tokens += Token(MermaidTokenKind.LABEL, baseOffset + pos, baseOffset + end + 1, text)
                        pos = end + 1
                        safePoint = baseOffset + pos
                    } else if (end < buf.length && buf[end] == '\n') {
                        // Unterminated label on this line.
                        tokens += errorTok(baseOffset + pos, buf.substring(pos, end))
                        diags += LexDiagnostic("Unterminated '[' label (missing ']')", baseOffset + pos)
                        pos = end
                        safePoint = baseOffset + pos
                    } else {
                        // Reached end-of-buf without ']'.
                        if (eos) {
                            tokens += errorTok(baseOffset + pos, buf.substring(pos))
                            diags += LexDiagnostic("Unterminated '[' label at end of input", baseOffset + pos)
                            pos = buf.length; safePoint = baseOffset + pos
                        } else return suspendHere(buf, pos, baseOffset, tokens, diags)
                    }
                }
                isIdentStart(c) -> {
                    var end = pos + 1
                    while (end < buf.length && isIdentCont(buf[end])) end++
                    if (end >= buf.length && !eos) {
                        // Identifier may continue in the next chunk.
                        return suspendHere(buf, pos, baseOffset, tokens, diags)
                    }
                    val text = buf.substring(pos, end)
                    val kind = classifyWord(text)
                    tokens += Token(kind, baseOffset + pos, baseOffset + end, text)
                    pos = end
                    safePoint = baseOffset + pos
                }
                else -> {
                    tokens += errorTok(baseOffset + pos, c.toString())
                    diags += LexDiagnostic("Unexpected character '${c}'", baseOffset + pos)
                    pos++
                    safePoint = baseOffset + pos
                }
            }
            // tokenStart referenced for clarity; suppress unused-value lint:
            @Suppress("UNUSED_VARIABLE") val _u = tokenStart
        }

        return LexerStep(
            tokens = tokens,
            newState = MermaidLexerState(pending = ""),
            safePoint = safePoint,
            diagnostics = diags,
        )
    }

    private fun suspendHere(
        buf: String,
        pos: Int,
        baseOffset: Int,
        tokens: List<Token>,
        diags: List<LexDiagnostic>,
    ): LexerStep<MermaidLexerState> = LexerStep(
        tokens = tokens,
        newState = MermaidLexerState(pending = buf.substring(pos)),
        safePoint = baseOffset + pos,
        diagnostics = diags,
    )

    private fun errorTok(start: Int, text: String): Token =
        Token(MermaidTokenKind.ERROR, start, start + text.length.coerceAtLeast(1), text)

    private companion object {
        private val DIRECTIONS = setOf("TD", "TB", "LR", "RL", "BT")
        private val HEADER_KEYWORDS = setOf("flowchart", "graph")

        private fun isIdentStart(c: Char): Boolean =
            c.isLetter() || c == '_'

        private fun isIdentCont(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_'

        private fun classifyWord(text: String): Int = when {
            text in HEADER_KEYWORDS -> MermaidTokenKind.KEYWORD_HEADER
            text in DIRECTIONS -> MermaidTokenKind.DIRECTION
            else -> MermaidTokenKind.IDENT
        }
    }
}

/**
 * Streaming continuation state: holds the unconsumed prefix of the previous chunk.
 * Empty whenever the lexer is at a safe point.
 */
public data class MermaidLexerState(
    val pending: String = "",
) : LexerState {
    override val pendingChars: Int get() = pending.length
}

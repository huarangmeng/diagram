package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.streaming.LexDiagnostic
import com.hrm.diagram.core.streaming.LexerState
import com.hrm.diagram.core.streaming.LexerStep
import com.hrm.diagram.core.streaming.ResumableLexer
import com.hrm.diagram.core.streaming.Token

/**
 * The Mermaid lexer operates in three modes:
 *  - [LexMode.Auto]      : initial — until a header keyword decides flowchart/sequence.
 *  - [LexMode.Flowchart] : Phase-1 flowchart token table (the original lexer behaviour).
 *  - [LexMode.Sequence]  : Phase-2 sequenceDiagram tokens (arrows ->>/-->/-x, COLON-LABEL, keywords).
 */
enum class LexMode { Auto, Flowchart, Sequence, Class, State }

/**
 * Resumable lexer for the Mermaid Phase 1 + 2 subset (see [MermaidTokenKind]).
 *
 * Streaming guarantees:
 * - **Chunk-split safety.** A chunk may end mid-arrow, mid-`[label]`, mid-identifier, mid-`%%`
 *   comment. Whatever cannot be finalised is moved to [MermaidLexerState.pending]; the next
 *   `feed` call re-scans `pending + nextChunk`.
 * - **Idempotent under coalescing.** Feeding `"abc"` then `"def"` MUST emit the same tokens as
 *   feeding `"abcdef"` in one shot.
 * - **No look-back past pending.** Tokens never reference offsets earlier than the previous
 *   `safePoint`; the host may discard old source bytes safely.
 * - **EOS flushing.** When `eos = true`, any unfinished bracketed label or arrow becomes an
 *   [MermaidTokenKind.ERROR] token plus a [LexDiagnostic] — never silently dropped.
 */
class MermaidLexer : ResumableLexer<MermaidLexerState> {

    override fun initialState(): MermaidLexerState = MermaidLexerState()

    override fun feed(
        state: MermaidLexerState,
        input: CharSequence,
        offset: Int,
        eos: Boolean,
    ): LexerStep<MermaidLexerState> {
        val pending = state.pending
        val buf: String = if (pending.isEmpty()) input.toString() else pending + input
        val baseOffset = offset - pending.length

        val tokens = ArrayList<Token>()
        val diags = ArrayList<LexDiagnostic>()
        var pos = 0
        var safePoint = baseOffset
        var mode = state.mode

        scan@ while (pos < buf.length) {
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
                    if (pos + 1 >= buf.length) {
                        if (eos) {
                            tokens += errorTok(baseOffset + pos, "%")
                            diags += LexDiagnostic("Unexpected '%' (Mermaid comments are '%%')", baseOffset + pos)
                            pos++; safePoint = baseOffset + pos
                        } else return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    } else if (buf[pos + 1] != '%') {
                        tokens += errorTok(baseOffset + pos, "%")
                        diags += LexDiagnostic("Unexpected '%' (Mermaid comments are '%%')", baseOffset + pos)
                        pos++; safePoint = baseOffset + pos
                    } else {
                        var end = pos + 2
                        while (end < buf.length && buf[end] != '\n') end++
                        if (end >= buf.length && !eos) {
                            return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                        }
                        tokens += Token(MermaidTokenKind.COMMENT, baseOffset + pos, baseOffset + end,
                            buf.substring(pos, end))
                        pos = end
                        safePoint = baseOffset + pos
                    }
                }
                c == '-' -> {
                    if (mode == LexMode.Sequence) {
                        // Need lookahead up to 4 chars ('-->>') to disambiguate arrows.
                        val remaining = buf.length - pos
                        if (remaining < 4 && !eos) {
                            return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                        }
                        val sub2 = if (remaining >= 2) buf.substring(pos, pos + 2) else null
                        val sub3 = if (remaining >= 3) buf.substring(pos, pos + 3) else null
                        val sub4 = if (remaining >= 4) buf.substring(pos, pos + 4) else null
                        when {
                            sub4 == "-->>" -> {
                                tokens += Token(MermaidTokenKind.MSG_ARROW_REPLY_SYNC, baseOffset + pos, baseOffset + pos + 4, "-->>")
                                pos += 4; safePoint = baseOffset + pos
                            }
                            sub3 == "-->" -> {
                                tokens += Token(MermaidTokenKind.MSG_ARROW_REPLY_DASH, baseOffset + pos, baseOffset + pos + 3, "-->")
                                pos += 3; safePoint = baseOffset + pos
                            }
                            sub3 == "--x" -> {
                                tokens += Token(MermaidTokenKind.MSG_ARROW_LOST_DASH, baseOffset + pos, baseOffset + pos + 3, "--x")
                                pos += 3; safePoint = baseOffset + pos
                            }
                            sub3 == "->>" -> {
                                tokens += Token(MermaidTokenKind.MSG_ARROW_SYNC, baseOffset + pos, baseOffset + pos + 3, "->>")
                                pos += 3; safePoint = baseOffset + pos
                            }
                            sub2 == "->" -> {
                                tokens += Token(MermaidTokenKind.MSG_ARROW_ASYNC, baseOffset + pos, baseOffset + pos + 2, "->")
                                pos += 2; safePoint = baseOffset + pos
                            }
                            sub2 == "-x" -> {
                                tokens += Token(MermaidTokenKind.MSG_ARROW_LOST, baseOffset + pos, baseOffset + pos + 2, "-x")
                                pos += 2; safePoint = baseOffset + pos
                            }
                            else -> {
                                tokens += Token(MermaidTokenKind.MINUS, baseOffset + pos, baseOffset + pos + 1, "-")
                                pos++; safePoint = baseOffset + pos
                            }
                        }
                    } else if (mode == LexMode.Class) {
                        val remaining = buf.length - pos
                        if (remaining < 4 && !eos) {
                            return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                        }
                        val sub2 = if (remaining >= 2) buf.substring(pos, pos + 2) else null
                        val sub3 = if (remaining >= 3) buf.substring(pos, pos + 3) else null
                        val sub4 = if (remaining >= 4) buf.substring(pos, pos + 4) else null
                        when {
                            sub4 == "--|>" -> {
                                tokens += Token(MermaidTokenKind.CLASS_ARROW_INHERIT, baseOffset + pos, baseOffset + pos + 4, "--|>")
                                pos += 4; safePoint = baseOffset + pos
                            }
                            sub3 == "--*" -> {
                                tokens += Token(MermaidTokenKind.CLASS_ARROW_COMPOSITION, baseOffset + pos, baseOffset + pos + 3, "--*")
                                pos += 3; safePoint = baseOffset + pos
                            }
                            sub3 == "--o" -> {
                                tokens += Token(MermaidTokenKind.CLASS_ARROW_AGGREGATION, baseOffset + pos, baseOffset + pos + 3, "--o")
                                pos += 3; safePoint = baseOffset + pos
                            }
                            sub3 == "-->" -> {
                                tokens += Token(MermaidTokenKind.CLASS_ARROW_ASSOCIATION, baseOffset + pos, baseOffset + pos + 3, "-->")
                                pos += 3; safePoint = baseOffset + pos
                            }
                            sub2 == "--" -> {
                                tokens += Token(MermaidTokenKind.CLASS_LINK_SOLID, baseOffset + pos, baseOffset + pos + 2, "--")
                                pos += 2; safePoint = baseOffset + pos
                            }
                            else -> {
                                tokens += Token(MermaidTokenKind.MINUS, baseOffset + pos, baseOffset + pos + 1, "-")
                                pos++; safePoint = baseOffset + pos
                            }
                        }
                    } else if (mode == LexMode.State) {
                        // State mode: only "-->" recognised as STATE_ARROW.
                        val needed = 3
                        if (buf.length - pos < needed) {
                            if (eos) {
                                tokens += errorTok(baseOffset + pos, buf.substring(pos))
                                diags += LexDiagnostic("Truncated arrow", baseOffset + pos)
                                pos = buf.length; safePoint = baseOffset + pos
                            } else return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                        } else if (buf[pos + 1] == '-' && buf[pos + 2] == '>') {
                            tokens += Token(MermaidTokenKind.STATE_ARROW, baseOffset + pos, baseOffset + pos + 3, "-->")
                            pos += 3
                            safePoint = baseOffset + pos
                        } else {
                            tokens += errorTok(baseOffset + pos, "-")
                            diags += LexDiagnostic("Expected '-->'", baseOffset + pos)
                            pos++; safePoint = baseOffset + pos
                        }
                    } else {
                        // Auto / Flowchart: only "-->" is recognised.
                        val needed = 3
                        if (buf.length - pos < needed) {
                            if (eos) {
                                tokens += errorTok(baseOffset + pos, buf.substring(pos))
                                diags += LexDiagnostic("Truncated arrow", baseOffset + pos)
                                pos = buf.length; safePoint = baseOffset + pos
                            } else return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
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
                }
                c == ':' && (mode == LexMode.Sequence || mode == LexMode.State) -> {
                    // Emit COLON, then scan the rest of the line as a single LABEL token (trimmed).
                    var end = pos + 1
                    while (end < buf.length && buf[end] != '\n') end++
                    if (end >= buf.length && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    tokens += Token(MermaidTokenKind.COLON, baseOffset + pos, baseOffset + pos + 1, ":")
                    val raw = buf.substring(pos + 1, end)
                    val trimmed = raw.trim()
                    if (trimmed.isNotEmpty()) {
                        // We carry the trimmed text but the span covers the raw region.
                        tokens += Token(MermaidTokenKind.LABEL, baseOffset + pos + 1, baseOffset + end, trimmed)
                    }
                    pos = end
                    safePoint = baseOffset + pos
                }
                c == ',' && mode == LexMode.Sequence -> {
                    tokens += Token(MermaidTokenKind.COMMA, baseOffset + pos, baseOffset + pos + 1, ",")
                    pos++; safePoint = baseOffset + pos
                }
                c == '+' && mode == LexMode.Sequence -> {
                    tokens += Token(MermaidTokenKind.PLUS, baseOffset + pos, baseOffset + pos + 1, "+")
                    pos++; safePoint = baseOffset + pos
                }
                c == '+' && mode == LexMode.Class -> {
                    tokens += Token(MermaidTokenKind.PLUS, baseOffset + pos, baseOffset + pos + 1, "+")
                    pos++; safePoint = baseOffset + pos
                }
                c == '}' && (mode == LexMode.Class || mode == LexMode.State) -> {
                    tokens += Token(MermaidTokenKind.RBRACE, baseOffset + pos, baseOffset + pos + 1, "}")
                    pos++; safePoint = baseOffset + pos
                }
                c == ')' && mode == LexMode.Class -> {
                    tokens += Token(MermaidTokenKind.RPAREN, baseOffset + pos, baseOffset + pos + 1, ")")
                    pos++; safePoint = baseOffset + pos
                }
                c == '~' && mode == LexMode.Class -> {
                    tokens += Token(MermaidTokenKind.TILDE, baseOffset + pos, baseOffset + pos + 1, "~")
                    pos++; safePoint = baseOffset + pos
                }
                c == '$' && mode == LexMode.Class -> {
                    tokens += Token(MermaidTokenKind.DOLLAR, baseOffset + pos, baseOffset + pos + 1, "$")
                    pos++; safePoint = baseOffset + pos
                }
                c == '#' && mode == LexMode.Class -> {
                    tokens += Token(MermaidTokenKind.HASH, baseOffset + pos, baseOffset + pos + 1, "#")
                    pos++; safePoint = baseOffset + pos
                }
                c == ',' && mode == LexMode.Class -> {
                    tokens += Token(MermaidTokenKind.COMMA, baseOffset + pos, baseOffset + pos + 1, ",")
                    pos++; safePoint = baseOffset + pos
                }
                c == '*' && mode == LexMode.Class -> {
                    val remaining = buf.length - pos
                    if (remaining < 3 && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    if (remaining >= 3 && buf[pos + 1] == '-' && buf[pos + 2] == '-') {
                        tokens += Token(MermaidTokenKind.CLASS_ARROW_COMPOSITION, baseOffset + pos, baseOffset + pos + 3, "*--")
                        pos += 3; safePoint = baseOffset + pos
                    } else {
                        tokens += Token(MermaidTokenKind.ASTERISK, baseOffset + pos, baseOffset + pos + 1, "*")
                        pos++; safePoint = baseOffset + pos
                    }
                }
                c == 'o' && mode == LexMode.Class && pos + 2 < buf.length && buf[pos + 1] == '-' && buf[pos + 2] == '-' -> {
                    tokens += Token(MermaidTokenKind.CLASS_ARROW_AGGREGATION, baseOffset + pos, baseOffset + pos + 3, "o--")
                    pos += 3; safePoint = baseOffset + pos
                }
                c == '<' && mode == LexMode.State -> {
                    val remaining = buf.length - pos
                    if (remaining < 2 && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    if (remaining >= 2 && buf[pos + 1] == '<') {
                        tokens += Token(MermaidTokenKind.STEREOTYPE_OPEN, baseOffset + pos, baseOffset + pos + 2, "<<")
                        pos += 2; safePoint = baseOffset + pos
                    } else {
                        tokens += errorTok(baseOffset + pos, "<")
                        diags += LexDiagnostic("Unexpected '<'", baseOffset + pos)
                        pos++; safePoint = baseOffset + pos
                    }
                }
                c == '>' && mode == LexMode.State -> {
                    if (pos + 1 < buf.length && buf[pos + 1] == '>') {
                        tokens += Token(MermaidTokenKind.STEREOTYPE_CLOSE, baseOffset + pos, baseOffset + pos + 2, ">>")
                        pos += 2; safePoint = baseOffset + pos
                    } else {
                        tokens += errorTok(baseOffset + pos, ">")
                        diags += LexDiagnostic("Unexpected '>'", baseOffset + pos)
                        pos++; safePoint = baseOffset + pos
                    }
                }
                c == '<' && mode == LexMode.Class -> {
                    val remaining = buf.length - pos
                    if (remaining < 4 && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    val sub2 = if (remaining >= 2) buf.substring(pos, pos + 2) else null
                    val sub4 = if (remaining >= 4) buf.substring(pos, pos + 4) else null
                    when {
                        sub4 == "<|--" -> {
                            tokens += Token(MermaidTokenKind.CLASS_ARROW_INHERIT, baseOffset + pos, baseOffset + pos + 4, "<|--")
                            pos += 4; safePoint = baseOffset + pos
                        }
                        sub4 == "<|.." -> {
                            tokens += Token(MermaidTokenKind.CLASS_ARROW_REALIZATION, baseOffset + pos, baseOffset + pos + 4, "<|..")
                            pos += 4; safePoint = baseOffset + pos
                        }
                        sub2 == "<<" -> {
                            tokens += Token(MermaidTokenKind.STEREOTYPE_OPEN, baseOffset + pos, baseOffset + pos + 2, "<<")
                            pos += 2; safePoint = baseOffset + pos
                        }
                        sub2 == "<-" -> {
                            // `<--` association reverse, `<..` dependency reverse
                            val sub3 = if (remaining >= 3) buf.substring(pos, pos + 3) else null
                            if (sub3 == "<--") {
                                tokens += Token(MermaidTokenKind.CLASS_ARROW_ASSOCIATION, baseOffset + pos, baseOffset + pos + 3, "<--")
                                pos += 3; safePoint = baseOffset + pos
                            } else {
                                tokens += Token(MermaidTokenKind.LT, baseOffset + pos, baseOffset + pos + 1, "<")
                                pos++; safePoint = baseOffset + pos
                            }
                        }
                        else -> {
                            // Possibly "<.." for dependency reverse
                            val sub3 = if (remaining >= 3) buf.substring(pos, pos + 3) else null
                            if (sub3 == "<..") {
                                tokens += Token(MermaidTokenKind.CLASS_ARROW_DEPENDENCY, baseOffset + pos, baseOffset + pos + 3, "<..")
                                pos += 3; safePoint = baseOffset + pos
                            } else {
                                tokens += Token(MermaidTokenKind.LT, baseOffset + pos, baseOffset + pos + 1, "<")
                                pos++; safePoint = baseOffset + pos
                            }
                        }
                    }
                }
                c == '>' && mode == LexMode.Class -> {
                    if (pos + 1 < buf.length && buf[pos + 1] == '>') {
                        tokens += Token(MermaidTokenKind.STEREOTYPE_CLOSE, baseOffset + pos, baseOffset + pos + 2, ">>")
                        pos += 2; safePoint = baseOffset + pos
                    } else {
                        tokens += Token(MermaidTokenKind.GT, baseOffset + pos, baseOffset + pos + 1, ">")
                        pos++; safePoint = baseOffset + pos
                    }
                }
                c == '.' && mode == LexMode.Class -> {
                    val remaining = buf.length - pos
                    if (remaining < 4 && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    val sub3 = if (remaining >= 3) buf.substring(pos, pos + 3) else null
                    val sub4 = if (remaining >= 4) buf.substring(pos, pos + 4) else null
                    when {
                        sub4 == "..|>" -> {
                            tokens += Token(MermaidTokenKind.CLASS_ARROW_REALIZATION, baseOffset + pos, baseOffset + pos + 4, "..|>")
                            pos += 4; safePoint = baseOffset + pos
                        }
                        sub3 == "..>" -> {
                            tokens += Token(MermaidTokenKind.CLASS_ARROW_DEPENDENCY, baseOffset + pos, baseOffset + pos + 3, "..>")
                            pos += 3; safePoint = baseOffset + pos
                        }
                        remaining >= 2 && buf[pos + 1] == '.' -> {
                            tokens += Token(MermaidTokenKind.CLASS_LINK_DASHED, baseOffset + pos, baseOffset + pos + 2, "..")
                            pos += 2; safePoint = baseOffset + pos
                        }
                        else -> {
                            tokens += errorTok(baseOffset + pos, ".")
                            diags += LexDiagnostic("Unexpected '.'", baseOffset + pos)
                            pos++; safePoint = baseOffset + pos
                        }
                    }
                }
                c == ':' && mode == LexMode.Class -> {
                    val remaining = buf.length - pos
                    if (remaining < 3 && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    if (remaining >= 3 && buf[pos + 1] == ':' && buf[pos + 2] == ':') {
                        tokens += Token(MermaidTokenKind.TRIPLE_COLON, baseOffset + pos, baseOffset + pos + 3, ":::")
                        pos += 3; safePoint = baseOffset + pos
                    } else {
                        tokens += Token(MermaidTokenKind.COLON, baseOffset + pos, baseOffset + pos + 1, ":")
                        pos++; safePoint = baseOffset + pos
                    }
                }
                c == '"' && (mode == LexMode.Class || mode == LexMode.State) -> {
                    var end = pos + 1
                    while (end < buf.length && buf[end] != '"' && buf[end] != '\n') end++
                    if (end >= buf.length && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    if (end >= buf.length || buf[end] == '\n') {
                        tokens += errorTok(baseOffset + pos, buf.substring(pos, end))
                        diags += LexDiagnostic("Unterminated string", baseOffset + pos)
                        pos = end; safePoint = baseOffset + pos
                    } else {
                        tokens += Token(MermaidTokenKind.STRING, baseOffset + pos, baseOffset + end + 1, buf.substring(pos + 1, end))
                        pos = end + 1; safePoint = baseOffset + pos
                    }
                }
                c == '[' -> {
                    if (mode == LexMode.State && pos + 2 < buf.length && buf[pos + 1] == '*' && buf[pos + 2] == ']') {
                        tokens += Token(MermaidTokenKind.START_END_TOKEN, baseOffset + pos, baseOffset + pos + 3, "[*]")
                        pos += 3; safePoint = baseOffset + pos
                    } else {
                        val r = scanDelimited(buf, pos, openLen = 1, close = "]", baseOffset, eos)
                        when (r) {
                            is DelimResult.Ok -> {
                                tokens += Token(MermaidTokenKind.LABEL, baseOffset + pos, baseOffset + r.endExclusive,
                                    buf.substring(pos + 1, r.endExclusive - 1))
                                pos = r.endExclusive; safePoint = baseOffset + pos
                            }
                            DelimResult.Suspend -> return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                            is DelimResult.Unterminated -> {
                                tokens += errorTok(baseOffset + pos, buf.substring(pos, r.consumeEnd))
                                diags += LexDiagnostic("Unterminated '[' label", baseOffset + pos)
                                pos = r.consumeEnd; safePoint = baseOffset + pos
                            }
                        }
                    }
                }
                c == '(' -> {
                    if (mode == LexMode.Class) {
                        tokens += Token(MermaidTokenKind.LPAREN, baseOffset + pos, baseOffset + pos + 1, "(")
                        pos++; safePoint = baseOffset + pos
                    } else if (pos + 1 >= buf.length) {
                        if (eos) {
                            tokens += errorTok(baseOffset + pos, "(")
                            diags += LexDiagnostic("Unterminated '(' shape", baseOffset + pos)
                            pos++; safePoint = baseOffset + pos
                        } else return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    } else if (buf[pos + 1] == '(') {
                        val r = scanDelimited(buf, pos, openLen = 2, close = "))", baseOffset, eos)
                        when (r) {
                            is DelimResult.Ok -> {
                                tokens += Token(MermaidTokenKind.LABEL_DOUBLE_PAREN, baseOffset + pos,
                                    baseOffset + r.endExclusive, buf.substring(pos + 2, r.endExclusive - 2))
                                pos = r.endExclusive; safePoint = baseOffset + pos
                            }
                            DelimResult.Suspend -> return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                            is DelimResult.Unterminated -> {
                                tokens += errorTok(baseOffset + pos, buf.substring(pos, r.consumeEnd))
                                diags += LexDiagnostic("Unterminated '((' shape", baseOffset + pos)
                                pos = r.consumeEnd; safePoint = baseOffset + pos
                            }
                        }
                    } else {
                        val r = scanDelimited(buf, pos, openLen = 1, close = ")", baseOffset, eos)
                        when (r) {
                            is DelimResult.Ok -> {
                                tokens += Token(MermaidTokenKind.LABEL_PAREN, baseOffset + pos,
                                    baseOffset + r.endExclusive, buf.substring(pos + 1, r.endExclusive - 1))
                                pos = r.endExclusive; safePoint = baseOffset + pos
                            }
                            DelimResult.Suspend -> return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                            is DelimResult.Unterminated -> {
                                tokens += errorTok(baseOffset + pos, buf.substring(pos, r.consumeEnd))
                                diags += LexDiagnostic("Unterminated '(' shape", baseOffset + pos)
                                pos = r.consumeEnd; safePoint = baseOffset + pos
                            }
                        }
                    }
                }
                c == '{' -> {
                    if (mode == LexMode.Class || mode == LexMode.State) {
                        tokens += Token(MermaidTokenKind.LBRACE, baseOffset + pos, baseOffset + pos + 1, "{")
                        pos++; safePoint = baseOffset + pos
                    } else {
                        val r = scanDelimited(buf, pos, openLen = 1, close = "}", baseOffset, eos)
                        when (r) {
                            is DelimResult.Ok -> {
                                tokens += Token(MermaidTokenKind.LABEL_BRACE, baseOffset + pos, baseOffset + r.endExclusive,
                                    buf.substring(pos + 1, r.endExclusive - 1))
                                pos = r.endExclusive; safePoint = baseOffset + pos
                            }
                            DelimResult.Suspend -> return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                            is DelimResult.Unterminated -> {
                                tokens += errorTok(baseOffset + pos, buf.substring(pos, r.consumeEnd))
                                diags += LexDiagnostic("Unterminated '{' shape", baseOffset + pos)
                                pos = r.consumeEnd; safePoint = baseOffset + pos
                            }
                        }
                    }
                }
                c == '|' -> {
                    val r = scanDelimited(buf, pos, openLen = 1, close = "|", baseOffset, eos)
                    when (r) {
                        is DelimResult.Ok -> {
                            tokens += Token(MermaidTokenKind.EDGE_LABEL, baseOffset + pos, baseOffset + r.endExclusive,
                                buf.substring(pos + 1, r.endExclusive - 1))
                            pos = r.endExclusive; safePoint = baseOffset + pos
                        }
                        DelimResult.Suspend -> return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                        is DelimResult.Unterminated -> {
                            tokens += errorTok(baseOffset + pos, buf.substring(pos, r.consumeEnd))
                            diags += LexDiagnostic("Unterminated '|' edge label", baseOffset + pos)
                            pos = r.consumeEnd; safePoint = baseOffset + pos
                        }
                    }
                }
                isIdentStart(c) -> {
                    var end = pos + 1
                    while (end < buf.length && isIdentCont(buf[end])) end++
                    if (end >= buf.length && !eos) {
                        return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                    }
                    var text = buf.substring(pos, end)
                    // Special-case "stateDiagram-v2" since '-' isn't a normal identifier char.
                    // If after the identifier we see '-', we need to lookahead enough to know
                    // whether it is part of a "stateDiagram-vN" header or the next token.
                    if (text == "stateDiagram" && end < buf.length && buf[end] == '-') {
                        // Need at least "-vN" (3 chars) to decide.
                        if (buf.length - end < 3 && !eos) {
                            return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                        }
                        if (buf.length - end >= 3 && buf[end + 1] == 'v' && buf[end + 2].isDigit()) {
                            var e2 = end + 3
                            while (e2 < buf.length && buf[e2].isDigit()) e2++
                            if (e2 >= buf.length && !eos) {
                                return suspendHere(buf, pos, baseOffset, tokens, diags, mode)
                            }
                            end = e2
                            text = buf.substring(pos, end)
                        }
                    }
                    val kind = classifyWord(text, mode)
                    tokens += Token(kind, baseOffset + pos, baseOffset + end, text)
                    pos = end
                    safePoint = baseOffset + pos
                    // Mode transitions on header detection.
                    if (mode == LexMode.Auto) {
                        when (kind) {
                            MermaidTokenKind.SEQUENCE_HEADER -> mode = LexMode.Sequence
                            MermaidTokenKind.CLASS_HEADER -> mode = LexMode.Class
                            MermaidTokenKind.STATE_HEADER -> mode = LexMode.State
                            MermaidTokenKind.KEYWORD_HEADER -> mode = LexMode.Flowchart
                        }
                    }
                }
                else -> {
                    tokens += errorTok(baseOffset + pos, c.toString())
                    diags += LexDiagnostic("Unexpected character '${c}'", baseOffset + pos)
                    pos++
                    safePoint = baseOffset + pos
                }
            }
        }

        return LexerStep(
            tokens = tokens,
            newState = MermaidLexerState(pending = "", mode = mode),
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
        mode: LexMode,
    ): LexerStep<MermaidLexerState> = LexerStep(
        tokens = tokens,
        newState = MermaidLexerState(pending = buf.substring(pos), mode = mode),
        safePoint = baseOffset + pos,
        diagnostics = diags,
    )

    private fun errorTok(start: Int, text: String): Token =
        Token(MermaidTokenKind.ERROR, start, start + text.length.coerceAtLeast(1), text)

    private fun scanDelimited(
        buf: String,
        openStart: Int,
        openLen: Int,
        close: String,
        baseOffset: Int,
        eos: Boolean,
    ): DelimResult {
        @Suppress("UNUSED_PARAMETER")
        val unusedBaseOffset = baseOffset
        val bodyStart = openStart + openLen
        var i = bodyStart
        while (i < buf.length) {
            if (buf[i] == '\n') {
                return DelimResult.Unterminated(consumeEnd = i)
            }
            if (matchesAt(buf, i, close)) {
                return DelimResult.Ok(endExclusive = i + close.length)
            }
            i++
        }
        return if (eos) DelimResult.Unterminated(consumeEnd = buf.length) else DelimResult.Suspend
    }

    private fun matchesAt(buf: String, at: Int, s: String): Boolean {
        if (at + s.length > buf.length) return false
        for (k in s.indices) if (buf[at + k] != s[k]) return false
        return true
    }

    private sealed interface DelimResult {
        data class Ok(val endExclusive: Int) : DelimResult
        data class Unterminated(val consumeEnd: Int) : DelimResult
        data object Suspend : DelimResult
    }

    private companion object {
        private val DIRECTIONS = setOf("TD", "TB", "LR", "RL", "BT")
        private val HEADER_KEYWORDS = setOf("flowchart", "graph")

        private fun isIdentStart(c: Char): Boolean =
            c.isLetter() || c == '_'

        private fun isIdentCont(c: Char): Boolean =
            c.isLetterOrDigit() || c == '_'

        private fun classifyWord(text: String, mode: LexMode): Int {
            // Header detection works in Auto mode (and remains valid in others as a no-op).
            if (mode == LexMode.Auto || mode == LexMode.Flowchart) {
                if (text == "sequenceDiagram") return MermaidTokenKind.SEQUENCE_HEADER
                if (text == "classDiagram") return MermaidTokenKind.CLASS_HEADER
                if (text == "stateDiagram" || text.startsWith("stateDiagram-")) return MermaidTokenKind.STATE_HEADER
                if (text in HEADER_KEYWORDS) return MermaidTokenKind.KEYWORD_HEADER
                if (text in DIRECTIONS) return MermaidTokenKind.DIRECTION
            }
            if (mode == LexMode.State) {
                return when (text) {
                    "stateDiagram" -> MermaidTokenKind.STATE_HEADER
                    "state" -> MermaidTokenKind.STATE_KW
                    "direction" -> MermaidTokenKind.DIRECTION_KW
                    "note", "Note" -> MermaidTokenKind.NOTE_KW
                    "left" -> MermaidTokenKind.LEFT_KW
                    "right" -> MermaidTokenKind.RIGHT_KW
                    "top" -> MermaidTokenKind.TOP_KW
                    "bottom" -> MermaidTokenKind.BOTTOM_KW
                    "of" -> MermaidTokenKind.OF_KW
                    "as" -> MermaidTokenKind.AS_KW
                    else -> if (text.startsWith("stateDiagram-")) MermaidTokenKind.STATE_HEADER
                            else MermaidTokenKind.IDENT
                }
            }
            if (mode == LexMode.Class) {
                return when (text) {
                    "classDiagram" -> MermaidTokenKind.CLASS_HEADER
                    "class" -> MermaidTokenKind.CLASS_KW
                    "namespace" -> MermaidTokenKind.NAMESPACE_KW
                    "cssClass" -> MermaidTokenKind.CSS_CLASS_KW
                    "direction" -> MermaidTokenKind.DIRECTION_KW
                    "note", "Note" -> MermaidTokenKind.NOTE_KW
                    "for" -> MermaidTokenKind.OF_KW
                    "left" -> MermaidTokenKind.LEFT_KW
                    "right" -> MermaidTokenKind.RIGHT_KW
                    "top" -> MermaidTokenKind.TOP_KW
                    "bottom" -> MermaidTokenKind.BOTTOM_KW
                    "of" -> MermaidTokenKind.OF_KW
                    else -> MermaidTokenKind.IDENT
                }
            }
            if (mode == LexMode.Sequence) {
                return when (text) {
                    "participant" -> MermaidTokenKind.PARTICIPANT_KW
                    "actor" -> MermaidTokenKind.ACTOR_KW
                    "note", "Note" -> MermaidTokenKind.NOTE_KW
                    "left" -> MermaidTokenKind.LEFT_KW
                    "right" -> MermaidTokenKind.RIGHT_KW
                    "of" -> MermaidTokenKind.OF_KW
                    "over" -> MermaidTokenKind.OVER_KW
                    "activate" -> MermaidTokenKind.ACTIVATE_KW
                    "deactivate" -> MermaidTokenKind.DEACTIVATE_KW
                    "loop" -> MermaidTokenKind.LOOP_KW
                    "alt" -> MermaidTokenKind.ALT_KW
                    "else" -> MermaidTokenKind.ELSE_KW
                    "opt" -> MermaidTokenKind.OPT_KW
                    "par" -> MermaidTokenKind.PAR_KW
                    "and" -> MermaidTokenKind.AND_KW
                    "critical" -> MermaidTokenKind.CRITICAL_KW
                    "option" -> MermaidTokenKind.OPTION_KW
                    "break" -> MermaidTokenKind.BREAK_KW
                    "end" -> MermaidTokenKind.END_KW
                    "autonumber" -> MermaidTokenKind.AUTONUMBER_KW
                    "as" -> MermaidTokenKind.AS_KW
                    "sequenceDiagram" -> MermaidTokenKind.SEQUENCE_HEADER
                    else -> MermaidTokenKind.IDENT
                }
            }
            return MermaidTokenKind.IDENT
        }
    }
}

/**
 * Streaming continuation state: holds the unconsumed prefix of the previous chunk and
 * the active [LexMode] (so chunk boundaries don't reset the mode).
 */
data class MermaidLexerState(
    val pending: String = "",
    val mode: LexMode = LexMode.Auto,
) : LexerState {
    override val pendingChars: Int get() = pending.length
}

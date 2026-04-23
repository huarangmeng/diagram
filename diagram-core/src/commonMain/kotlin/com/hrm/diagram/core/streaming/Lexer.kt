package com.hrm.diagram.core.streaming

/**
 * Resumable, append-only lexer contract shared by every parser front-end.
 *
 * See `docs/streaming.md` §3.1. Implementations MUST:
 * - Be **pure**: same `(state, input, offset)` ⇒ same [LexerStep].
 * - Be **append-safe**: chunks may split mid-token, mid-comment, mid-string, mid-codepoint.
 *   Anything not yet stable must be carried inside [LexerStep.newState], never mutated globally.
 * - Report a monotonically non-decreasing [LexerStep.safePoint] absolute offset; the host may
 *   discard original source bytes before that offset.
 *
 * Implementations are language-specific and live under `:diagram-parser`.
 */
public interface ResumableLexer<S : LexerState> {
    /** State a fresh session begins in. MUST be cheap and re-usable. */
    public fun initialState(): S

    /**
     * Feed one append-only chunk.
     *
     * @param state previous state (for the first call: [initialState]).
     * @param input the new chunk; treated as the substring of the canonical source starting at [offset].
     * @param offset absolute offset of [input]'s first char inside the canonical source string.
     * @param eos `true` IFF the stream is finalised (LLM finished); lexer MUST then flush any
     *            buffered partial token (commonly as an ERROR token + diagnostic) instead of holding it.
     */
    public fun feed(state: S, input: CharSequence, offset: Int, eos: Boolean = false): LexerStep<S>
}

/**
 * Marker for per-language continuation state.
 *
 * MUST be **immutable** (so hosts can checkpoint / rewind cheaply) and MUST be **value-comparable**
 * (data class / value class) so streaming tests can assert state equivalence between
 * "fed in one chunk" and "fed in N chunks" runs.
 */
public interface LexerState {
    /**
     * Number of chars carried over from the previous chunk that have NOT yet been emitted as tokens.
     * Used by hosts to size buffers; MUST equal `0` whenever the lexer is at a safe point.
     */
    public val pendingChars: Int
}

/**
 * Output of one [ResumableLexer.feed] call.
 *
 * Invariants enforced by tests in `commonTest`:
 * - `safePoint >= previous safePoint`
 * - `safePoint <= offset + input.length`
 * - tokens' spans, if any, MUST fall entirely before `safePoint` OR be marked as partial.
 */
public data class LexerStep<S : LexerState>(
    val tokens: List<Token>,
    val newState: S,
    /** Absolute source offset up to which all tokens have been finalised. */
    val safePoint: Int,
    /** Lex-level diagnostics (encoding errors, illegal chars). Parser-level diagnostics live in `Diagnostic`. */
    val diagnostics: List<LexDiagnostic> = emptyList(),
)

/**
 * A lexical token. `kind` is intentionally an opaque int so each language can define its own
 * token kind table without polluting `:diagram-core`. The [text] field is a view into the
 * original source — implementations SHOULD use [CharSequence.subSequence] to avoid copies.
 */
public data class Token(
    /** Per-language token kind id (see e.g. `MermaidTokenKind`, `DotTokenKind`). */
    val kind: Int,
    /** Inclusive absolute start offset in the canonical source. */
    val start: Int,
    /** Exclusive absolute end offset. `end > start`. */
    val end: Int,
    /** The token text. May be a sub-view of the source for zero-copy. */
    val text: CharSequence,
) {
    init {
        require(end > start) { "Token end must be > start (got $start..$end)" }
    }
    public val length: Int get() = end - start
}

/**
 * Lex-time diagnostic. Distinct from `Diagnostic` (which is parser/IR-level) so that hosts
 * may apply different policies (e.g. silently skip illegal control chars vs surface a ParseError).
 */
public data class LexDiagnostic(
    val message: String,
    val absoluteOffset: Int,
    val severity: Severity = Severity.ERROR,
) {
    public enum class Severity { ERROR, WARNING }
}

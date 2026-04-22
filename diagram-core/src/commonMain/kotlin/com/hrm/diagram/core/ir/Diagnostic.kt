package com.hrm.diagram.core.ir

enum class Severity { ERROR, WARNING, INFO }

/** Inclusive 1-based line/column range pointing back into the source text. */
data class Span(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
) {
    init {
        require(startLine >= 1 && startCol >= 1 && endLine >= startLine) { "invalid span: $this" }
    }
}

/**
 * Diagnostic codes follow `<LANG>-<E|W|I><three digits>` (see docs/diagnostics.md).
 * Parsers MUST NOT throw — they return a [ParseResult] carrying these instead.
 */
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val code: String,
    val span: Span? = null,
)

/**
 * Result envelope for any parser. `model` is null IFF parsing failed irrecoverably.
 * Diagnostics may be present even when [model] is non-null (warnings/info).
 */
data class ParseResult(
    val model: DiagramModel?,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }
}

package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.parser.mermaid.MermaidLexer
import com.hrm.diagram.parser.mermaid.MermaidLexerState
import com.hrm.diagram.parser.mermaid.MermaidStyleConfig
import com.hrm.diagram.parser.mermaid.MermaidStyleDecl
import com.hrm.diagram.parser.mermaid.MermaidStyleExtrasCodec
import com.hrm.diagram.parser.mermaid.MermaidStyleParsers
import com.hrm.diagram.parser.mermaid.MermaidTokenKind
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import com.hrm.diagram.render.streaming.SessionPipeline

/**
 * Top-level Mermaid pipeline. Lexes the incoming source once and routes complete logical lines
 * to one of two sub-pipelines:
 *  - [MermaidFlowchartSubPipeline] when the first non-blank header is `flowchart` / `graph`.
 *  - [MermaidSequenceSubPipeline] when the first non-blank header is `sequenceDiagram`.
 *
 * Until the dispatcher has seen the header line it buffers tokens; on EOS without any header
 * it falls back to the flowchart sub-pipeline (which will surface a clear diagnostic).
 */
internal class MermaidSessionPipeline(
    private val textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
) : SessionPipeline {

    private val lexer = MermaidLexer()
    private var lexState: MermaidLexerState = lexer.initialState()
    private val tokenBuffer: MutableList<Token> = ArrayList()
    private val pendingLines: MutableList<List<Token>> = ArrayList()
    private var sub: MermaidSubPipeline? = null

    // --- Style parsing state (Phase 1: themeVariables + classDef) ---
    private var rawPending: String = ""
    private var rawPendingAbsoluteOffset: Int = 0
    private var frontmatterStripped: Boolean = false
    private var styleConfig: MermaidStyleConfig? = null
    private val styleClasses: LinkedHashMap<String, MermaidStyleDecl> = LinkedHashMap()
    private val styleDiagnosticsAll: MutableList<Diagnostic> = ArrayList()
    private var cachedStyleExtras: Map<String, String> = emptyMap()

    override fun advance(
        previousSnapshot: DiagramSnapshot,
        chunk: CharSequence,
        absoluteOffset: Int,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val pre = preprocessForStyle(chunk, absoluteOffset, isFinal)
        styleDiagnosticsAll += pre.newStyleDiagnostics

        if (pre.lexerFeeds.isEmpty()) {
            // Still need to advance lexer state on EOS so pending is flushed deterministically.
            val step = lexer.feed(lexState, "", absoluteOffset, eos = isFinal)
            lexState = step.newState
            tokenBuffer += step.tokens
        } else {
            for ((i, feed) in pre.lexerFeeds.withIndex()) {
                // Safety: if we skipped any bytes between feeds, ensure the lexer does not carry
                // pending across gaps. Our preprocessor only cuts on NEWLINE boundaries, but keep
                // this guard to avoid accidental token corruption.
                if (i > 0 && feed.absoluteOffset != pre.lexerFeeds[i - 1].endAbsoluteOffset && lexState.pending.isNotEmpty()) {
                    lexState = lexState.copy(pending = "")
                }
                val step = lexer.feed(
                    lexState,
                    feed.text,
                    feed.absoluteOffset,
                    eos = isFinal && i == pre.lexerFeeds.lastIndex,
                )
                lexState = step.newState
                tokenBuffer += step.tokens
            }
        }

        val lines = drainLines(isFinal)

        // Decide the sub-pipeline using either: (a) lexer mode (after header was lexed) or
        // (b) the first non-blank line we have buffered so far.
        if (sub == null) {
            // Look for header in pendingLines + new lines.
            val all = pendingLines + lines
            for (line in all) {
                val firstSig = line.firstOrNull { it.kind != MermaidTokenKind.COMMENT }
                if (firstSig == null) continue
                when (firstSig.kind) {
                    MermaidTokenKind.SEQUENCE_HEADER -> {
                        sub = MermaidSequenceSubPipeline(textMeasurer); break
                    }
                    MermaidTokenKind.CLASS_HEADER -> {
                        sub = MermaidClassSubPipeline(textMeasurer); break
                    }
                    MermaidTokenKind.STATE_HEADER -> {
                        sub = MermaidStateSubPipeline(textMeasurer); break
                    }
                    MermaidTokenKind.ER_HEADER -> {
                        sub = MermaidErSubPipeline(textMeasurer); break
                    }
                    MermaidTokenKind.KEYWORD_HEADER -> {
                        sub = MermaidFlowchartSubPipeline(textMeasurer); break
                    }
                    else -> { /* keep looking */ }
                }
            }
            if (sub == null) {
                // Buffer until we know.
                pendingLines += lines
                if (isFinal) {
                    // Fallback: route as flowchart so caller still gets a diagnostic.
                    sub = MermaidFlowchartSubPipeline(textMeasurer)
                    val drained = pendingLines.toList()
                    pendingLines.clear()
                    return wrapWithStyleDiagnosticsAndHints(
                        sub!!.acceptLines(previousSnapshot, drained, seq, isFinal),
                        pre.newStyleDiagnostics,
                    )
                }
                // Empty advance: nothing to draw yet.
                return wrapWithStyleDiagnosticsAndHints(
                    emptyAdvance(previousSnapshot, seq, isFinal),
                    pre.newStyleDiagnostics,
                )
            }
        }

        val toFeed = if (pendingLines.isNotEmpty()) {
            val combined = pendingLines.toMutableList().also { it.addAll(lines) }
            pendingLines.clear()
            combined
        } else lines

        return wrapWithStyleDiagnosticsAndHints(
            sub!!.acceptLines(previousSnapshot, toFeed, seq, isFinal),
            pre.newStyleDiagnostics,
        )
    }

    private fun emptyAdvance(prev: DiagramSnapshot, seq: Long, isFinal: Boolean): PipelineAdvance {
        val snap = prev.copy(seq = seq, isFinal = isFinal)
        return PipelineAdvance(
            snapshot = snap,
            patch = SessionPatch.empty(seq, isFinal),
        )
    }

    private fun drainLines(eos: Boolean): List<List<Token>> {
        val out = ArrayList<List<Token>>()
        var start = 0
        for (i in tokenBuffer.indices) {
            if (tokenBuffer[i].kind == MermaidTokenKind.NEWLINE) {
                if (i > start) out += tokenBuffer.subList(start, i).toList()
                start = i + 1
            }
        }
        if (eos && start < tokenBuffer.size) {
            out += tokenBuffer.subList(start, tokenBuffer.size).toList()
            tokenBuffer.clear()
        } else {
            val tail = if (start < tokenBuffer.size) tokenBuffer.subList(start, tokenBuffer.size).toList() else emptyList()
            tokenBuffer.clear()
            tokenBuffer.addAll(tail)
        }
        return out
    }

    private data class LexerFeed(
        val text: String,
        val absoluteOffset: Int,
        val endAbsoluteOffset: Int,
    )

    private data class StylePreprocessResult(
        val lexerFeeds: List<LexerFeed>,
        val newStyleDiagnostics: List<Diagnostic>,
    )

    /**
     * Preprocess incoming source to:
     * - Strip Mermaid frontmatter (`--- ... ---`) at the beginning of the document.
     * - Parse and remove `classDef ...` lines from the lexer stream (Phase 1).
     *
     * Rationale:
     * - Mermaid style decl strings contain `:` / `#` / `-` which are not uniformly tokenized in all lexer modes.
     * - Feeding these lines to the Mermaid lexer produces spurious ERROR tokens and breaks downstream parsers.
     */
    private fun preprocessForStyle(chunk: CharSequence, absoluteOffset: Int, isFinal: Boolean): StylePreprocessResult {
        // Merge with previous raw pending (line buffering) similar to the lexer.
        val buf = buildString {
            append(rawPending)
            append(chunk)
        }
        val baseOffset = absoluteOffset - rawPending.length
        rawPendingAbsoluteOffset = baseOffset
        rawPending = ""

        val newDiags = ArrayList<Diagnostic>()
        var startIdx = 0

        // Frontmatter stripping: only once, only if it appears at the very beginning.
        if (!frontmatterStripped && baseOffset == 0) {
            val fm = tryStripFrontmatter(buf)
            if (fm != null) {
                frontmatterStripped = true
                startIdx = fm.endIdxExclusive
                // Always strip frontmatter from lexer input; if it contains theme config, parse it.
                val r = MermaidStyleParsers.parseFrontmatterThemeConfig(fm.text)
                if (r != null) {
                    styleConfig = r.config
                    newDiags += r.diagnostics
                    cachedStyleExtras = emptyMap()
                }
            } else if (buf.startsWith("---") && !isFinal) {
                // Potential frontmatter split across chunks; keep buffering until closed.
                rawPending = buf
                return StylePreprocessResult(emptyList(), emptyList())
            } else {
                // No frontmatter.
                frontmatterStripped = true
            }
        }

        val feeds = ArrayList<LexerFeed>()

        // Scan line-by-line. We only decide to skip `classDef` when the whole line is present.
        var i = startIdx
        var runStart: Int? = null
        while (i < buf.length) {
            val nl = buf.indexOf('\n', startIndex = i)
            if (nl < 0) break
            val lineStart = i
            val lineEndExclusive = nl + 1 // include '\n'
            val rawLine = buf.substring(lineStart, lineEndExclusive)
            val trimmedLeading = rawLine.trimStart()

            if (trimmedLeading.startsWith("classDef ")) {
                // Flush pending kept run before the skipped line.
                if (runStart != null && runStart < lineStart) {
                    val seg = buf.substring(runStart, lineStart)
                    feeds += LexerFeed(
                        text = seg,
                        absoluteOffset = baseOffset + runStart,
                        endAbsoluteOffset = baseOffset + lineStart,
                    )
                    runStart = null
                }
                val parsed = MermaidStyleParsers.parseClassDefLine(trimmedLeading.trimEnd())
                if (parsed != null) {
                    for (cls in parsed.classes) styleClasses[cls.name] = cls.decl
                    newDiags += parsed.diagnostics
                    cachedStyleExtras = emptyMap()
                }
                // Skip the whole line (including newline).
            } else {
                if (runStart == null) runStart = lineStart
            }
            i = lineEndExclusive
        }

        // Tail without newline: keep for next chunk unless EOS.
        val tailStart = i
        val tail = buf.substring(tailStart)
        if (isFinal) {
            if (tail.isNotEmpty()) {
                val trimmedLeading = tail.trimStart()
                if (trimmedLeading.startsWith("classDef ")) {
                    val parsed = MermaidStyleParsers.parseClassDefLine(trimmedLeading.trimEnd())
                    if (parsed != null) {
                        for (cls in parsed.classes) styleClasses[cls.name] = cls.decl
                        newDiags += parsed.diagnostics
                        cachedStyleExtras = emptyMap()
                    }
                    // Do not feed to lexer.
                } else {
                    if (runStart == null) runStart = tailStart
                }
            }
        } else {
            // Buffer tail to ensure we never split a style line across chunks.
            rawPending = tail
        }

        // Flush last kept run.
        if (runStart != null) {
            val end = if (isFinal) buf.length else tailStart
            if (end > runStart) {
                val seg = buf.substring(runStart, end)
                feeds += LexerFeed(
                    text = seg,
                    absoluteOffset = baseOffset + runStart,
                    endAbsoluteOffset = baseOffset + end,
                )
            }
        }

        return StylePreprocessResult(feeds, newDiags)
    }

    private data class FrontmatterStrip(val text: String, val endIdxExclusive: Int)

    private fun tryStripFrontmatter(buf: String): FrontmatterStrip? {
        // Only treat the very beginning as frontmatter.
        if (!buf.startsWith("---")) return null
        // Find a closing line that is exactly '---' (possibly with trailing spaces).
        var idx = 0
        while (true) {
            val nl = buf.indexOf('\n', startIndex = idx)
            if (nl < 0) return null
            val line = buf.substring(idx, nl).trimEnd()
            if (idx != 0 && line == "---") {
                val end = nl + 1
                return FrontmatterStrip(text = buf.substring(0, end), endIdxExclusive = end)
            }
            idx = nl + 1
        }
    }

    private fun wrapWithStyleDiagnosticsAndHints(advance: PipelineAdvance, newStyleDiags: List<Diagnostic>): PipelineAdvance {
        val styledSnapshot = injectStyleHints(advance.snapshot)
        val mergedSnapshot = if (styleDiagnosticsAll.isEmpty()) styledSnapshot else {
            styledSnapshot.copy(diagnostics = styledSnapshot.diagnostics + styleDiagnosticsAll)
        }
        val mergedPatch = if (newStyleDiags.isEmpty()) {
            advance.patch
        } else {
            advance.patch.copy(newDiagnostics = advance.patch.newDiagnostics + newStyleDiags)
        }
        return advance.copy(snapshot = mergedSnapshot, patch = mergedPatch)
    }

    private fun injectStyleHints(snapshot: DiagramSnapshot): DiagramSnapshot {
        val ir = snapshot.ir ?: return snapshot
        // Only inject once per advance; extras are cached and updated when style inputs change.
        val extras = ensureStyleExtrasCached()
        if (extras.isEmpty()) return snapshot

        val updated: DiagramModel = when (ir) {
            is GraphIR -> ir.copy(styleHints = mergeHints(ir.styleHints, extras))
            // Other Mermaid IR families can opt-in later. For now, keep as-is.
            else -> ir
        }
        return if (updated === ir) snapshot else snapshot.copy(ir = updated)
    }

    private fun mergeHints(old: StyleHints, styleExtras: Map<String, String>): StyleHints {
        val themeName = styleConfig?.theme?.name?.lowercase()
        val mergedExtras = if (old.extras.isEmpty()) styleExtras else old.extras + styleExtras
        return old.copy(
            theme = themeName ?: old.theme,
            extras = mergedExtras,
        )
    }

    private fun ensureStyleExtrasCached(): Map<String, String> {
        // Rebuild lazily only when we have new style inputs and cache is empty.
        if (cachedStyleExtras.isNotEmpty()) return cachedStyleExtras
        val out = LinkedHashMap<String, String>()
        val cfg = styleConfig
        if (cfg != null) {
            out["mermaid.styleModelVersion"] = "1"
            cfg.theme?.let { out["mermaid.theme"] = it.name.lowercase() }
            cfg.themeTokens?.let { out["mermaid.themeTokens"] = MermaidStyleExtrasCodec.encodeThemeTokens(it) }
        }
        if (styleClasses.isNotEmpty()) {
            out["mermaid.classDefs"] = MermaidStyleExtrasCodec.encodeClassDefs(styleClasses)
        }
        cachedStyleExtras = out
        return cachedStyleExtras
    }

    override fun dispose() {
        tokenBuffer.clear()
        pendingLines.clear()
        sub?.dispose()
        sub = null
        rawPending = ""
        frontmatterStripped = false
        styleConfig = null
        styleClasses.clear()
        styleDiagnosticsAll.clear()
        cachedStyleExtras = emptyMap()
    }
}

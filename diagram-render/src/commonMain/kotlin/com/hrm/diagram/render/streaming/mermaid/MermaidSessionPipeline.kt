package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.parser.mermaid.MermaidLexer
import com.hrm.diagram.parser.mermaid.MermaidLexerState
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

    override fun advance(
        previousSnapshot: DiagramSnapshot,
        chunk: CharSequence,
        absoluteOffset: Int,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val step = lexer.feed(lexState, chunk, absoluteOffset, eos = isFinal)
        lexState = step.newState
        tokenBuffer += step.tokens

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
                    return sub!!.acceptLines(previousSnapshot, drained, seq, isFinal)
                }
                // Empty advance: nothing to draw yet.
                return emptyAdvance(previousSnapshot, seq, isFinal)
            }
        }

        val toFeed = if (pendingLines.isNotEmpty()) {
            val combined = pendingLines.toMutableList().also { it.addAll(lines) }
            pendingLines.clear()
            combined
        } else lines

        return sub!!.acceptLines(previousSnapshot, toFeed, seq, isFinal)
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

    override fun dispose() {
        tokenBuffer.clear()
        pendingLines.clear()
        sub?.dispose()
        sub = null
    }
}

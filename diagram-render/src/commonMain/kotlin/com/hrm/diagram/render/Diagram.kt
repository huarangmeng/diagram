package com.hrm.diagram.render

import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.HeuristicTextMeasurer
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.render.streaming.DiagramSession
import com.hrm.diagram.render.streaming.SessionPipeline
import com.hrm.diagram.render.streaming.StubSessionPipeline
import com.hrm.diagram.render.streaming.mermaid.MermaidSessionPipeline
import com.hrm.diagram.render.streaming.plantuml.PlantUmlSessionPipeline

/**
 * Top-level entry point for the diagram framework. Verbose API surface lives in subpackages
 * (`com.hrm.diagram.render.streaming`, etc.); this object exists purely as a discoverable
 * facade so callers can write `Diagram.session(...)` / `Diagram.parse(...)`.
 *
 * See `docs/api.md` for the full surface contract.
 */
object Diagram {
    /**
     * Open a streaming session — the primary use case for LLM-driven incremental rendering.
     * See `docs/streaming.md`.
     *
     * If [pipeline] is omitted, dispatches to the best registered pipeline for [language]:
     * - [SourceLanguage.MERMAID] → [MermaidSessionPipeline] (Phase 1 flowchart subset)
     * - [SourceLanguage.PLANTUML] → [PlantUmlSessionPipeline] (Phase 4 sequence MVP)
     * - [SourceLanguage.DOT]      → [StubSessionPipeline] (parsers land in later phases)
     */
    fun session(
        language: SourceLanguage,
        theme: DiagramTheme = DiagramTheme.Default,
        layoutOptions: LayoutOptions = LayoutOptions(),
        textMeasurer: TextMeasurer = HeuristicTextMeasurer(),
        pipeline: SessionPipeline = defaultPipelineFor(language, textMeasurer),
    ): DiagramSession = DiagramSession.create(language, theme, layoutOptions, pipeline)

    private fun defaultPipelineFor(
        language: SourceLanguage,
        textMeasurer: TextMeasurer,
    ): SessionPipeline = when (language) {
        SourceLanguage.MERMAID -> MermaidSessionPipeline(textMeasurer = textMeasurer)
        SourceLanguage.PLANTUML -> PlantUmlSessionPipeline(textMeasurer = textMeasurer)
        SourceLanguage.DOT -> StubSessionPipeline()
    }
}

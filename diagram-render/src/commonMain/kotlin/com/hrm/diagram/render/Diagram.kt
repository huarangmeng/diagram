package com.hrm.diagram.render

import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.render.streaming.DiagramSession
import com.hrm.diagram.render.streaming.SessionPipeline
import com.hrm.diagram.render.streaming.StubSessionPipeline
import com.hrm.diagram.render.streaming.mermaid.MermaidSessionPipeline

/**
 * Top-level entry point for the diagram framework. Verbose API surface lives in subpackages
 * (`com.hrm.diagram.render.streaming`, etc.); this object exists purely as a discoverable
 * facade so callers can write `Diagram.session(...)` / `Diagram.parse(...)`.
 *
 * See `docs/api.md` for the full surface contract.
 */
public object Diagram {
    /**
     * Open a streaming session — the primary use case for LLM-driven incremental rendering.
     * See `docs/streaming.md`.
     *
     * If [pipeline] is omitted, dispatches to the best registered pipeline for [language]:
     * - [SourceLanguage.MERMAID] → [MermaidSessionPipeline] (Phase 1 flowchart subset)
     * - PlantUML / DOT             → [StubSessionPipeline] (parsers land in later phases)
     */
    public fun session(
        language: SourceLanguage,
        theme: DiagramTheme = DiagramTheme.Default,
        layoutOptions: LayoutOptions = LayoutOptions(),
        pipeline: SessionPipeline = defaultPipelineFor(language),
    ): DiagramSession = DiagramSession.create(language, theme, layoutOptions, pipeline)

    private fun defaultPipelineFor(language: SourceLanguage): SessionPipeline = when (language) {
        SourceLanguage.MERMAID -> MermaidSessionPipeline()
        SourceLanguage.PLANTUML, SourceLanguage.DOT -> StubSessionPipeline()
    }
}

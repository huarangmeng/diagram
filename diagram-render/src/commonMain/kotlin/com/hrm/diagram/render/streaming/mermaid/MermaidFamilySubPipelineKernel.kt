package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.DiagramModel
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.DrawEntitySnapshotCache
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch

/**
 * Shared template for Mermaid family sub-pipelines that parse token lines into a non-GraphIR model.
 *
 * GraphIR diagrams use [com.hrm.diagram.render.streaming.kernel.StreamingGraphPipelineKernel].
 * This kernel keeps the remaining parser/layout/render snapshot assembly in one place while the
 * top-level [MermaidSessionPipeline] still owns final DrawEntity delta submission.
 */
internal class MermaidFamilySubPipelineKernel<M : DiagramModel>(
    private val acceptLine: (List<Token>) -> IrPatchBatch,
    private val snapshot: () -> M,
    private val diagnostics: () -> List<Diagnostic>,
    private val transformModel: (M) -> M = { it },
    private val beforeLayout: (M) -> Unit = {},
    private val layout: (previous: LaidOutDiagram?, model: M, options: LayoutOptions) -> LaidOutDiagram,
    private val renderEntities: (model: M, laidOut: LaidOutDiagram) -> List<DrawEntity>,
    private val layoutOptions: (model: M, isFinal: Boolean) -> LayoutOptions = { _, isFinal ->
        LayoutOptions(incremental = !isFinal, allowGlobalReflow = isFinal)
    },
    private val postLayout: (model: M, laidOut: LaidOutDiagram, seq: Long) -> LaidOutDiagram = { _, laidOut, _ ->
        laidOut
    },
    private val patchFactory: (
        seq: Long,
        isFinal: Boolean,
        patches: List<IrPatch>,
        diagnostics: List<Diagnostic>,
    ) -> SessionPatch = { seq, isFinal, _, diagnostics ->
        if (diagnostics.isEmpty()) {
            SessionPatch.empty(seq, isFinal)
        } else {
            SessionPatch(
                seq = seq,
                addedNodes = emptyList(),
                addedEdges = emptyList(),
                addedDrawCommands = emptyList(),
                newDiagnostics = diagnostics,
                isFinal = isFinal,
            )
        }
    },
) {
    private val entityCache = DrawEntitySnapshotCache()

    fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        val newPatches = ArrayList<IrPatch>()
        for (line in lines) {
            newPatches += acceptLine(line).patches
        }
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }
        val model = transformModel(snapshot())
        beforeLayout(model)
        val laidOut = layout(previousSnapshot.laidOut, model, layoutOptions(model, isFinal))
            .let { postLayout(model, it, seq) }
        val drawEntities = entityCache.store(renderEntities(model, laidOut))
        return PipelineAdvance(
            snapshot = DiagramSnapshot(
                ir = model,
                laidOut = laidOut,
                drawCommands = drawEntities.flatMap { it.commands },
                diagnostics = diagnostics(),
                seq = seq,
                isFinal = isFinal,
                sourceLanguage = previousSnapshot.sourceLanguage,
            ),
            patch = patchFactory(seq, isFinal, newPatches, newDiagnostics),
            irBatch = IrPatchBatch(seq, newPatches),
        )
    }

    fun drawEntities(): List<DrawEntity> = entityCache.snapshot()

    fun clear() {
        entityCache.clear()
    }
}

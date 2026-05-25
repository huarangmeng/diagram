package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.kernel.StreamingGraphPipelineKernel

internal class MermaidGraphSubPipelineKernel(
    private val acceptLine: (List<Token>) -> IrPatchBatch,
    private val snapshot: () -> GraphIR,
    private val diagnostics: () -> List<Diagnostic>,
    private val graphKernel: StreamingGraphPipelineKernel,
    private val beforeAdvance: (GraphIR, Boolean) -> Unit = { _, _ -> },
) {
    private var graphStyles: MermaidGraphStyleState? = null

    fun updateGraphStyles(styles: MermaidGraphStyleState) {
        graphStyles = styles
    }

    fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        for (line in lines) {
            acceptLine(line)
        }
        val ir0 = snapshot()
        val ir = graphStyles?.applyTo(ir0) ?: ir0
        beforeAdvance(ir, isFinal)
        return graphKernel.advance(
            previousSnapshot = previousSnapshot,
            seq = seq,
            isFinal = isFinal,
            ir = ir,
            diagnostics = diagnostics(),
        )
    }

    fun drawEntities(): List<DrawEntity> = graphKernel.drawEntities()

    fun clear() {
        graphKernel.clear()
    }
}

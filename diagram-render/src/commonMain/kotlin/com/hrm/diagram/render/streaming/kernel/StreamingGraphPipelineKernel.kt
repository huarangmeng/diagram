package com.hrm.diagram.render.streaming.kernel

import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.IncrementalLayout
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.sugiyama.SugiyamaLayouts
import com.hrm.diagram.render.cache.DrawCommandStore
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.cache.withMeasuredEntityTextBounds
import com.hrm.diagram.render.graph.GraphMeasurePolicy
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.PipelineAdvanceAssembler
import com.hrm.diagram.render.streaming.StreamingDiffTracker

internal class StreamingGraphPipelineKernel(
    private val textMeasurer: TextMeasurer,
    private val sourceLanguage: SourceLanguage,
    private val measurePolicy: GraphMeasurePolicy,
    private val layout: IncrementalLayout<GraphIR>,
    private val renderEntities: (GraphIR, LaidOutDiagram) -> List<DrawEntity>,
    private val layoutModel: (GraphIR) -> GraphIR = { it },
    private val layoutOptions: (GraphIR, Boolean) -> LayoutOptions = { ir, isFinal ->
        LayoutOptions(
            direction = ir.styleHints.direction,
            incremental = !isFinal,
            allowGlobalReflow = isFinal,
            extras = ir.styleHints.extras,
        )
    },
    private val postLayout: (GraphIR, LaidOutDiagram) -> LaidOutDiagram = { _, laid -> laid },
    edgeKeyOf: (index: Int, edge: Edge) -> String = { index, edge -> "${edge.from.value}->${edge.to.value}:$index" },
) {
    private val drawStore = DrawCommandStore()
    private val diffTracker = StreamingDiffTracker(edgeKeyOf)

    fun advance(
        previousSnapshot: DiagramSnapshot,
        seq: Long,
        isFinal: Boolean,
        ir: GraphIR,
        diagnostics: List<Diagnostic>,
    ): PipelineAdvance {
        measurePolicy.measure(ir, remeasure = isFinal)
        val laid = layout
            .layout(previousSnapshot.laidOut, layoutModel(ir), layoutOptions(ir, isFinal))
            .copy(source = ir, seq = seq)
            .let { postLayout(ir, it) }
        val drawDelta = drawStore.updateEntities(renderEntities(ir, laid).withMeasuredEntityTextBounds(textMeasurer))
        val diff = diffTracker.diffGraph(ir, diagnostics)
        return PipelineAdvanceAssembler.assemble(
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = sourceLanguage,
            model = ir,
            laidOut = laid,
            drawDelta = drawDelta,
            diagnostics = diagnostics,
            diff = diff,
        )
    }

    fun clear() {
        drawStore.clear()
        diffTracker.reset()
        measurePolicy.clear()
    }

    companion object {
        fun sugiyamaLayout(defaultNodeSize: Size, measurePolicy: GraphMeasurePolicy): IncrementalLayout<GraphIR> =
            SugiyamaLayouts.forGraph(
                defaultNodeSize = defaultNodeSize,
                nodeSizeOf = measurePolicy::sizeOf,
            )
    }
}

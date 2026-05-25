package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.parser.mermaid.MermaidFlowchartParser
import com.hrm.diagram.parser.mermaid.MermaidTokenKind
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.graph.GraphIrRenderer
import com.hrm.diagram.render.graph.GraphMeasurePolicy
import com.hrm.diagram.render.graph.GraphRenderStyle
import com.hrm.diagram.render.graph.graphLabelText
import com.hrm.diagram.render.streaming.BatchLineStreamingSubPipeline
import com.hrm.diagram.render.streaming.DrawEntitySnapshotProvider
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.kernel.StreamingGraphPipelineKernel

/**
 * Sub-pipeline for Mermaid flowchart. Syntax and style adaptation stay here; GraphIR
 * measuring/layout/rendering is delegated to the shared graph modules.
 */
internal class MermaidFlowchartSubPipeline(
    private val textMeasurer: TextMeasurer,
) : MermaidSubPipeline {
    private val parser = MermaidFlowchartParser()
    private val labelFont = FontSpec(family = "sans-serif", sizeSp = 13f)
    private val edgeLabelFont = FontSpec(family = "sans-serif", sizeSp = 11f)
    private val measurePolicy = GraphMeasurePolicy(
        textMeasurer = textMeasurer,
        defaultSize = Size(120f, 48f),
        maxWidth = 220f,
        minWidth = 64f,
        minHeight = 36f,
        labelOf = ::labelTextOf,
        fontOf = { labelFont },
        paddingOf = { node ->
            when (node.shape) {
                NodeShape.Circle, NodeShape.Diamond -> 28f to 18f
                NodeShape.Stadium, NodeShape.RoundedBox -> 18f to 12f
                else -> 14f to 10f
            }
        },
    )
    private val renderer = GraphIrRenderer(
        textMeasurer,
        GraphRenderStyle(
            prefix = "mermaid",
            nodeFont = labelFont,
            edgeFont = edgeLabelFont,
            nodeFill = Color(0xFFE3F2FDU.toInt()),
            nodeStroke = Color(0xFF1565C0U.toInt()),
            nodeText = Color(0xFF0D47A1U.toInt()),
            edgeColor = Color(0xFF455A64U.toInt()),
            edgeLabelText = Color(0xFF263238U.toInt()),
            edgeLabelBg = Color(0xF0FFFFFFU.toInt()),
            nodeCorner = { node, rect ->
                when (node.shape) {
                    NodeShape.Circle, NodeShape.Stadium -> minOf(rect.size.width, rect.size.height) / 2f
                    NodeShape.RoundedBox -> 14f
                    else -> 4f
                }
            },
            nodeLabel = ::labelTextOf,
            nodeFontOf = { _, _ -> labelFont },
        ),
    )
    private val kernel = StreamingGraphPipelineKernel(
        textMeasurer = textMeasurer,
        sourceLanguage = SourceLanguage.MERMAID,
        measurePolicy = measurePolicy,
        layout = StreamingGraphPipelineKernel.sugiyamaLayout(Size(120f, 48f), measurePolicy),
        renderEntities = { graph, laid ->
            renderer.render(graph, laid).also { lastDrawEntities = it }
        },
    )
    private var graphStyles: MermaidGraphStyleState? = null

    var lastDrawEntities: List<DrawEntity> = emptyList()
        private set

    override fun updateGraphStyles(styles: MermaidGraphStyleState) {
        graphStyles = styles
    }

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance {
        for (lineToks in lines) {
            parser.acceptLine(lineToks)
        }

        val rawIr: GraphIR = parser.snapshot()
        val ir: GraphIR = graphStyles?.applyTo(rawIr) ?: rawIr
        return kernel.advance(
            previousSnapshot = previousSnapshot,
            seq = seq,
            isFinal = isFinal,
            ir = ir,
            diagnostics = parser.diagnosticsSnapshot(),
        )
    }

    override fun dispose() {
        kernel.clear()
        lastDrawEntities = emptyList()
    }

    override fun drawEntitiesFor(snapshot: DiagramSnapshot): List<DrawEntity> = lastDrawEntities

    private fun labelTextOf(node: Node): String =
        graphLabelText(node.label).ifBlank { node.id.value }
}

/** Internal SPI used by the dispatcher to delegate per-line work. */
internal interface MermaidSubPipeline : DrawEntitySnapshotProvider, BatchLineStreamingSubPipeline<List<Token>, PipelineAdvance> {
    /** Optional hook for Mermaid GraphIR-based styling (flowchart/erDiagram). */
    fun updateGraphStyles(styles: MermaidGraphStyleState) {}

    /** Optional hook for non-GraphIR frontmatter/style extras needed during render. */
    fun updateStyleExtras(extras: Map<String, String>) {}

    override fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance

}

/** Helper kept here to avoid duplicating across sub-pipelines. */
internal fun isLineNonBlank(line: List<Token>): Boolean =
    line.any { it.kind != MermaidTokenKind.COMMENT && it.kind != MermaidTokenKind.NEWLINE }

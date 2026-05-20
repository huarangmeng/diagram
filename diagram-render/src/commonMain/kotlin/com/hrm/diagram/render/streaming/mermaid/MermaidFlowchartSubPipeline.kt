package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.layout.LayoutOptions
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.core.text.TextMeasurer
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.parser.mermaid.MermaidFlowchartParser
import com.hrm.diagram.parser.mermaid.MermaidTokenKind
import com.hrm.diagram.render.cache.DrawEntity
import com.hrm.diagram.render.graph.GraphIrRenderer
import com.hrm.diagram.render.graph.GraphMeasurePolicy
import com.hrm.diagram.render.graph.GraphRenderStyle
import com.hrm.diagram.render.graph.graphLabelText
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
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
    private val layout = StreamingGraphPipelineKernel.sugiyamaLayout(Size(120f, 48f), measurePolicy)
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
        val newPatches = ArrayList<IrPatch>()
        val addedNodeIds = ArrayList<com.hrm.diagram.core.ir.NodeId>()
        for (lineToks in lines) {
            val batch = parser.acceptLine(lineToks)
            for (patch in batch.patches) {
                newPatches += patch
                if (patch is IrPatch.AddNode) addedNodeIds += patch.node.id
            }
        }

        val rawIr: GraphIR = parser.snapshot()
        val ir: GraphIR = graphStyles?.applyTo(rawIr) ?: rawIr
        measurePolicy.measure(ir, remeasure = isFinal)
        val laidOut: LaidOutDiagram = layout
            .layout(
                previousSnapshot.laidOut,
                ir,
                LayoutOptions(direction = ir.styleHints.direction, incremental = !isFinal, allowGlobalReflow = isFinal),
            )
            .copy(source = ir, seq = seq)
        lastDrawEntities = renderer.render(ir, laidOut)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }
        return PipelineAdvance(
            snapshot = DiagramSnapshot(
                ir = ir,
                laidOut = laidOut,
                drawCommands = lastDrawEntities.flatMap { it.commands },
                diagnostics = parser.diagnosticsSnapshot(),
                seq = seq,
                isFinal = isFinal,
                sourceLanguage = previousSnapshot.sourceLanguage,
            ),
            patch = SessionPatch(
                seq = seq,
                addedNodes = addedNodeIds,
                addedEdges = newPatches.filterIsInstance<IrPatch.AddEdge>().map { it.edge },
                addedDrawCommands = lastDrawEntities.flatMap { it.commands },
                newDiagnostics = newDiagnostics,
                isFinal = isFinal,
            ),
            irBatch = IrPatchBatch(seq, newPatches),
        )
    }

    override fun dispose() {
        measurePolicy.clear()
        lastDrawEntities = emptyList()
    }

    override fun drawEntitiesFor(snapshot: DiagramSnapshot): List<DrawEntity> = lastDrawEntities

    private fun labelTextOf(node: Node): String =
        graphLabelText(node.label).ifBlank { node.id.value }
}

/** Internal SPI used by the dispatcher to delegate per-line work. */
internal interface MermaidSubPipeline {
    /** Optional hook for Mermaid GraphIR-based styling (flowchart/erDiagram). */
    fun updateGraphStyles(styles: MermaidGraphStyleState) {}

    /** Optional hook for non-GraphIR frontmatter/style extras needed during render. */
    fun updateStyleExtras(extras: Map<String, String>) {}

    fun acceptLines(
        previousSnapshot: DiagramSnapshot,
        lines: List<List<Token>>,
        seq: Long,
        isFinal: Boolean,
    ): PipelineAdvance

    fun drawEntitiesFor(snapshot: DiagramSnapshot): List<DrawEntity>

    fun dispose() {}
}

/** Helper kept here to avoid duplicating across sub-pipelines. */
internal fun isLineNonBlank(line: List<Token>): Boolean =
    line.any { it.kind != MermaidTokenKind.COMMENT && it.kind != MermaidTokenKind.NEWLINE }

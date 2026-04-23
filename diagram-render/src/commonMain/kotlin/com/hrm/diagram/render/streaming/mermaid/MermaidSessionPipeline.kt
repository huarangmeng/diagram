package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.draw.FontSpec
import com.hrm.diagram.core.draw.PathCmd
import com.hrm.diagram.core.draw.PathOp
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Stroke
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.layout.EdgeRoute
import com.hrm.diagram.layout.LaidOutDiagram
import com.hrm.diagram.layout.RouteKind
import com.hrm.diagram.parser.mermaid.MermaidFlowchartParser
import com.hrm.diagram.parser.mermaid.MermaidLexer
import com.hrm.diagram.parser.mermaid.MermaidLexerState
import com.hrm.diagram.parser.mermaid.MermaidTokenKind
import com.hrm.diagram.render.streaming.DiagramSnapshot
import com.hrm.diagram.render.streaming.PipelineAdvance
import com.hrm.diagram.render.streaming.SessionPatch
import com.hrm.diagram.render.streaming.SessionPipeline

/**
 * Real (non-stub) [SessionPipeline] for `SourceLanguage.MERMAID` flowcharts.
 *
 * Pipeline per [advance]:
 * 1. Feed chunk through [MermaidLexer] → new tokens.
 * 2. Append to a token buffer; split into complete logical lines on NEWLINE.
 * 3. Feed each complete line to [MermaidFlowchartParser] → IR patches.
 * 4. Run a trivial "row layout" (deterministic, pinned: existing node positions never move)
 *    to produce a [LaidOutDiagram].
 * 5. Render IR + layout into a flat [DrawCommand] list.
 *
 * The layout is intentionally minimal — Phase 1 only proves the streaming pipeline shape;
 * Sugiyama lands in a follow-up todo. Even so, the pinning contract from `IncrementalLayout`
 * is honoured: existing nodes keep their `Rect` byte-for-byte.
 */
public class MermaidSessionPipeline : SessionPipeline {

    private val lexer = MermaidLexer()
    private var lexState: MermaidLexerState = lexer.initialState()
    private val tokenBuffer: MutableList<Token> = ArrayList()
    private val parser = MermaidFlowchartParser()
    private val nodePositions: LinkedHashMap<NodeId, Rect> = LinkedHashMap()

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

        // Drain complete lines (delimited by NEWLINE). On EOS, the trailing partial line is also drained.
        val lines = drainLines(isFinal)
        val newPatches = ArrayList<IrPatch>()
        val addedNodeIds = ArrayList<NodeId>()
        for (lineToks in lines) {
            val batch = parser.acceptLine(lineToks)
            for (p in batch.patches) {
                newPatches += p
                if (p is IrPatch.AddNode) addedNodeIds += p.node.id
            }
        }

        val ir: GraphIR = parser.snapshot()
        val laidOut: LaidOutDiagram = recomputeLayout(ir, seq)
        val drawCommands: List<DrawCommand> = renderDraw(ir, laidOut)
        val newDiagnostics = newPatches.filterIsInstance<IrPatch.AddDiagnostic>().map { it.diagnostic }

        val snapshot = DiagramSnapshot(
            ir = ir,
            laidOut = laidOut,
            drawCommands = drawCommands,
            diagnostics = parser.diagnosticsSnapshot(),
            seq = seq,
            isFinal = isFinal,
            sourceLanguage = previousSnapshot.sourceLanguage,
        )
        val patch = SessionPatch(
            seq = seq,
            addedNodes = addedNodeIds,
            addedEdges = newPatches.filterIsInstance<IrPatch.AddEdge>().map { it.edge },
            addedDrawCommands = drawCommands,
            newDiagnostics = newDiagnostics,
            isFinal = isFinal,
        )
        return PipelineAdvance(snapshot = snapshot, patch = patch)
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
            // Drop drained tokens (everything before `start`).
            val tail = if (start < tokenBuffer.size) tokenBuffer.subList(start, tokenBuffer.size).toList() else emptyList()
            tokenBuffer.clear()
            tokenBuffer.addAll(tail)
        }
        return out
    }

    // --- Trivial pinned layout (Phase 1 placeholder) ---

    private fun recomputeLayout(ir: GraphIR, seq: Long): LaidOutDiagram {
        val nodeW = 120f; val nodeH = 48f
        val gapX = 40f; val gapY = 60f
        val isHorizontal = ir.styleHints.direction == Direction.LR || ir.styleHints.direction == Direction.RL

        for (n in ir.nodes) {
            if (nodePositions.containsKey(n.id)) continue
            val idx = nodePositions.size
            val x = if (isHorizontal) idx * (nodeW + gapX) else 0f
            val y = if (isHorizontal) 0f else idx * (nodeH + gapY)
            nodePositions[n.id] = Rect.ltrb(x, y, x + nodeW, y + nodeH)
        }
        val routes = ir.edges.mapNotNull { e ->
            val a = nodePositions[e.from] ?: return@mapNotNull null
            val b = nodePositions[e.to] ?: return@mapNotNull null
            EdgeRoute(
                from = e.from,
                to = e.to,
                points = listOf(centerOf(a), centerOf(b)),
                kind = RouteKind.Polyline,
            )
        }
        val maxRight = (nodePositions.values.maxOfOrNull { it.right } ?: 0f) + 16f
        val maxBottom = (nodePositions.values.maxOfOrNull { it.bottom } ?: 0f) + 16f
        return LaidOutDiagram(
            source = ir,
            nodePositions = nodePositions.toMap(),
            edgeRoutes = routes,
            bounds = Rect.ltrb(0f, 0f, maxRight, maxBottom),
            seq = seq,
        )
    }

    private fun centerOf(r: Rect): Point =
        Point((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)

    private fun renderDraw(ir: GraphIR, laidOut: LaidOutDiagram): List<DrawCommand> {
        val out = ArrayList<DrawCommand>(ir.nodes.size * 3 + ir.edges.size)
        val font = FontSpec(family = "sans-serif", sizeSp = 13f)
        val nodeFill = Color(0xFFE3F2FDU.toInt())
        val nodeStroke = Color(0xFF1565C0U.toInt())
        val edgeColor = Color(0xFF455A64U.toInt())
        val textColor = Color(0xFF0D47A1U.toInt())
        val stroke = Stroke(width = 1.5f)

        for (n in ir.nodes) {
            val r = laidOut.nodePositions[n.id] ?: continue
            out += DrawCommand.FillRect(rect = r, color = nodeFill, corner = 6f, z = 1)
            out += DrawCommand.StrokeRect(rect = r, stroke = stroke, color = nodeStroke, corner = 6f, z = 2)
            val labelStr = (n.label as? RichLabel.Plain)?.text?.ifEmpty { n.id.value } ?: n.id.value
            out += DrawCommand.DrawText(
                text = labelStr,
                origin = Point(r.left + 8f, r.top + r.size.height / 2f + 4f),
                font = font,
                color = textColor,
                maxWidth = r.size.width - 16f,
                z = 3,
            )
        }
        for (route in laidOut.edgeRoutes) {
            val (a, b) = route.points
            val path = PathCmd(listOf(PathOp.MoveTo(a), PathOp.LineTo(b)))
            out += DrawCommand.StrokePath(path = path, stroke = stroke, color = edgeColor, z = 0)
        }
        return out
    }

    override fun dispose() {
        nodePositions.clear()
        tokenBuffer.clear()
    }
}
